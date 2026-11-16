package dev.mirutime.app.features.webview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewSiteDataTest {

    @Test
    fun `cookie header turns into unique names`() {
        assertEquals(
            listOf("token", "cf_clearance"),
            cookieNames("token=abc; cf_clearance=xyz; token=abc"),
        )
        assertEquals(emptyList<String>(), cookieNames(null))
        assertEquals(emptyList<String>(), cookieNames(""))
        // A cookie with no value still has to be expired by name.
        assertEquals(listOf("empty"), cookieNames("empty="))
    }

    @Test
    fun `paths cover the root and every directory prefix`() {
        assertEquals(listOf("/"), cookiePaths("https://ranobelib.me"))
        assertEquals(listOf("/"), cookiePaths("https://ranobelib.me/"))
        assertEquals(
            listOf("/", "/ru/", "/ru/book/", "/ru/book/page.html"),
            cookiePaths("https://ranobelib.me/ru/book/page.html?x=1#top"),
        )
        // A non-http scheme still gets the root path, never an empty list.
        assertEquals(listOf("/"), cookiePaths("about:blank"))
    }

    @Test
    fun `paths stop at the limit a page could push`() {
        val deep = "https://ranobelib.me" + "/a".repeat(200)
        assertEquals(8, cookiePaths(deep).size)
    }

    @Test
    fun `expired headers cover every path, scheme and cookie identity`() {
        val headers = expiredCookieHeaders("https://ranobelib.me/ru/book", listOf("token"))

        // 3 paths x 3 identities x 2 schemes.
        assertEquals(18, headers.size)
        assertEquals(
            setOf("https://ranobelib.me/", "http://ranobelib.me/"),
            headers.map { it.first }.toSet(),
        )
        assertTrue(headers.all { (_, header) -> "token=; Max-Age=0; Path=" in header })
        val targets = headers.filter { it.first == "https://ranobelib.me/" }.map { it.second }.toSet()
        assertEquals(
            setOf(
                "token=; Max-Age=0; Path=/",
                "token=; Max-Age=0; Path=/; Domain=ranobelib.me",
                "token=; Max-Age=0; Path=/; Domain=.ranobelib.me",
                "token=; Max-Age=0; Path=/ru/",
                "token=; Max-Age=0; Path=/ru/; Domain=ranobelib.me",
                "token=; Max-Age=0; Path=/ru/; Domain=.ranobelib.me",
                "token=; Max-Age=0; Path=/ru/book",
                "token=; Max-Age=0; Path=/ru/book; Domain=ranobelib.me",
                "token=; Max-Age=0; Path=/ru/book; Domain=.ranobelib.me",
            ),
            targets,
        )
    }

    @Test
    fun `nothing to expire for an unusable page or an empty header`() {
        assertEquals(emptyList<Pair<String, String>>(), expiredCookieHeaders("about:blank", listOf("token")))
        assertEquals(
            emptyList<Pair<String, String>>(),
            expiredCookieHeaders("https://ranobelib.me/ru", emptyList()),
        )
    }

    @Test
    fun `a subdomain also expires the cookies it inherits from its parents`() {
        assertEquals(
            listOf(
                "",
                "; Domain=m.ranobelib.me",
                "; Domain=.m.ranobelib.me",
                "; Domain=ranobelib.me",
                "; Domain=.ranobelib.me",
            ),
            cookieDomains("m.ranobelib.me"),
        )
        // An apex stops before the public suffix.
        assertEquals(
            listOf("", "; Domain=ranobelib.me", "; Domain=.ranobelib.me"),
            cookieDomains("ranobelib.me"),
        )
        val headers = expiredCookieHeaders("https://m.ranobelib.me/ru", listOf("sid"))
        // 2 paths x 5 identities x 2 schemes.
        assertEquals(20, headers.size)
        assertTrue(
            headers.any { (_, header) ->
                header == "sid=; Max-Age=0; Path=/; Domain=.ranobelib.me"
            },
        )
    }

    @Test
    fun `page storage script clears both per-origin stores`() {
        assertTrue(CLEAR_PAGE_STORAGE_JS.contains("localStorage.clear()"))
        assertTrue(CLEAR_PAGE_STORAGE_JS.contains("sessionStorage.clear()"))
    }
}
