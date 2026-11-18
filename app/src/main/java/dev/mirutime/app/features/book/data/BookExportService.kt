package dev.mirutime.app.features.book.data

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import dev.mirutime.app.MiruApp
import dev.mirutime.app.R
import dev.mirutime.app.features.settings.data.AppLanguage
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.core.log.MiruLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Live export state for the book screen's tool tiles. */
data class BookExportProgress(
    val bookUrl: String,
    val kind: BookExportKind,
    val done: Int,
    val total: Int,
)

object BookExportMonitor {
    private val _progress = MutableStateFlow<BookExportProgress?>(null)

    /** Null when no export is running. */
    val progress: StateFlow<BookExportProgress?> = _progress.asStateFlow()

    fun begin(bookUrl: String, kind: BookExportKind) {
        _progress.value = BookExportProgress(bookUrl, kind, done = 0, total = 0)
    }

    fun update(done: Int, total: Int) {
        _progress.value = _progress.value?.copy(done = done, total = total)
    }

    fun finish() {
        _progress.value = null
    }
}

/**
 * Single foreground owner for book exports started from the book screen. It
 * exists so a long export survives navigation and the process sleeping, and so
 * both the app and the notification show real progress: the app-scoped job this
 * replaces was cancelled with the composition and left a 0-byte EPUB behind.
 *
 * The export itself stays in the exporters; this class only resolves the SAF
 * destination, streams progress and turns the outcome into a notification.
 */
class BookExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    @Volatile private var job: Job? = null
    private var activeStartId = 0
    @Volatile private var lastPercent = -1

    override fun onCreate() {
        super.onCreate()
        BookExportNotifications.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (!running.get()) stopSelf()
            return START_NOT_STICKY
        }
        MiruLog.event("book", "export-command", "action" to (intent.action ?: "none"))
        when (intent.action) {
            ActionCancel -> {
                MiruLog.event("book", "export-cancel", "source" to "notification")
                val runningJob = job
                if (runningJob == null) {
                    stopSelf()
                } else {
                    runningJob.cancel()
                }
                return START_NOT_STICKY
            }
            ActionEpub, ActionCbz -> {
                val request = BookExportRequest.from(intent)
                if (request == null) {
                    // A start we cannot parse is a failed export, not a no-op:
                    // the picker already created a destination document.
                    MiruLog.event("book", "export-rejected", "reason" to "invalid-request")
                    BookExportNotifications.notifyResult(
                        this,
                        if (intent.action == ActionCbz) BookExportKind.Cbz else BookExportKind.Epub,
                        success = false,
                        message = uiContext.getString(R.string.book_export_failed_title),
                    )
                    if (!running.get()) stopSelf()
                    return START_NOT_STICKY
                }
                // One export at a time: a second start must not interleave two
                // writers. It must also not vanish: the picker already created
                // (or truncated) a destination document.
                if (!running.compareAndSet(false, true)) {
                    MiruLog.event("book", "export-rejected", "reason" to "busy")
                    // Only a file destination may be removed, and only when it
                    // is empty: the CBZ request carries a folder tree, and a
                    // CreateDocument destination may be an existing file.
                    val busyTarget = Uri.parse(request.uri)
                    if (request.kind == BookExportKind.Epub && documentSize(busyTarget) == 0L) {
                        deleteDocument(busyTarget)
                    }
                    BookExportNotifications.notifyResult(
                        this,
                        request.kind,
                        success = false,
                        message = uiContext.getString(R.string.book_export_busy),
                    )
                    if (job == null) stopSelf()
                    return START_NOT_STICKY
                }
                activeStartId = startId
                BookExportMonitor.begin(request.bookUrl, request.kind)
                startForegroundProgress(request.kind, done = 0, total = 0)
                job = scope.launch { execute(request) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        // Nothing may keep the tiles disabled after the service is gone.
        BookExportMonitor.finish()
        running.set(false)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    /** Same language as the notification channel, not the system locale. */
    private val uiContext: Context
        get() = AppLanguage.wrap(this, AppSettings.appLanguage)

    private suspend fun execute(request: BookExportRequest) {
        var success = false
        var cancelled = false
        var message = ""
        MiruLog.event(
            "book",
            "export-start",
            "kind" to request.kind.name.lowercase(),
            "book" to MiruLog.idHash(request.bookUrl),
        )
        try {
            when (request.kind) {
                BookExportKind.Epub -> {
                    val result = runEpub(request)
                    success = result is EpubExportResult.Success
                    message = when (result) {
                        is EpubExportResult.Success -> uiContext.getString(
                            R.string.tool_epub_success_fmt,
                            result.chaptersWritten,
                        )
                        EpubExportResult.NoChapters -> uiContext.getString(R.string.tool_epub_no_chapters)
                        is EpubExportResult.Failure ->
                            uiContext.getString(R.string.tool_epub_failed_fmt, result.message)
                    }
                    MiruLog.event(
                        "book",
                        "epub-result",
                        "book" to MiruLog.idHash(request.bookUrl),
                        "ok" to success,
                        "written" to ((result as? EpubExportResult.Success)?.chaptersWritten ?: 0),
                    )
                }
                BookExportKind.Cbz -> {
                    val result = runCbz(request)
                    success = result is CbzExportResult.Success
                    message = when (result) {
                        is CbzExportResult.Success -> uiContext.getString(
                            R.string.tool_cbz_success_fmt,
                            result.chaptersWritten,
                        )
                        CbzExportResult.NoChapters -> uiContext.getString(R.string.tool_cbz_no_chapters)
                        is CbzExportResult.Failure ->
                            uiContext.getString(R.string.tool_cbz_failed_fmt, result.message)
                    }
                    MiruLog.event(
                        "book",
                        "cbz-result",
                        "book" to MiruLog.idHash(request.bookUrl),
                        "ok" to success,
                        "written" to ((result as? CbzExportResult.Success)?.chaptersWritten ?: 0),
                    )
                }
            }
        } catch (e: CancellationException) {
            message = uiContext.getString(R.string.book_export_cancelled)
            cancelled = true
            MiruLog.event(
                "book",
                "export-result",
                "kind" to request.kind.name.lowercase(),
                "ok" to false,
                "reason" to "cancelled",
            )
            throw e
        } catch (e: Exception) {
            message = e.message ?: uiContext.getString(R.string.book_export_failed_title)
            MiruLog.event(
                "book",
                "export-result",
                "kind" to request.kind.name.lowercase(),
                "ok" to false,
                "error" to (e::class.simpleName ?: "error"),
            )
        } finally {
            if (message.isBlank()) message = uiContext.getString(R.string.book_export_failed_title)
            BookExportMonitor.finish()
            BookExportNotifications.notifyResult(
                this,
                request.kind,
                success = success,
                message = message,
                title = if (cancelled) uiContext.getString(R.string.book_export_cancelled) else null,
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Clear the job before the flag: a start that slips in afterwards
            // must not have its own job nulled by this finally block.
            job = null
            running.set(false)
            // stopSelf(startId) keeps a newer start alive: it must not be
            // killed by the finish of the previous export.
            stopSelf(activeStartId)
        }
    }

    private suspend fun runEpub(request: BookExportRequest): EpubExportResult {
        val app = application as MiruApp
        val target = Uri.parse(request.uri)
        // Export into a local temp file first: a failed/cancelled export must
        // not leave a half-written or empty EPUB at the SAF URI.
        val temp = runCatching {
            File.createTempFile("miru-epub-", ".epub", cacheDir)
        }.getOrNull() ?: return EpubExportResult.Failure("Cannot create a temporary export file")
        // CreateDocument may hand back an EXISTING file the user agreed to
        // replace. Until the copy truncates it, its bytes are still theirs, so
        // cleanup may remove it only when the document was empty to begin with
        // or when this export already truncated it.
        val ownedBefore = documentSize(target) == 0L
        var truncated = false
        try {
            val result = temp.outputStream().use { stream ->
                app.bookEpubExporter.export(
                    bookUrl = request.bookUrl,
                    includeTranslation = request.includeTranslation,
                    outputStream = stream,
                    onProgress = { done, total -> postProgress(request.kind, done, total) },
                    title = request.title,
                    language = request.language,
                )
            }
            if (result !is EpubExportResult.Success) {
                deleteIfOwned(target, ownedBefore, truncated)
                return result
            }
            val output = contentResolver.openOutputStream(target)
            if (output == null) {
                deleteIfOwned(target, ownedBefore, truncated)
                return EpubExportResult.Failure("Cannot open the selected file")
            }
            truncated = true
            output.use { out -> temp.inputStream().use { input -> input.copyTo(out) } }
            return result
        } catch (e: CancellationException) {
            deleteIfOwned(target, ownedBefore, truncated)
            throw e
        } catch (e: Exception) {
            deleteIfOwned(target, ownedBefore, truncated)
            throw e
        } finally {
            temp.delete()
        }
    }

    private suspend fun runCbz(request: BookExportRequest): CbzExportResult {
        val app = application as MiruApp
        return app.bookCbzExporter.export(
            bookUrl = request.bookUrl,
            target = SafCbzOutput(this, Uri.parse(request.uri)),
            onProgress = { done, total -> postProgress(request.kind, done, total) },
        )
    }

    /** Destination size before we touch it; null = the provider cannot tell. */
    private fun documentSize(document: Uri): Long? = runCatching {
        contentResolver.query(
            document,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }.getOrNull()

    private fun deleteIfOwned(document: Uri, ownedBefore: Boolean, truncated: Boolean) {
        if (ownedBefore || truncated) deleteDocument(document)
    }

    // Best effort, but a silent failure leaves the 0-byte document the user
    // reported, so the reason must reach the log. Callers decide ownership: a
    // pre-existing file the picker offered to replace must never be removed.
    private fun deleteDocument(document: Uri) {
        runCatching { DocumentsContract.deleteDocument(contentResolver, document) }
            .onFailure { MiruLog.w("book", "export-delete-failed error=${it.javaClass.simpleName}") }
    }

    private fun startForegroundProgress(kind: BookExportKind, done: Int, total: Int) {
        lastPercent = -1
        val notification = BookExportNotifications.progress(this, kind, done, total)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                BookExportNotifications.ForegroundId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(BookExportNotifications.ForegroundId, notification)
        }
    }

    private fun postProgress(kind: BookExportKind, done: Int, total: Int) {
        BookExportMonitor.update(done, total)
        val percent = bookExportPercent(done, total)
        if (percent == lastPercent) return
        lastPercent = percent
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(
                BookExportNotifications.ForegroundId,
                BookExportNotifications.progress(this, kind, done, total),
            )
        }
    }

    companion object {
        const val ActionEpub = "dev.mirutime.app.action.EXPORT_EPUB"
        const val ActionCbz = "dev.mirutime.app.action.EXPORT_CBZ"
        const val ActionCancel = "dev.mirutime.app.action.CANCEL_EXPORT"

        fun startEpub(
            context: Context,
            bookUrl: String,
            uri: Uri,
            includeTranslation: Boolean,
            title: String,
            language: String? = null,
        ) {
            context.startForegroundService(
                Intent(context, BookExportService::class.java).apply {
                    action = ActionEpub
                    putExtra(BookExportRequest.ExtraBookUrl, bookUrl)
                    putExtra(BookExportRequest.ExtraUri, uri.toString())
                    putExtra(BookExportRequest.ExtraTranslation, includeTranslation)
                    putExtra(BookExportRequest.ExtraTitle, title)
                    putExtra(BookExportRequest.ExtraLanguage, language)
                },
            )
        }

        fun startCbz(context: Context, bookUrl: String, uri: Uri) {
            context.startForegroundService(
                Intent(context, BookExportService::class.java).apply {
                    action = ActionCbz
                    putExtra(BookExportRequest.ExtraBookUrl, bookUrl)
                    putExtra(BookExportRequest.ExtraUri, uri.toString())
                },
            )
        }

        fun cancelIntent(context: Context): Intent =
            Intent(context, BookExportService::class.java).apply { action = ActionCancel }
    }
}

private data class BookExportRequest(
    val kind: BookExportKind,
    val bookUrl: String,
    val uri: String,
    val includeTranslation: Boolean,
    // Display title and BCP47 code for the EPUB metadata; null = from the book.
    val title: String? = null,
    val language: String? = null,
) {
    companion object {
        const val ExtraBookUrl = "export_book_url"
        const val ExtraUri = "export_uri"
        const val ExtraTranslation = "export_include_translation"
        const val ExtraTitle = "export_title"
        const val ExtraLanguage = "export_language"

        fun from(intent: Intent): BookExportRequest? {
            val kind = when (intent.action) {
                BookExportService.ActionEpub -> BookExportKind.Epub
                BookExportService.ActionCbz -> BookExportKind.Cbz
                else -> return null
            }
            val bookUrl = intent.getStringExtra(ExtraBookUrl)?.takeIf { it.isNotBlank() }
                ?: return null
            val uri = intent.getStringExtra(ExtraUri)?.takeIf { it.isNotBlank() }
                ?: return null
            return BookExportRequest(
                kind = kind,
                bookUrl = bookUrl,
                uri = uri,
                includeTranslation = intent.getBooleanExtra(ExtraTranslation, true),
                title = intent.getStringExtra(ExtraTitle)?.takeIf { it.isNotBlank() },
                language = intent.getStringExtra(ExtraLanguage)?.takeIf { it.isNotBlank() },
            )
        }
    }
}
