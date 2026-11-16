package dev.mirutime.app.features.webview.data

import android.os.SystemClock
import android.webkit.WebView
import dev.mirutime.core.host.KeyValueStore
import dev.mirutime.core.log.MiruLog
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// Mirrors the localStorage of the page open in the in-app browser into the
// plugin store that Lua get_localStorage(hostOrUrl, key) reads
// (namespace = host, key = localStorage key; see core StorageBindings).
//
// Nothing filled those entries, so a plugin keeping its session in
// localStorage (RanobeLib, MangaLib and HentaiLib via cdnlibs.lua) stayed
// signed out after the user signed in here. Cookie sessions never had the
// problem: the WebView and the plugin HTTP client share CookieManager.
//
// The page is untrusted and mirutime_kv is one file shared by every plugin:
// - the namespace is the host of view.url, read when the script is sent and
//   again when the answer arrives (a page that navigated in between is
//   dropped), never anything the script returns;
// - only plain DNS hosts are mirrored, so a page cannot address the namespaces
//   the app keeps there for itself ("repo", "lua_preferences", ...);
// - entries are capped, and text SharedPreferences cannot write as XML is
//   dropped, because one bad character would make the whole file unreadable.
internal const val LOCAL_STORAGE_POLL_MS = 3_000L
internal const val LOCAL_STORAGE_MIN_GAP_MS = 1_000L
internal const val LOCAL_STORAGE_MAX_ENTRIES = 256
internal const val LOCAL_STORAGE_MAX_KEY_CHARS = 512
internal const val LOCAL_STORAGE_MAX_VALUE_CHARS = 16 * 1024
internal const val LOCAL_STORAGE_MAX_TOTAL_CHARS = 64 * 1024
// shortcut: every visited host gets its own namespace and nothing prunes
// them, so the store grows with browsing; add eviction once there is a host
// list to iterate (the sync only ever sees the page in front of it).

// Which keys this class wrote for a host, so a key the page later removes (a
// logout) is removed here too; KeyValueStore cannot enumerate. A page key that
// starts like this is refused, so it cannot rewrite the bookkeeping.
internal const val LOCAL_STORAGE_INDEX_KEY = "__miru_synced_keys"
private const val RESERVED_KEY_PREFIX = "__miru_"

// localStorage is already bounded near 5 MB per origin; these only guard
// against a pathological page.
private const val SCAN_LIMIT = 4096
private const val MAX_RESULT_CHARS = 1 shl 20

// Runs inside the page: a JSON array of [key, value] pairs, or null. Smallest
// entries win, so a session token is not crowded out by big caches;
// parseLocalStorageDump applies the same rule again on this side.
internal val LOCAL_STORAGE_DUMP_JS: String = """
    (function () {
      try {
        var protocol = location.protocol;
        if (protocol !== 'http:' && protocol !== 'https:') return null;
        var storage = window.localStorage, all = [];
        for (var i = 0; i < storage.length && i < $SCAN_LIMIT; i++) {
          var key = storage.key(i);
          if (key === null || key.length > $LOCAL_STORAGE_MAX_KEY_CHARS) continue;
          var value = storage.getItem(key);
          if (value === null || value.length > $LOCAL_STORAGE_MAX_VALUE_CHARS) continue;
          all.push([key, value]);
        }
        all.sort(function (a, b) {
          return (a[0].length + a[1].length) - (b[0].length + b[1].length);
        });
        var out = [], total = 0;
        for (var j = 0; j < all.length && out.length < $LOCAL_STORAGE_MAX_ENTRIES; j++) {
          total += all[j][0].length + all[j][1].length;
          if (total > $LOCAL_STORAGE_MAX_TOTAL_CHARS) break;
          out.push(all[j]);
        }
        return JSON.stringify(out);
      } catch (e) {
        return null;
      }
    })()
""".trimIndent()

// Dot-separated hostname labels, at least two: no single label and no
// underscore, so no namespace the app itself uses can match.
private val HOST_NAME =
    Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")
private val PAGE_AUTHORITY = Regex("^https?://([^/?#]*)", RegexOption.IGNORE_CASE)

// True for a namespace the in-app browser fills (a page host). Those entries
// hold site sessions and every plugin can read them, so the plugin-kv backup
// leaves them out; the browser refills them on the next visit.
internal fun isBrowserStorageNamespace(namespace: String): Boolean = HOST_NAME.matches(namespace)

// The store namespace for a page, or null for a page that must not be
// mirrored. The host is lowercased, as core's hostOf() does for a plugin
// lookup; parsed by hand because Uri/URI reject some URLs real sites use.
internal fun localStorageHostOf(pageUrl: String?): String? {
    val authority = PAGE_AUTHORITY.find(pageUrl.orEmpty())?.groupValues?.get(1) ?: return null
    val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
    return host.takeIf { it.length <= 253 && HOST_NAME.matches(it) }
}

// SharedPreferences writes control characters as XML character references the
// parser then refuses to read back, losing the whole file. Page strings are
// arbitrary UTF-16, so anything XML 1.0 cannot carry stays out.
private fun String.isStorable(): Boolean {
    var i = 0
    while (i < length) {
        val c = this[i]
        when {
            c.isHighSurrogate() -> {
                if (i + 1 >= length || !this[i + 1].isLowSurrogate()) return false
                i++
            }
            c.isLowSurrogate() -> return false
            c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> return false
            c == '\uFFFE' || c == '\uFFFF' -> return false
        }
        i++
    }
    return true
}

private fun jsonElement(text: String): JsonElement? = try {
    Json.parseToJsonElement(text)
} catch (_: SerializationException) {
    // Page data we cannot parse is not an error to report: it is not ours.
    null
}

private fun JsonElement?.jsonString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

// The dump has a fixed shape (an array of two-element arrays); anything nested
// deeper is not ours. Json.parseToJsonElement recurses, so a page that replaced
// JSON.stringify could otherwise return enough brackets to overflow the stack.
private fun isShallow(text: String): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '[', '{' -> if (++depth > 3) return false
            ']', '}' -> depth--
        }
    }
    return true
}

internal class LocalStorageDump(
    val entries: Map<String, String>,
    // The page held more than the budget: a key missing from [entries] may
    // still be on the page, so its absence is not a logout.
    val truncated: Boolean,
)

// What evaluateJavascript hands back for LOCAL_STORAGE_DUMP_JS: the script's
// string result, JSON-encoded once more. Null for anything unusable; an empty
// map means the page's localStorage really is empty.
internal fun parseLocalStorageDump(result: String?): LocalStorageDump? {
    if (result == null || result.length > MAX_RESULT_CHARS) return null
    val text = jsonElement(result).jsonString() ?: return null
    if (!isShallow(text)) return null
    val pairs = jsonElement(text) as? JsonArray ?: return null

    val entries = LinkedHashMap<String, String>()
    for (item in pairs) {
        val pair = item as? JsonArray ?: continue
        if (pair.size != 2) continue
        val key = pair[0].jsonString() ?: continue
        val value = pair[1].jsonString() ?: continue
        if (key.isEmpty() || key.length > LOCAL_STORAGE_MAX_KEY_CHARS) continue
        if (key.startsWith(RESERVED_KEY_PREFIX)) continue
        if (value.length > LOCAL_STORAGE_MAX_VALUE_CHARS) continue
        if (!key.isStorable() || !value.isStorable()) continue
        entries[key] = value
    }
    return withinBudget(entries)
}

// Smallest entries first, stopping at the first one that no longer fits; the
// same rule as the page-side script.
private fun withinBudget(entries: Map<String, String>): LocalStorageDump {
    val kept = LinkedHashMap<String, String>()
    var total = 0
    for ((key, value) in entries.entries.sortedBy { it.key.length + it.value.length }) {
        if (kept.size >= LOCAL_STORAGE_MAX_ENTRIES) break
        total += key.length + value.length
        if (total > LOCAL_STORAGE_MAX_TOTAL_CHARS) break
        kept[key] = value
    }
    return LocalStorageDump(kept, truncated = kept.size < entries.size)
}

class WebViewLocalStorageSync(private val store: KeyValueStore) {

    private var lastDumpAt = 0L

    // Samples the page into the store. Main thread only (WebView API). Calls
    // inside the minimum gap are dropped unless forced: onPageFinished and a
    // history update arrive together. Best effort by nature: a token written
    // and abandoned between two samples is only caught by the poll.
    fun sync(view: WebView, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastDumpAt < LOCAL_STORAGE_MIN_GAP_MS) return
        lastDumpAt = now
        val urlWhenRequested = view.url
        view.evaluateJavascript(LOCAL_STORAGE_DUMP_JS) { result ->
            mirror(urlWhenRequested, view.url, result)
        }
    }

    // The script runs in whichever document is current when it executes, so a
    // page that navigated after the request may answer with another site's
    // entries: those are dropped. A failed evaluation is not an empty page.
    internal fun mirror(urlWhenRequested: String?, urlWhenReceived: String?, result: String?) {
        val host = localStorageHostOf(urlWhenRequested) ?: return
        if (localStorageHostOf(urlWhenReceived) != host) return
        val dump = parseLocalStorageDump(result) ?: return
        val entries = dump.entries

        val previous = readIndex(host)
        var written = 0
        var removed = 0
        for ((key, value) in entries) {
            if (store.get(host, key) == value) continue
            // The page owns its own keys: its value wins over an older one,
            // including one a plugin wrote with set_localStorage.
            store.set(host, key, value)
            written++
        }
        // Only keys this class wrote: what a plugin saved with set_localStorage
        // is not the page's to remove. A dump cut down to the budget says
        // nothing about the keys it left out, so it removes nothing.
        if (!dump.truncated) {
            for (key in previous) {
                if (key in entries) continue
                store.remove(host, key)
                removed++
            }
            if (previous != entries.keys) writeIndex(host, entries.keys)
        }
        if (written + removed > 0) {
            // Counts only: hosts, keys and values can identify a session.
            MiruLog.event("webview", "storage_sync", "written" to written, "removed" to removed)
        }
    }

    private fun readIndex(host: String): Set<String> {
        val raw = store.get(host, LOCAL_STORAGE_INDEX_KEY) ?: return emptySet()
        val array = jsonElement(raw) as? JsonArray ?: return emptySet()
        return array.mapNotNullTo(LinkedHashSet()) { it.jsonString() }
    }

    private fun writeIndex(host: String, keys: Set<String>) {
        if (keys.isEmpty()) {
            store.remove(host, LOCAL_STORAGE_INDEX_KEY)
        } else {
            store.set(host, LOCAL_STORAGE_INDEX_KEY, JsonArray(keys.map(::JsonPrimitive)).toString())
        }
    }
}
