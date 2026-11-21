package dev.mirutime.app.features.finder.ui

import dev.mirutime.app.features.finder.domain.CatalogBook
import org.junit.Assert.assertEquals
import org.junit.Test

// A page that already carries every cover must not trigger the backfill or its
// toast; a page with even one unresolved blank cover must.
class CatalogCoverBackfillTest {

    private fun book(url: String, cover: String?) = CatalogBook(
        bookUrl = url,
        title = url,
        rating = 0f,
        chapters = 0,
        genres = emptyList(),
        ongoing = false,
        coverUrl = cover,
    )

    @Test
    fun `page with every cover needs no backfill`() {
        val books = listOf(book("a", "https://x/a.webp"), book("b", "https://x/b.webp"))
        assertEquals(0, catalogBackfillPending(books) { false })
    }

    @Test
    fun `blank and missing covers are counted`() {
        val books = listOf(book("a", null), book("b", "  "), book("c", "https://x/c.webp"))
        assertEquals(2, catalogBackfillPending(books) { false })
    }

    @Test
    fun `already resolved urls are not fetched again`() {
        val books = listOf(book("a", null), book("b", null))
        assertEquals(1, catalogBackfillPending(books) { it == "a" })
    }

    @Test
    fun `empty page counts nothing`() {
        assertEquals(0, catalogBackfillPending(emptyList()) { false })
    }
}
