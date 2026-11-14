package dev.mirutime.app.features.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryUpdateNotificationTextTest {
    private fun format(title: String, count: Int) = "$title +$count"
    private fun more(count: Int) = "and $count more"

    @Test
    fun `keeps five books and collapses the rest`() {
        val books = (1..7).map { "Book $it" to it }

        assertEquals(
            listOf(
                "Book 7 +7",
                "Book 6 +6",
                "Book 5 +5",
                "Book 4 +4",
                "Book 3 +3",
                "and 2 more",
            ),
            libraryUpdateLines(books, format = ::format, more = ::more),
        )
    }

    @Test
    fun `orders by count and breaks ties by title`() {
        val books = listOf("B" to 5, "A" to 5, "C" to 3, "D" to 5)

        assertEquals(
            listOf("A +5", "B +5", "D +5", "C +3"),
            libraryUpdateLines(books, format = ::format, more = ::more),
        )
    }

    @Test
    fun `drops blank titles and zero counts`() {
        val books = listOf("" to 3, "Book" to 0, "Book A" to 2)

        assertEquals(
            listOf("Book A +2"),
            libraryUpdateLines(books, format = ::format, more = ::more),
        )
    }

    @Test
    fun `empty list produces no lines`() {
        assertEquals(emptyList<String>(), libraryUpdateLines(emptyList(), format = ::format, more = ::more))
    }

    @Test
    fun `inbox lines come from the run summary and add the skipped count`() {
        assertEquals(
            listOf("Book 2 +2", "Book 1 +1", "skipped 3"),
            libraryUpdateInboxLines(
                books = listOf("Book 1" to 1, "Book 2" to 2),
                skipped = 3,
                format = ::format,
                more = ::more,
                skippedFormat = { "skipped $it" },
            ),
        )
    }

    @Test
    fun `no skipped line when nothing was skipped`() {
        assertEquals(
            listOf("Book 1 +1"),
            libraryUpdateInboxLines(
                books = listOf("Book 1" to 1),
                skipped = 0,
                format = ::format,
                more = ::more,
                skippedFormat = { "skipped $it" },
            ),
        )
    }

    @Test
    fun `skipped line keeps the inbox at five lines with many books`() {
        val books = (1..7).map { "Book $it" to it }

        val lines = libraryUpdateInboxLines(
            books = books,
            skipped = 2,
            format = ::format,
            more = ::more,
            skippedFormat = { "skipped $it" },
        )

        assertEquals(
            listOf("Book 7 +7", "Book 6 +6", "Book 5 +5", "and 4 more", "skipped 2"),
            lines,
        )
        assertTrue(lines.size <= 5)
    }

    @Test
    fun `skipped-only inbox keeps just the skipped line`() {
        assertEquals(
            listOf("skipped 4"),
            libraryUpdateInboxLines(
                books = emptyList(),
                skipped = 4,
                format = ::format,
                more = ::more,
                skippedFormat = { "skipped $it" },
            ),
        )
    }
}
