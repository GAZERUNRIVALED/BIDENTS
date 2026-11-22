package dev.mirutime.feature.reader.ui

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import dev.mirutime.feature.reader.R
import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.features.reader.document.BlockKind
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Pure helpers behind the native selection menu's reader items. */
internal object SelectionSearch {

    fun normalize(texts: List<CharSequence>): String =
        texts
            .map { it.toString().trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()

    /** Images and decorative dividers are not worth a web search. */
    fun isSearchable(kind: BlockKind): Boolean =
        kind == BlockKind.Heading || kind == BlockKind.Paragraph

    fun fallbackUrl(query: String): String =
        "https://www.google.com/search?q=" +
            URLEncoder.encode(query, StandardCharsets.UTF_8.name())
}

/**
 * Sends a selection to the user's default search app/browser. Only when no
 * ACTION_WEB_SEARCH handler exists do we open the Google results URL.
 */
fun openWebSearch(context: Context, query: String) {
    val text = query.trim()
    if (text.isEmpty()) return
    MiruLog.event("reader", "selection-search", "chars" to text.length, "lines" to text.count { it == '\n' } + 1)
    val search = Intent(Intent.ACTION_WEB_SEARCH)
        .putExtra(SearchManager.QUERY, text)
    try {
        context.startActivity(search)
    } catch (missingSearch: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(SelectionSearch.fallbackUrl(text))),
            )
        } catch (missingBrowser: ActivityNotFoundException) {
            Toast.makeText(context, R.string.webview_no_app, Toast.LENGTH_SHORT).show()
        }
    }
}

/** Sends the selection to the system share sheet. */
internal fun openShareText(context: Context, text: String) {
    val body = text.trim()
    if (body.isEmpty()) return
    MiruLog.event("reader", "selection-share", "chars" to body.length)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, body)
    context.startActivity(Intent.createChooser(send, null))
}

/** True when an installed app handles PROCESS_TEXT (dictionaries, translators). */
internal fun canProcessText(context: Context): Boolean =
    Intent(Intent.ACTION_PROCESS_TEXT)
        .setType("text/plain")
        .resolveActivity(context.packageManager) != null

/**
 * Hands the selection to a PROCESS_TEXT handler: any installed dictionary or
 * translator receives it as read-only input. A chooser is used because the
 * default handler may be a translator while the item says Dictionary.
 */
internal fun openProcessText(context: Context, text: String) {
    val body = text.trim()
    if (body.isEmpty()) return
    MiruLog.event("reader", "selection-process-text", "chars" to body.length)
    val process = Intent(Intent.ACTION_PROCESS_TEXT)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, body)
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    context.startActivity(Intent.createChooser(process, null))
}
