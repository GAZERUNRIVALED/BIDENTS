package dev.mirutime.app.features.book.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Progress math shared by the export notification and the book screen tiles. */
class BookExportProgressTest {

    @Test
    fun `percent is bounded and defined before the total is known`() {
        assertEquals(0, bookExportPercent(done = 0, total = 0))
        assertEquals(0, bookExportPercent(done = 5, total = 0))
        assertEquals(0, bookExportPercent(done = 0, total = 3))
        assertEquals(33, bookExportPercent(done = 1, total = 3))
        assertEquals(100, bookExportPercent(done = 3, total = 3))
        assertEquals(100, bookExportPercent(done = 4, total = 3))
    }
}
