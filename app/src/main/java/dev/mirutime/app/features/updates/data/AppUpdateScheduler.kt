package dev.mirutime.app.features.updates.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.work.BackoffPolicy
import dev.mirutime.app.MainActivity
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.mirutime.app.R
import dev.mirutime.app.features.settings.data.AppLanguage
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.PersistedSettings
import java.util.concurrent.TimeUnit

/** Daily app-release check promised by Settings → Updates. */
object AppUpdateScheduler {
    private const val UniqueWork = "mirutime-app-update"
    private const val IntervalHours = 24L
    private const val BackoffMinutes = 60L

    fun sync(context: Context, settings: PersistedSettings) {
        val manager = try {
            WorkManager.getInstance(context)
        } catch (_: Exception) {
            return
        }
        if (!settings.appUpdateCheck) {
            runCatching { manager.cancelUniqueWork(UniqueWork) }
            return
        }
        val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(
            IntervalHours,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BackoffMinutes,
                TimeUnit.MINUTES,
            )
            .build()
        runCatching {
            manager.enqueueUniquePeriodicWork(
                UniqueWork,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

    fun notifyAvailable(
        context: Context,
        release: AppUpdateResult.Available,
        currentVersion: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val localized = AppLanguage.wrap(context, AppSettings.appLanguage)
        val manager = localized.getSystemService(NotificationManager::class.java) ?: return
        val channelId = "app_updates"
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                localized.getString(R.string.st_updates_app_notif_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = localized.getString(R.string.st_updates_app_notif_channel_desc)
            },
        )
        val text = localized.getString(R.string.st_updates_app_notif_fmt, release.version)
        val notes = appUpdateReleaseNotes(release.body)
        val openUpdates = PendingIntent.getActivity(
            context,
            7402,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_APP_UPDATE, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openRelease = PendingIntent.getActivity(
            context,
            7403,
            Intent(Intent.ACTION_VIEW, Uri.parse(release.url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(localized, channelId)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setLargeIcon(Icon.createWithResource(localized, R.mipmap.ic_launcher))
            .setContentTitle(localized.getString(R.string.st_updates_app_notif_title))
            .setContentText(text)
            .setSubText(
                localized.getString(
                    R.string.st_updates_app_notif_installed_fmt,
                    currentVersion,
                ),
            )
            .setStyle(Notification.BigTextStyle().bigText(notes.ifBlank { text }))
            .setContentIntent(openUpdates)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(localized, R.drawable.ic_stat_download),
                    localized.getString(R.string.st_updates_app_notif_open_release),
                    openRelease,
                ).build(),
            )
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(7401, notification) }
    }
}

/**
 * GitHub release notes are markdown/HTML; the shade shows their plain text.
 * Templates wrap the changelog in divs, images and comments and repeat it in
 * a RU and an EN spoiler; the notification shows the English spoiler's
 * changelog without artifacts, falling back to the whole body when that
 * spoiler is missing. Markup is stripped first, then the existing prefix
 * cleaning runs, and entities are decoded last so an escaped `&lt;` is not
 * mistaken for a tag.
 */
internal fun appUpdateReleaseNotes(body: String?, maxChars: Int = 800): String =
    body.orEmpty()
        .englishSpoiler()
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
        .replace(Regex("</?[a-zA-Z][^>]*>"), "")
        .lineSequence()
        .map { it.trim().replace(Regex("^[#>*\\-\\s]+"), "") }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .decodeEntities()
        .take(maxChars)
        .trim()

// Inner text of the English spoiler, or the whole body when that spoiler is
// missing; either way the summary label is dropped and the text is cut at the
// artifacts section so the download list and compare link stay out.
private fun String.englishSpoiler(): String {
    val block = Regex("<details[^>]*>(.*?)</details>", RegexOption.DOT_MATCHES_ALL)
        .findAll(this)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .firstOrNull { inner ->
            Regex("<summary[^>]*>(.*?)</summary>", RegexOption.DOT_MATCHES_ALL)
                .find(inner)?.groupValues?.getOrNull(1)
                ?.contains("english", ignoreCase = true) == true
        }
        ?: this
    // The summary label itself ("🇬🇧 English version") stays out of the shade.
    val body = block.replace(Regex("<summary[^>]*>.*?</summary>", RegexOption.DOT_MATCHES_ALL), "")
    val cut = Regex("^##[ \\t]*(Artifacts|Артефакты)", RegexOption.MULTILINE).find(body)
        ?: return body
    return body.substring(0, cut.range.first)
}

private fun String.decodeEntities(): String {
    val named = replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
    return named.replace(Regex("&#(\\d+);")) { match ->
        val code = match.groupValues[1].toIntOrNull()
        if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
    }
}
