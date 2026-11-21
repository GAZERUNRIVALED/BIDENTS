package dev.mirutime.app.features.finder.data

/**
 * Catalogue covers resolved during this process, keyed by source + book URL.
 *
 * The screen's own maps die with its composition, so returning from a book used
 * to re-ask every card, and a source that lists no covers (uukan) re-asked its
 * whole page on every visit. A resolved cover and a "the book has no cover"
 * answer both live here; a call that could not be made (cooldown, source error)
 * is not stored, so it may retry later.
 *
 * Touched only from the catalogue's main-dispatcher coroutines.
 */
object CatalogCoverMemory {
    // Browsing many sources must not grow this without a bound: the oldest
    // entry goes first, which is the insertion order of both maps.
    // FIFO, not LRU: a source with more coverless books than MaxEntries can
    // push its own oldest misses out and re-ask them. Raise the cap if a
    // catalogue that large ever shows up.
    // shortcut: FIFO eviction, upgrade to an access-ordered cache if a single
    // source exceeds MaxEntries coverless books.
    private const val MaxEntries = 2_000

    private val covers = LinkedHashMap<String, String>()
    private val misses = LinkedHashSet<String>()

    private fun key(sourceId: String, bookUrl: String) = "$sourceId\n$bookUrl"

    fun cover(sourceId: String, bookUrl: String): String? = covers[key(sourceId, bookUrl)]

    fun isMiss(sourceId: String, bookUrl: String): Boolean = key(sourceId, bookUrl) in misses

    fun putCover(sourceId: String, bookUrl: String, cover: String) {
        val key = key(sourceId, bookUrl)
        misses.remove(key)
        covers.remove(key)
        covers[key] = cover
        while (covers.size > MaxEntries) covers.remove(covers.keys.first())
    }

    fun putMiss(sourceId: String, bookUrl: String) {
        val key = key(sourceId, bookUrl)
        // A known cover is the better answer and is never downgraded.
        if (key in covers) return
        misses.add(key)
        while (misses.size > MaxEntries) misses.remove(misses.first())
    }

    // A reload of the source asks the site again, so its answers are gone too:
    // a stale miss would otherwise hide a cover for the rest of the process.
    fun forgetSource(sourceId: String) {
        val prefix = "$sourceId\n"
        covers.keys.removeAll { it.startsWith(prefix) }
        misses.removeAll { it.startsWith(prefix) }
    }
}
