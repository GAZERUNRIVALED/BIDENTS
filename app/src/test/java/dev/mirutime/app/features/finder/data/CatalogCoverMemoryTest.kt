package dev.mirutime.app.features.finder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The catalogue asks a source once per card per process: a cover and a "no
// cover" answer both have to survive the screen, a failed call must not.
class CatalogCoverMemoryTest {

    @Test
    fun `a cover and a miss are separate answers`() {
        CatalogCoverMemory.putCover("src-a", "https://x/1", "https://x/1.webp")
        CatalogCoverMemory.putMiss("src-a", "https://x/2")

        assertEquals("https://x/1.webp", CatalogCoverMemory.cover("src-a", "https://x/1"))
        assertFalse(CatalogCoverMemory.isMiss("src-a", "https://x/1"))
        assertNull(CatalogCoverMemory.cover("src-a", "https://x/2"))
        assertTrue(CatalogCoverMemory.isMiss("src-a", "https://x/2"))
    }

    @Test
    fun `answers are per source`() {
        CatalogCoverMemory.putMiss("src-b", "https://y/1")
        assertFalse(CatalogCoverMemory.isMiss("src-c", "https://y/1"))
    }

    @Test
    fun `a cover replaces an earlier miss`() {
        CatalogCoverMemory.putMiss("src-d", "https://z/1")
        CatalogCoverMemory.putCover("src-d", "https://z/1", "https://z/1.webp")

        assertEquals("https://z/1.webp", CatalogCoverMemory.cover("src-d", "https://z/1"))
        assertFalse(CatalogCoverMemory.isMiss("src-d", "https://z/1"))
    }

    @Test
    fun `a miss never drops a known cover`() {
        CatalogCoverMemory.putCover("src-f", "https://v/1", "https://v/1.webp")
        CatalogCoverMemory.putMiss("src-f", "https://v/1")

        assertEquals("https://v/1.webp", CatalogCoverMemory.cover("src-f", "https://v/1"))
        assertFalse(CatalogCoverMemory.isMiss("src-f", "https://v/1"))
    }

    @Test
    fun `forgetting a source clears its answers only`() {
        CatalogCoverMemory.putCover("src-g", "https://u/1", "https://u/1.webp")
        CatalogCoverMemory.putMiss("src-g", "https://u/2")
        CatalogCoverMemory.putCover("src-h", "https://u/1", "https://u/1.webp")

        CatalogCoverMemory.forgetSource("src-g")

        assertNull(CatalogCoverMemory.cover("src-g", "https://u/1"))
        assertFalse(CatalogCoverMemory.isMiss("src-g", "https://u/2"))
        assertEquals("https://u/1.webp", CatalogCoverMemory.cover("src-h", "https://u/1"))
    }

    @Test
    fun `the oldest cover is evicted past the cap`() {
        for (i in 0 until 2_001) {
            CatalogCoverMemory.putCover("src-e", "https://w/$i", "https://w/$i.webp")
        }
        assertNull("oldest entry must be gone", CatalogCoverMemory.cover("src-e", "https://w/0"))
        assertEquals(
            "https://w/2000.webp",
            CatalogCoverMemory.cover("src-e", "https://w/2000"),
        )
    }
}
