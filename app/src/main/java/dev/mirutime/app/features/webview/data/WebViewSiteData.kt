package dev.mirutime.app.features.webview.data

// Per-site clearing for the in-app browser. The WebView API has no "delete this
// domain": cookies can only be dropped by expiring each name through
// CookieManager, the page's own storage is cleared from inside the page, and
// the HTTP cache is all-or-nothing (so this action leaves it alone).
//
// shortcut: only cookies whose name appears in the header the page receives are
// expired (an HttpOnly session may hide there — verify on a device), only the
// page's own ancestor Paths are covered, only the apex host and its direct name
// are tried (no parent domains), and the first MAX_COOKIE_PATHS ancestors win.
// Widen this if a real site keeps a session somewhere else.

private const val MAX_COOKIE_NAMES = 128
private const val MAX_COOKIE_PATHS = 8
private const val MAX_EXPIRY_HEADERS = 600

// Path part of an absolute http(s) URL, without query or fragment.
private val URL_PATH = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*([^?#]*)")

// "a=1; b=2" from CookieManager.getCookie(url) -> [a, b].
internal fun cookieNames(header: String?): List<String> =
    header.orEmpty()
        .split(';')
        .mapNotNull { pair -> pair.substringBefore('=').trim().takeIf { it.isNotEmpty() } }
        .distinct()
        .take(MAX_COOKIE_NAMES)

// Every Path a cookie of this page can carry: the root and each directory
// prefix. Expiring a cookie with the wrong Path creates a second cookie
// instead of deleting the first.
internal fun cookiePaths(url: String): List<String> {
    val path = URL_PATH.find(url)?.groupValues?.get(1).orEmpty()
    if (!path.startsWith('/')) return listOf("/")
    val paths = linkedSetOf("/")
    var slash = path.indexOf('/')
    while (slash >= 0 && paths.size < MAX_COOKIE_PATHS) {
        val next = path.indexOf('/', slash + 1)
        val prefix = if (next < 0) path else path.substring(0, next + 1)
        if (prefix.length > 1) paths += prefix
        slash = next
    }
    return paths.toList()
}

// Set-Cookie targets for expiring [names] on every Path of the page: each pair
// is (url to expire against, Set-Cookie value). A cookie is identified by name +
// domain + path, so a server cookie carrying Domain=.host is not the host-only
// cookie the same name would create. The page's host, its dotted form and every
// parent down to TLD+1 are all expired, on both schemes — the identity rules of
// WebViewCookieStore.clearCookies plus the parents a subdomain inherits.
internal fun expiredCookieHeaders(pageUrl: String, names: List<String>): List<Pair<String, String>> {
    val host = localStorageHostOf(pageUrl) ?: return emptyList()
    if (names.isEmpty()) return emptyList()
    val domains = cookieDomains(host)
    val out = ArrayList<Pair<String, String>>()
    for (scheme in listOf("https", "http")) {
        val target = "$scheme://$host/"
        for (path in cookiePaths(pageUrl)) {
            for (name in names) {
                for (domain in domains) {
                    if (out.size >= MAX_EXPIRY_HEADERS) return out
                    out += target to "$name=; Max-Age=0; Path=$path$domain"
                }
            }
        }
    }
    return out
}

// Domain attributes to try: host-only, then the host and each parent down to
// TLD+1 (a public suffix cannot hold a cookie, so a shorter parent is a no-op).
internal fun cookieDomains(host: String): List<String> {
    val domains = mutableListOf("", "; Domain=$host", "; Domain=.$host")
    var dot = host.indexOf('.')
    while (dot >= 0) {
        val parent = host.substring(dot + 1)
        if (!parent.contains('.')) break
        domains += "; Domain=$parent"
        domains += "; Domain=.$parent"
        dot = host.indexOf('.', dot + 1)
    }
    return domains
}

// Runs inside the page. Cookie sessions are dropped by expiring the cookies,
// client-side ones live in per-origin storage nothing else can reach.
// shortcut: IndexedDB and the Cache API of the page survive; add them to this
// script when a source starts keeping its session there.
internal val CLEAR_PAGE_STORAGE_JS: String = """
    (function () {
      try { window.localStorage.clear(); } catch (e) {}
      try { window.sessionStorage.clear(); } catch (e) {}
    })()
""".trimIndent()
