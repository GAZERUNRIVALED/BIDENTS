package dev.mirutime.app.features.library.data

import dev.mirutime.core.log.MiruLog
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.libraryBookKey
import dev.mirutime.core.api.PluginError
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

data class UpdateCheckSummary(
    val checked: Int,
    val updated: Int,
    val failed: Int,
    val firstError: PluginError? = null,
    val firstErrorSource: String? = null,
    val newChapters: Int = 0,
    val lines: List<Pair<String, Int>> = emptyList(),
    val skipped: Int = 0,
)

/** Live progress for the Updates screen: books finished / total. */
data class UpdateProgress(
    val done: Int,
    val total: Int,
    val title: String,
)

/**
 * Pulls the current chapter list for library books and records the delta.
 * It never deletes/updates a book on a failed fetch and serializes writers
 * through the repository's normal transactions.
 */
class LibraryUpdateService(
    private val provider: LibraryBookRefreshPort,
    private val library: LibraryRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxConcurrency: Int = 3,
) {
    // Per-run source health: after a few consecutive failures one flaky source
    // must not spend the whole run timing out. No persistence by design.
    private data class SourceHealth(val failures: Int = 0, val cooldownUntil: Long = 0L)

    private val sourceHealth = ConcurrentHashMap<String, SourceHealth>()

    suspend fun checkAll(
        onProgress: (UpdateProgress) -> Unit = {},
    ): UpdateCheckSummary = updateGate.withLock { runCheckAll(onProgress) }

    private suspend fun runCheckAll(
        onProgress: (UpdateProgress) -> Unit,
    ): UpdateCheckSummary {
        // A shelf can opt out of update checks; honoring it here keeps the
        // worker and the manual "check now" action consistent.
        val excludedShelves = excludedShelfIds()
        val books = library.libraryBooks()
            .filterNot { it.isLocalOnly() || it.shelfId in excludedShelves }
        if (books.isEmpty()) return UpdateCheckSummary(0, 0, 0)
        sourceHealth.clear()
        val gate = Semaphore(maxConcurrency.coerceAtLeast(1))
        val total = books.size
        val done = AtomicInteger(0)
        val checked = AtomicInteger(0)
        val updated = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val newChapters = AtomicInteger(0)
        val lines = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        val firstError = AtomicReference<UpdateResult.Failed?>(null)
        val progressLock = Any()
        fun report(progress: UpdateProgress) = synchronized(progressLock) { onProgress(progress) }
        report(UpdateProgress(0, total, ""))
        coroutineScope {
            // One coroutine per source: books of a source are checked one by
            // one, while the global permit still bounds how many sources (and
            // therefore provider calls) run at once.
            books.groupBy { it.installId }.forEach { (_, sourceBooks) ->
                launch {
                    for (row in sourceBooks) {
                        gate.withPermit {
                            report(UpdateProgress(done.get(), total, row.title))
                            when (
                                val result = check(
                                    row.bookId,
                                    row.url,
                                    row.totalChapters,
                                    row.chapterListHash,
                                    row.installId,
                                    row.chaptersLastPage,
                                )
                            ) {
                                is UpdateResult.Failed -> {
                                    failed.incrementAndGet()
                                    firstError.compareAndSet(null, result)
                                }
                                UpdateResult.Skipped -> skipped.incrementAndGet()
                                UpdateResult.NoChange -> checked.incrementAndGet()
                                is UpdateResult.Updated -> {
                                    checked.incrementAndGet()
                                    updated.incrementAndGet()
                                    newChapters.addAndGet(result.newChapters)
                                    lines += row.title to result.newChapters
                                }
                            }
                            report(UpdateProgress(done.incrementAndGet(), total, row.title))
                        }
                    }
                }
            }
        }
        val first = firstError.get()
        return UpdateCheckSummary(
            checked = checked.get(),
            updated = updated.get(),
            failed = failed.get(),
            firstError = first?.error,
            firstErrorSource = first?.takeIf { it.error != null }?.sourceId,
            newChapters = newChapters.get(),
            lines = lines.toList(),
            skipped = skipped.get(),
        )
    }

    /** Refresh one book; returns the number of newly discovered chapters. */
    suspend fun checkBook(bookUrl: String): Int = updateGate.withLock { runCheckBook(bookUrl) }

    private suspend fun runCheckBook(bookUrl: String): Int {
        val row = library.book(bookUrl) ?: return 0
        if (row.isLocalOnly() || row.shelfId in excludedShelfIds()) return 0
        return when (
            val result = check(
                row.bookId,
                row.url,
                row.totalChapters,
                row.chapterListHash,
                row.installId,
                row.chaptersLastPage,
            )
        ) {
            is UpdateResult.Failed, UpdateResult.NoChange, UpdateResult.Skipped -> 0
            is UpdateResult.Updated -> result.newChapters
        }
    }

    suspend fun markSeen(bookUrl: String) {
        library.book(bookUrl)?.let { library.markUpdatesSeen(it.bookId, now()) }
    }

    private suspend fun excludedShelfIds(): Set<String> =
        library.shelves().filter { it.excludeFromUpdates }.map { it.id }.toSet()

    // Update checks only make sense for plugin/local-file sources. An OPDS
    // audiobook has no chapter feed to refresh; counting it as failed would
    // report every catalogue book as broken.
    private fun BookEntity.isLocalOnly(): Boolean =
        installId.startsWith("local-") ||
            url.startsWith("local://") ||
            installId.startsWith("opds:")

    private suspend fun check(
        bookId: Long,
        bookUrl: String,
        before: Int,
        localHash: String?,
        sourceId: String,
        lastPage: Int?,
    ): UpdateResult {
        val health = sourceHealth[sourceId]
        if (health != null && health.cooldownUntil > now()) {
            MiruLog.d(
                "updates",
                "event=source-skip source=$sourceId consecutive=${health.failures}",
            )
            return UpdateResult.Skipped
        }
        // Variant 1 (NoveLA getChapterListHash): a plugin-provided hash that
        // matches the stored one means the list is unchanged; skip the full
        // chapter fetch entirely. A known page means the source is paginated,
        // so the hash is ignored there and probing it only costs a request.
        val bookKey = libraryBookKey(sourceId, bookUrl)
        val remoteHash = if (lastPage == null) {
            try {
                provider.chapterListHash(bookKey)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        if (lastPage == null && remoteHash != null && remoteHash == localHash) {
            recordHealth(sourceId, failed = false)
            MiruLog.d(
                "updates",
                "event=check-book source=$sourceId before=$before after=$before " +
                    "hashPresent=${remoteHash != null} lastPage=$lastPage result=hash-skip",
            )
            return UpdateResult.NoChange
        }
        val refreshed = try {
            provider.refreshChapterCountResult(bookKey, lastPage)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            library.stampLastUpdateCheck(bookId, now())
            recordHealth(sourceId, failed = true)
            MiruLog.d(
                "updates",
                "event=check-book source=$sourceId before=$before after=null " +
                    "hashPresent=${remoteHash != null} lastPage=$lastPage result=failed",
            )
            return UpdateResult.Failed(null, sourceId)
        }
        val error = refreshed.error
        val after = refreshed.count
        if (after == null || after <= 0) {
            // The fetch failed, but the row was checked; an unstamped row would
            // keep looking overdue forever.
            library.stampLastUpdateCheck(bookId, now())
            recordHealth(sourceId, failed = true)
            MiruLog.d(
                "updates",
                "event=check-book source=$sourceId before=$before after=$after " +
                    "hashPresent=${remoteHash != null} lastPage=$lastPage result=failed",
            )
            return UpdateResult.Failed(error, sourceId)
        }
        // The count delta comes from the provider, which already saw the new
        // chapter list; the local row may be stale after a failed write.
        val newChapters = refreshed.added.coerceAtLeast(0)
        library.recordUpdateCheck(bookId, after, remoteHash, now())
        recordHealth(sourceId, failed = false)
        MiruLog.d(
            "updates",
            "event=check-book source=$sourceId before=$before after=$after " +
                "hashPresent=${remoteHash != null} lastPage=$lastPage added=$newChapters " +
                "result=${if (newChapters > 0) "updated" else "unchanged"}",
        )
        return if (newChapters > 0) {
            UpdateResult.Updated(newChapters)
        } else {
            UpdateResult.NoChange
        }
    }

    private fun recordHealth(sourceId: String, failed: Boolean) {
        if (!failed) {
            sourceHealth.remove(sourceId)
            return
        }
        val failures = (sourceHealth[sourceId]?.failures ?: 0) + 1
        sourceHealth[sourceId] = SourceHealth(
            failures = failures,
            cooldownUntil = if (failures >= FailureCooldownThreshold) {
                now() + FailureCooldownMs
            } else {
                0L
            },
        )
    }

    private sealed interface UpdateResult {
        data object NoChange : UpdateResult
        data object Skipped : UpdateResult
        data class Failed(val error: PluginError?, val sourceId: String) : UpdateResult
        data class Updated(val newChapters: Int) : UpdateResult
    }

    private companion object {
        const val FailureCooldownThreshold = 3
        const val FailureCooldownMs = 10 * 60_000L

        /**
         * One check run at a time per process. A run reads the library
         * snapshot, then rewrites chapter rows; two overlapping runs would act
         * on the same stale totals.
         */
        val updateGate = Mutex()
    }
}
