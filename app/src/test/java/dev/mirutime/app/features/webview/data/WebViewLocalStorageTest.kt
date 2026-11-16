package dev.mirutime.app.features.webview.data

import dev.mirutime.core.host.KeyValueStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewLocalStorageTest {

    private class RecordingStore : KeyValueStore {
        val data = LinkedHashMap<Pair<String, String>, String>()
        var writes = 0

        override fun get(namespace: String, key: String): String? = data[namespace to key]

        override fun set(namespace: String, key: String, value: String) {
            data[namespace to key] = value
            writes++
        }

        override fun remove(namespace: String, key: String) {
            if (data.remove(namespace to key) != null) writes++
        }

        fun keysOf(namespace: String): Set<String> =
            data.keys.filter { it.first == namespace }.map { it.second }.toSet()
    }

    private val page = "https://ranobelib.me/ru/book/1"
    private val authJson = """{"token":{"access_token":"abc"},"auth":{"id":7}}"""

    // What WebView.evaluateJavascript hands back for the dump script: the
    // string the script returned, JSON-encoded once more.
    private fun result(vararg entries: Pair<String, String>): String {
        val pairs = entries.map { (key, value) ->
            JsonArray(listOf(JsonPrimitive(key), JsonPrimitive(value)))
        }
        return JsonPrimitive(JsonArray(pairs).toString()).toString()
    }

    private fun rawResult(innerJson: String): String = JsonPrimitive(innerJson).toString()

    private fun parseKeys(index: String?): Set<String> =
        (Json.parseToJsonElement(index!!) as JsonArray).map { (it as JsonPrimitive).content }.toSet()

    // ---- page URL -> store namespace --------------------------------------

    @Test
    fun `host is the lowercase hostname without port or credentials`() {
        assertEquals("ranobelib.me", localStorageHostOf("https://ranobelib.me/ru/book/1?x=1#top"))
        assertEquals("ranobelib.me", localStorageHostOf("HTTPS://RanobeLib.ME:8443/"))
        assertEquals("ranobelib.me", localStorageHostOf("http://user:pw@ranobelib.me/a"))
        assertEquals("192.168.1.10", localStorageHostOf("http://192.168.1.10:8080/library"))
        assertEquals("xn--80ak6aa92e.com", localStorageHostOf("https://xn--80ak6aa92e.com/"))
        // java.net.URI rejects '|' in a query; real sites still use it.
        assertEquals("ranobelib.me", localStorageHostOf("https://ranobelib.me/catalog?types=1|2"))
    }

    @Test
    fun `host is what core reads for the same URL`() {
        // StorageBindings.hostOf is java.net.URI(url).host; writer and reader
        // must agree or the plugin never finds what the browser saved.
        listOf(
            "https://ranobelib.me/ru/book/1",
            "http://mangalib.org:8080/x?y=1#z",
            "https://hentailib.org/",
            "https://api.cdnlibs.org/api/user/me",
        ).forEach { url ->
            assertEquals(url, java.net.URI(url).host, localStorageHostOf(url))
        }
    }

    @Test
    fun `host rejects pages that are not plain http hostnames`() {
        listOf(
            null,
            "",
            "about:blank",
            "file:///sdcard/page.html",
            "data:text/html,hi",
            "javascript:alert(1)",
            "blob:https://ranobelib.me/5f1a",
            "chrome-error://chromewebdata/",
            "https:///path",
            "https://[::1]/",
            "https://exa mple.com/",
            "https://a..com/",
            "https://-a.com/",
            "https://a.com./",
            "ftp://ranobelib.me/",
        ).forEach { url -> assertNull(url, localStorageHostOf(url)) }
    }

    @Test
    fun `host rejects the namespaces the app keeps for itself`() {
        // Single labels and underscores cannot match, so a page can never be
        // pointed at the settings, repository or enabled-plugin entries.
        listOf(
            "https://lua_preferences/",
            "http://repo/",
            "http://plugins_enabled/x",
            "http://plugin_base_url/x",
            "http://localhost:8080/",
        ).forEach { url -> assertNull(url, localStorageHostOf(url)) }
    }

    // ---- result text -> entries -------------------------------------------

    @Test
    fun `parse reads the text a real WebView returned`() {
        // Chromium running LOCAL_STORAGE_DUMP_JS on a page with two keys, in
        // the form evaluateJavascript hands it back.
        val captured =
            """"[[\"theme\",\"dark\"],""" +
                """[\"auth\",\"{\\\"token\\\":{\\\"access_token\\\":\\\"abc\\\"},\\\"auth\\\":{\\\"id\\\":7}}\"]]""""
        assertEquals(
            mapOf("theme" to "dark", "auth" to authJson),
            parseLocalStorageDump(captured)!!.entries,
        )
    }

    @Test
    fun `parse treats missing, null and malformed results as unusable`() {
        listOf(
            null,
            "",
            "null",
            "undefined",
            "42",
            "{}",
            "[[\"a\",\"b\"]]", // an array, not the string the script returns
            "\"not json\"",
            "\"{}\"", // the inner text must be an array
            "\"[[\\\"a\\\"\"", // truncated inner JSON
        ).forEach { text -> assertNull(text?.take(40), parseLocalStorageDump(text)) }
    }

    @Test
    fun `parse returns an empty map for a page with empty storage`() {
        assertEquals(emptyMap<String, String>(), parseLocalStorageDump(rawResult("[]"))!!.entries)
    }

    @Test
    fun `parse refuses a result larger than any legitimate dump`() {
        val huge = rawResult("""[["a","${"x".repeat(1 shl 20)}"]]""")
        assertNull(parseLocalStorageDump(huge))
    }

    @Test
    fun `parse refuses a result nested too deep for the parser`() {
        // A page that replaced JSON.stringify controls this text; the parser
        // recurses, so a wall of '[' would be a stack overflow.
        val deep = rawResult("[".repeat(2 * LOCAL_STORAGE_MAX_ENTRIES + 9))
        assertNull(parseLocalStorageDump(deep))
    }

    @Test
    fun `parse keeps a value that merely contains brackets`() {
        // Depth is what matters, not how many brackets a cached JSON value holds.
        val cached = "[" + (0 until 400).joinToString(",") { "\"$it\"" } + "]"
        val parsed = parseLocalStorageDump(result("cache" to cached, "auth" to authJson))!!

        assertEquals(cached, parsed.entries["cache"])
        assertEquals(authJson, parsed.entries["auth"])
    }

    @Test
    fun `parse drops entries that are malformed, reserved or too big`() {
        val big = "e".repeat(LOCAL_STORAGE_MAX_VALUE_CHARS)
        val longKey = "K".repeat(LOCAL_STORAGE_MAX_KEY_CHARS)
        val inner = JsonArray(
            listOf(
                JsonArray(listOf(JsonPrimitive("ok"), JsonPrimitive("1"))),
                JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive(1))),
                JsonArray(listOf(JsonPrimitive("single"))),
                JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"), JsonPrimitive("c"))),
                JsonPrimitive("loose"),
                JsonArray(listOf(JsonPrimitive(""), JsonPrimitive("empty key"))),
                JsonArray(listOf(JsonPrimitive(LOCAL_STORAGE_INDEX_KEY), JsonPrimitive("[]"))),
                JsonArray(listOf(JsonPrimitive("__miru_anything"), JsonPrimitive("1"))),
                JsonArray(listOf(JsonPrimitive("fits"), JsonPrimitive(big))),
                JsonArray(listOf(JsonPrimitive("over"), JsonPrimitive(big + "e"))),
                JsonArray(listOf(JsonPrimitive(longKey), JsonPrimitive("fits"))),
                JsonArray(listOf(JsonPrimitive(longKey + "K"), JsonPrimitive("over"))),
            ),
        ).toString()

        val parsed = parseLocalStorageDump(rawResult(inner))!!
        assertEquals(setOf("ok", "fits", longKey), parsed.entries.keys)
        assertEquals(big, parsed.entries["fits"])
    }

    @Test
    fun `parse drops text SharedPreferences cannot write back`() {
        // Control characters would make the one SharedPreferences XML file the
        // whole store lives in unreadable, losing every plugin setting.
        val parsed = parseLocalStorageDump(
            result(
                "nul" to "a\u0000b",
                "ctl" to "a\u0001b",
                "esc" to "a\u001Bb",
                "lone-high" to "a\uD800b",
                "lone-low" to "a\uDC00b",
                "ffff" to "x\uFFFFy",
                "bad\u0000key" to "v",
                "layout" to "a\tb\nc\r\nd",
                "emoji" to "😀 hola ñ 日本語",
            ),
        )!!

        assertEquals(setOf("layout", "emoji"), parsed.entries.keys)
        assertEquals("a\tb\nc\r\nd", parsed.entries["layout"])
        assertEquals("😀 hola ñ 日本語", parsed.entries["emoji"])
    }

    @Test
    fun `parse keeps at most the entry limit`() {
        val many = (0 until LOCAL_STORAGE_MAX_ENTRIES + 44).map { "k$it" to "v" }.toTypedArray()
        assertEquals(
            LOCAL_STORAGE_MAX_ENTRIES,
            parseLocalStorageDump(result(*many))!!.entries.size,
        )
    }

    @Test
    fun `parse keeps the small token and drops big caches when over budget`() {
        val caches = (0 until 80).map { "cache%02d".format(it) to "c".repeat(1000) }
        val parsed = parseLocalStorageDump(result(*(caches + ("auth" to authJson)).toTypedArray()))!!

        assertTrue(parsed.entries.containsKey("auth"))
        assertEquals(authJson, parsed.entries["auth"])
        // 65 caches of 1007 chars plus the token fit in 64 KiB; the 66th does not.
        assertEquals(66, parsed.entries.size)
        assertTrue(parsed.entries.containsKey("cache00"))
        assertTrue(!parsed.entries.containsKey("cache79"))
        assertTrue(
            parsed.entries.entries.sumOf { it.key.length + it.value.length } <=
                LOCAL_STORAGE_MAX_TOTAL_CHARS,
        )
    }

    @Test
    fun `parse reports a dump cut down to the budget`() {
        val caches = (0 until 80).map { "cache%02d".format(it) to "c".repeat(1000) }
        val over = parseLocalStorageDump(result(*(caches + ("auth" to authJson)).toTypedArray()))!!
        assertTrue(over.truncated)
        assertTrue(!parseLocalStorageDump(result("theme" to "dark"))!!.truncated)
    }

    @Test
    fun `only browser hosts are kept out of the plugin backup`() {
        assertTrue(isBrowserStorageNamespace("ranobelib.me"))
        assertTrue(isBrowserStorageNamespace("192.168.1.10"))
        listOf("lua_preferences", "repo", "plugins_enabled", "plugin_base_url").forEach {
            assertTrue(it, !isBrowserStorageNamespace(it))
        }
    }

    // ---- entries -> store --------------------------------------------------

    @Test
    fun `sync stores entries where get_localStorage reads them`() {
        val store = RecordingStore()
        WebViewLocalStorageSync(store).mirror(page, page, result("theme" to "dark", "auth" to authJson))

        // core: get_localStorage(hostOrUrl, key) -> KeyValueStore.get(host, key)
        assertEquals("dark", store.get("ranobelib.me", "theme"))
        assertEquals(authJson, store.get("ranobelib.me", "auth"))
    }

    @Test
    fun `sync supports a host given by IP address`() {
        val store = RecordingStore()
        val url = "http://192.168.1.10:8080/library"
        WebViewLocalStorageSync(store).mirror(url, url, result("token" to "t"))

        assertEquals("t", store.get("192.168.1.10", "token"))
    }

    @Test
    fun `sync writes nothing when the page did not change`() {
        val store = RecordingStore()
        val sync = WebViewLocalStorageSync(store)
        sync.mirror(page, page, result("auth" to authJson))
        val writes = store.writes

        sync.mirror(page, page, result("auth" to authJson))

        assertEquals(writes, store.writes)
    }

    @Test
    fun `sync follows a refreshed token`() {
        val store = RecordingStore()
        val sync = WebViewLocalStorageSync(store)
        sync.mirror(page, page, result("auth" to "old"))

        sync.mirror(page, page, result("auth" to "new"))

        assertEquals("new", store.get("ranobelib.me", "auth"))
    }

    @Test
    fun `sync removes keys the page removed and keeps what a plugin saved itself`() {
        val store = RecordingStore()
        val sync = WebViewLocalStorageSync(store)
        sync.mirror(page, page, result("auth" to authJson, "theme" to "dark"))
        // What set_localStorage writes: not part of the page's storage.
        store.set("ranobelib.me", "plugin-cache", "x")

        sync.mirror(page, page, result("theme" to "dark"))

        assertNull(store.get("ranobelib.me", "auth"))
        assertEquals("dark", store.get("ranobelib.me", "theme"))
        assertEquals("x", store.get("ranobelib.me", "plugin-cache"))
    }

    @Test
    fun `sync clears everything it wrote when the page empties its storage`() {
        val store = RecordingStore()
        val sync = WebViewLocalStorageSync(store)
        sync.mirror(page, page, result("auth" to authJson, "theme" to "dark"))
        store.set("ranobelib.me", "plugin-cache", "x")

        sync.mirror(page, page, rawResult("[]"))

        // A logout that wipes localStorage must not leave the token behind.
        assertEquals(setOf("plugin-cache"), store.keysOf("ranobelib.me"))
    }

    @Test
    fun `sync does not treat a truncated dump as removals`() {
        val store = RecordingStore()
        val sync = WebViewLocalStorageSync(store)
        sync.mirror(page, page, result("auth" to authJson, "theme" to "dark"))
        val caches = (0 until 400).map { "cache%03d".format(it) to "c".repeat(400) }

        sync.mirror(page, page, result(*caches.toTypedArray()))

        // The budget left these out, but the page still has them.
        assertEquals(authJson, store.get("ranobelib.me", "auth"))
        assertEquals("dark", store.get("ranobelib.me", "theme"))
    }

    @Test
    fun `sync does not read a failed evaluation as a logout`() {
        val store = RecordingStore()
        val sync = WebViewLocalStorageSync(store)
        sync.mirror(page, page, result("auth" to authJson))
        val before = store.data.toMap()

        listOf(null, "null", "garbage", "{}").forEach { sync.mirror(page, page, it) }

        assertEquals(before, store.data)
    }

    @Test
    fun `sync drops a result when the page moved to another host meanwhile`() {
        // The script runs in whichever document is current when it executes, so
        // entries answered after a navigation may belong to another site.
        val store = RecordingStore()
        WebViewLocalStorageSync(store).mirror(
            urlWhenRequested = page,
            urlWhenReceived = "https://evil.example/landing",
            result = result("auth" to "forged"),
        )

        assertTrue(store.data.isEmpty())
    }

    @Test
    fun `sync ignores pages that could address another namespace`() {
        val store = RecordingStore()
        val sync = WebViewLocalStorageSync(store)
        listOf(
            "https://lua_preferences/",
            "http://repo/",
            "http://plugins_enabled/",
            "about:blank",
            null,
        ).forEach { url -> sync.mirror(url, url, result("baseUrl" to "https://evil.example")) }

        assertTrue(store.data.isEmpty())
    }

    @Test
    fun `sync keeps hosts apart`() {
        val store = RecordingStore()
        val sync = WebViewLocalStorageSync(store)
        sync.mirror(page, page, result("auth" to "a"))
        val other = "https://mangalib.org/ru/manga/9"
        sync.mirror(other, other, result("auth" to "b"))

        assertEquals("a", store.get("ranobelib.me", "auth"))
        assertEquals("b", store.get("mangalib.org", "auth"))

        // Emptying one host leaves the other alone.
        sync.mirror(page, page, rawResult("[]"))
        assertNull(store.get("ranobelib.me", "auth"))
        assertEquals("b", store.get("mangalib.org", "auth"))
    }

    @Test
    fun `sync never lets a page key overwrite its own bookkeeping`() {
        val store = RecordingStore()
        val sync = WebViewLocalStorageSync(store)
        sync.mirror(
            page,
            page,
            result(LOCAL_STORAGE_INDEX_KEY to "[\"theme\"]", "auth" to authJson),
        )

        assertEquals(setOf("auth"), parseKeys(store.get("ranobelib.me", LOCAL_STORAGE_INDEX_KEY)))
        sync.mirror(page, page, result("theme" to "dark"))
        assertNull(store.get("ranobelib.me", "auth"))
    }

    @Test
    fun `sync recovers from a damaged index`() {
        val store = RecordingStore()
        store.set("ranobelib.me", LOCAL_STORAGE_INDEX_KEY, "{not json")

        WebViewLocalStorageSync(store).mirror(page, page, result("auth" to authJson))

        assertEquals(authJson, store.get("ranobelib.me", "auth"))
        assertEquals(setOf("auth"), parseKeys(store.get("ranobelib.me", LOCAL_STORAGE_INDEX_KEY)))
    }
}
