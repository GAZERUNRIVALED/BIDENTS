package dev.mirutime.app.features.book.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.mirutime.app.MainActivity
import dev.mirutime.app.R
import dev.mirutime.app.features.settings.data.AppLanguage
import dev.mirutime.app.features.settings.data.AppSettings

/** Which tool started the export; drives the notification title and messages. */
enum class BookExportKind { Epub, Cbz }

/** Progress bar/percentage shared by the notification and the screen tiles. */
fun bookExportPercent(done: Int, total: Int): Int =
    if (total <= 0) 0 else (done * 100 / total).coerceIn(0, 100)

/**
 * Foreground progress + result notifications for book exports. Best-effort like
 * backups: Android 13+ can deny notifications while the service still runs.
 */
object BookExportNotifications {
    const val ChannelId = "book_export"
    const val ForegroundId = 4301
    const val ResultId = 4302
    private const val ContentRequestCode = 4303
    private const val CancelRequestCode = 4304

    private fun localized(context: Context): Context =
        AppLanguage.wrap(context, AppSettings.appLanguage)

    fun ensureChannel(context: Context) {
        val ui = localized(context)
        val manager = ui.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                ui.getString(R.string.book_export_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = ui.getString(R.string.book_export_channel_desc)
                setShowBadge(false)
            },
        )
    }

    fun progress(
        context: Context,
        kind: BookExportKind,
        done: Int,
        total: Int,
    ): Notification {
        ensureChannel(context)
        val ui = localized(context)
        val percent = bookExportPercent(done, total)
        return Notification.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(ui.getString(kind.titleRes()))
            .setContentText(
                if (total > 0) {
                    ui.getString(R.string.book_export_progress_fmt, done, total)
                } else {
                    ui.getString(R.string.book_export_preparing)
                },
            )
            .setProgress(100, percent, total <= 0)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(context))
            .addAction(
                Notification.Action.Builder(
                    null,
                    ui.getString(R.string.book_export_cancel),
                    cancelIntent(context),
                ).build(),
            )
            .build()
    }

    /** [title] overrides the default success/failure title (e.g. "cancelled"). */
    fun notifyResult(
        context: Context,
        kind: BookExportKind,
        success: Boolean,
        message: String,
        title: String? = null,
    ) {
        ensureChannel(context)
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val ui = localized(context)
            manager.notify(
                ResultId,
                Notification.Builder(context, ChannelId)
                    .setSmallIcon(R.drawable.ic_stat_download)
                    .setContentTitle(
                        title ?: ui.getString(
                            if (success) kind.titleRes() else R.string.book_export_failed_title,
                        ),
                    )
                    .setContentText(message)
                    .setStyle(Notification.BigTextStyle().bigText(message))
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent(context))
                    .build(),
            )
        }
    }

    private fun BookExportKind.titleRes(): Int = when (this) {
        BookExportKind.Epub -> R.string.tool_epub
        BookExportKind.Cbz -> R.string.tool_cbz
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            ContentRequestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelIntent(context: Context): PendingIntent = PendingIntent.getService(
        context,
        CancelRequestCode,
        BookExportService.cancelIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
