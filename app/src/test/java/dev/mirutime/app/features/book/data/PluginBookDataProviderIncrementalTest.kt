package dev.mirutime.app.features.book.data

import dev.mirutime.core.api.BookMeta
import dev.mirutime.core.api.CatalogPage
import dev.mirutime.core.api.Chapter
import dev.mirutime.core.api.ChapterContent
import dev.mirutime.core.api.ChapterPage
import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.api.PluginSource
import dev.mirutime.core.api.getOrNull
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.ChapterSeed
import dev.mirutime.data.library.ChapterStatusRow
import dev.mirutime.data.library.ChapterTranslationQueueRow
import dev.mirutime.data.library.DownloadTaskEntity
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.TranslatedChapterRow
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Incremental chapter refresh (NoveLA parsePage port): a paged source must
// fetch only the stored page + new pages, append without deleting, and leave
// rows + chaptersLastPage untouched when any page fails.
class PluginBookDataProviderIncrementalTest {

    private val url = "https://site/book/1"
    private val sourceId = "fake-source"

    private fun book() = BookEntity(
        bookId = 7,
        installId = sourceId,
        url = url,
        title = "Book",
        author = "Author",
        coverUrl = null,
        contentType = "Novel",
        rating = 0.0,
        shelfId = "reading",
        totalChapters = 3,
        inLibrary = true,
        addedAt = 1L,
        updatedAt = 1L,
        lastReadAt = null,
        lastChapterId = null,
        lastChapterIndex = null,
        chapterListHash = null,
        lastUpdateAt = null,
        genresJson = "[]",
    )

    private fun chapter(index: Int) = ChapterEntity(
        chapterId = index.toLong(),
        bookId = 7,
        idx = index,
        url = "https://site/ch/$index",
        title = "Stored $index",
        updatedAt = 1L,
    )

    private fun ch(index: Int) = Chapter(
        title = "Chapter $index",
        url = "https://site/ch/$index",
    )

    private class FakeDao(
        private val book: BookEntity,
        private val rows: MutableList<ChapterEntity> = mutableListOf(),
    ) {
        var counter = rows.size
        var storedPage: Int? = null
        var appended: List<ChapterSeed> = emptyList()
        var appendCalls = 0
        var replaced: List<ChapterSeed>? = null
        var totalUpdated: Int? = null

        val proxy: LibraryDao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "book" -> book.takeIf { it.bookId == args?.getOrNull(0) }
                "bookByUrl" -> book.takeIf { it.url == args?.getOrNull(0) }
                "bookByInstallAndUrl" -> book.takeIf {
                    it.installId == args?.getOrNull(0) && it.url == args?.getOrNull(1)
                }
                "chapters" -> rows.toList()
                "chapterCount" -> counter
                "maxChapterIndex" -> rows.filter { it.removedAt == null }.maxOfOrNull { it.idx }
                "appendChaptersAndStamp" -> {
                    val seeds = (args?.getOrNull(1) as List<*>).filterIsInstance<ChapterSeed>()
                    val page = args?.getOrNull(2) as Int?
                    val at = args?.getOrNull(3) as Long
                    appendCalls++
                    appended = seeds
                    val fresh = seeds.filter { seed -> rows.none { it.url == seed.url } }
                    rows += fresh.map { seed ->
                        ChapterEntity(
                            chapterId = seed.index.toLong(),
                            bookId = book.bookId,
                            idx = seed.index,
                            url = seed.url,
                            title = seed.title,
                            updatedAt = at,
                        )
                    }
                    counter = rows.size
                    storedPage = page
                    fresh.size
                }
                "reconcileChapters" -> {
                    val seeds = (args?.getOrNull(1) as List<*>).filterIsInstance<ChapterSeed>()
                    replaced = seeds
                    rows.clear()
                    rows += seeds.map { seed ->
                        ChapterEntity(
                            chapterId = seed.index.toLong(),
                            bookId = book.bookId,
                            idx = seed.index,
                            url = seed.url,
                            title = seed.title,
                            updatedAt = 1L,
                        )
                    }
                    counter = rows.size
                    rows.toList()
                }
                "updateChaptersLastPage" -> {
                    storedPage = args?.getOrNull(1) as Int?
                    Unit
                }
                "updateTotalChapters" -> {
                    totalUpdated = args?.getOrNull(1) as Int
                    Unit
                }
                "chapterStatusRows" -> rows.map {
                    ChapterStatusRow(
                        chapterId = it.chapterId,
                        idx = it.idx,
                        url = it.url,
                        read = false,
                        downloaded = false,
                        hasBody = false,
                        hasPages = false,
                        sizeBytes = 0,
                    )
                }
                "translatedChapterRows" -> emptyList<TranslatedChapterRow>()
                "chapterTranslationQueueRows" -> emptyList<ChapterTranslationQueueRow>()
                "lastReadChapterRow" -> null
                "downloadTasksForBook" -> emptyList<DownloadTaskEntity>()
                "readChapterIndexes" -> emptyList<Int>()
                "durationForBook" -> 0L
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao
    }

    private class FakePageSource(
        private val pages: Map<Int, ChapterPage>,
        private val failPages: Set<Int> = emptySet(),
    ) : PluginSource {
        val pageCalls = mutableListOf<Int>()

        override val metadata = PluginMetadata(
            id = "fake-source",
            name = "Fake",
            baseUrl = "https://site/",
            contentType = ContentType.NOVEL,
            language = "en",
            api = 1,
            version = "1",
            sha256 = "sha",
        )

        override val supportsPages = true

        override suspend fun catalog(index: Int): Outcome<CatalogPage> = error("unused")

        override suspend fun search(index: Int, query: String): Outcome<CatalogPage> =
            error("unused")

        override suspend fun catalogFiltered(
            index: Int,
            filters: Map<String, List<String>>,
        ): Outcome<CatalogPage> = error("unused")

        override suspend fun book(url: String): Outcome<BookMeta> = error("unused")

        override suspend fun chapters(url: String): Outcome<List<Chapter>> =
            error("a paged source must not drain chapters()")

        override suspend fun chapterPage(url: String, page: Int): Outcome<ChapterPage> {
            pageCalls += page
            if (page in failPages) {
                return Outcome.Err(PluginError.ScriptError("page $page failed"))
            }
            val value = pages[page] ?: return Outcome.Err(PluginError.ScriptError("no page $page"))
            return Outcome.Ok(value)
        }

        override suspend fun chapterContent(html: String, url: String): Outcome<ChapterContent> =
            error("unused")

        override suspend fun filters(): Outcome<List<Map<String, String>>> = error("unused")

        override suspend fun settingsSchema(): Outcome<List<Map<String, String>>> =
            error("unused")
    }

    private class LegacySource(
        private val fetched: List<Chapter>,
    ) : PluginSource {
        var chapterCalls = 0

        override val metadata = PluginMetadata(
            id = "fake-source",
            name = "Fake",
            baseUrl = "https://site/",
            contentType = ContentType.NOVEL,
            language = "en",
            api = 1,
            version = "1",
            sha256 = "sha",
        )

        override suspend fun catalog(index: Int): Outcome<CatalogPage> = error("unused")

        override suspend fun search(index: Int, query: String): Outcome<CatalogPage> =
            error("unused")

        override suspend fun catalogFiltered(
            index: Int,
            filters: Map<String, List<String>>,
        ): Outcome<CatalogPage> = error("unused")

        override suspend fun book(url: String): Outcome<BookMeta> = error("unused")

        override suspend fun chapters(url: String): Outcome<List<Chapter>> {
            chapterCalls++
            return Outcome.Ok(fetched)
        }

        override suspend fun chapterContent(html: String, url: String): Outcome<ChapterContent> =
            error("unused")

        override suspend fun filters(): Outcome<List<Map<String, String>>> = error("unused")

        override suspend fun settingsSchema(): Outcome<List<Map<String, String>>> =
            error("unused")
    }

    @After
    fun tearDown() {
        PluginBookIndex.clear()
    }

    private fun provider(dao: FakeDao, source: PluginSource) = PluginBookDataProvider(
        library = LibraryRepository(dao.proxy),
        pluginSourceFor = { id -> source.takeIf { id == sourceId } },
    )

    private fun ok(result: Outcome<ChapterRefresh?>): ChapterRefresh =
        requireNotNull(result.getOrNull())

    @Test
    fun `incremental appends only new chapters with a continuing global index`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(book(), (1..3).map { chapter(it) }.toMutableList())
        val source = FakePageSource(
            mapOf(1 to ChapterPage(listOf(ch(1), ch(2), ch(3), ch(4)), totalPages = 1)),
        )

        val result = provider(dao, source).refreshChapterCountOutcome(url, lastPage = 1)

        val refresh = ok(result)
        assertEquals(4, refresh.count)
        assertEquals(1, refresh.lastPage)
        assertEquals(1, refresh.added)
        // Only the stored page is fetched: no full drain of the list.
        assertEquals(listOf(1), source.pageCalls)
        assertEquals(listOf(4), dao.appended.map { it.index })
        assertEquals(listOf("https://site/ch/4"), dao.appended.map { it.url })
        assertEquals(1, dao.storedPage)
    }

    @Test
    fun `incremental index continues after the highest stored index, not the count`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val rows = mutableListOf(chapter(1), chapter(2), chapter(3).copy(removedAt = 1L), chapter(4))
        val dao = FakeDao(book(), rows)
        val source = FakePageSource(
            mapOf(1 to ChapterPage(listOf(ch(1), ch(2), ch(4), ch(5)), totalPages = 1)),
        )

        provider(dao, source).refreshChapterCountOutcome(url, lastPage = 1)

        // 3 active rows but max idx 4: a new chapter must not reuse idx 4.
        assertEquals(listOf(5), dao.appended.map { it.index })
    }

    @Test
    fun `incremental skips blank urls without consuming an index`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(book(), (1..3).map { chapter(it) }.toMutableList())
        val source = FakePageSource(
            mapOf(
                1 to ChapterPage(
                    listOf(
                        Chapter(title = "Blank", url = ""),
                        ch(2),
                        Chapter(title = "Missing", url = "  "),
                        ch(4),
                    ),
                    totalPages = 1,
                ),
            ),
        )

        provider(dao, source).refreshChapterCountOutcome(url, lastPage = 1)

        assertEquals(listOf(4), dao.appended.map { it.index })
        assertEquals(listOf("https://site/ch/4"), dao.appended.map { it.url })
    }

    @Test
    fun `incremental appends a duplicate url across pages once`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(book(), (1..3).map { chapter(it) }.toMutableList())
        val source = FakePageSource(
            mapOf(
                1 to ChapterPage(listOf(ch(1), ch(2), ch(3), ch(4)), totalPages = 2),
                2 to ChapterPage(listOf(ch(4), ch(5)), totalPages = 2),
            ),
        )

        provider(dao, source).refreshChapterCountOutcome(url, lastPage = 1)

        assertEquals(listOf(4, 5), dao.appended.map { it.index })
        assertEquals(listOf("https://site/ch/4", "https://site/ch/5"), dao.appended.map { it.url })
    }

    @Test
    fun `incremental without new chapters advances the page and appends nothing`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(book(), (1..3).map { chapter(it) }.toMutableList())
        val source = FakePageSource(
            mapOf(1 to ChapterPage(listOf(ch(1), ch(2), ch(3)), totalPages = 1)),
        )

        val result = provider(dao, source).refreshChapterCountOutcome(url, lastPage = 1)

        val refresh = ok(result)
        assertEquals(3, refresh.count)
        assertEquals(0, refresh.added)
        assertEquals(0, dao.appendCalls)
        assertEquals(1, dao.storedPage)
    }

    @Test
    fun `page failure mid-way writes nothing and keeps the stored page`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(book(), (1..61).map { chapter(it) }.toMutableList())
        val source = FakePageSource(
            pages = mapOf(3 to ChapterPage(listOf(ch(62), ch(63)), totalPages = 4)),
            failPages = setOf(4),
        )

        val result = provider(dao, source).refreshChapterCountOutcome(url, lastPage = 3)

        assertTrue(result is Outcome.Err)
        assertEquals(listOf(3, 4), source.pageCalls)
        assertEquals(0, dao.appendCalls)
        assertNull(dao.storedPage)
        assertEquals(61, dao.proxy.chapters(7L).size)
    }

    @Test
    fun `a shrinking page counter falls back to a full reparse`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(book(), (1..61).map { chapter(it) }.toMutableList())
        val source = FakePageSource(
            mapOf(
                3 to ChapterPage(emptyList(), totalPages = 2),
                1 to ChapterPage((1..31).map { ch(it) }, totalPages = 2),
                2 to ChapterPage((32..61).map { ch(it) }, totalPages = 2),
            ),
        )

        val result = provider(dao, source).refreshChapterCountOutcome(url, lastPage = 3)

        val refresh = ok(result)
        assertEquals(61, refresh.count)
        assertEquals(2, refresh.lastPage)
        assertEquals(listOf(3, 1, 2), source.pageCalls)
        assertEquals(0, dao.appendCalls)
        assertTrue(dao.replaced != null)
        assertEquals(2, dao.storedPage)
    }

    @Test
    fun `a stored count below the page floor forces a full reparse`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(book(), (1..3).map { chapter(it) }.toMutableList())
        val source = FakePageSource(
            mapOf(1 to ChapterPage((1..4).map { ch(it) }, totalPages = 1)),
        )

        val result = provider(dao, source).refreshChapterCountOutcome(url, lastPage = 2)

        val refresh = ok(result)
        assertEquals(4, refresh.count)
        assertEquals(1, refresh.added)
        assertEquals(listOf(1), source.pageCalls)
        assertEquals(0, dao.appendCalls)
        assertTrue(dao.replaced != null)
        assertEquals(1, dao.storedPage)
    }

    @Test
    fun `a source without pages keeps the legacy chapters path`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(book(), (1..3).map { chapter(it) }.toMutableList())
        val source = LegacySource((1..4).map { ch(it) })

        val result = provider(dao, source).refreshChapterCountOutcome(url, lastPage = 1)

        val refresh = ok(result)
        assertEquals(4, refresh.count)
        assertEquals(1, refresh.added)
        assertNull(refresh.lastPage)
        assertEquals(1, source.chapterCalls)
        assertTrue(dao.replaced != null)
        assertEquals(4, dao.proxy.chapters(7L).size)
        assertNull(dao.storedPage)
    }

    @Test
    fun `manual refresh on a paged source appends through the stored page only`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(
            book().copy(chaptersLastPage = 1),
            (1..3).map { chapter(it) }.toMutableList(),
        )
        val source = FakePageSource(
            mapOf(1 to ChapterPage(listOf(ch(1), ch(2), ch(3), ch(4)), totalPages = 1)),
        )

        val result = provider(dao, source).refreshChaptersOutcome(url)

        val chapters = requireNotNull(result.getOrNull())
        assertEquals(listOf(1, 2, 3, 4), chapters.map { it.index })
        assertEquals(listOf(1), source.pageCalls)
        assertEquals(listOf(4), dao.appended.map { it.index })
        assertEquals(1, dao.storedPage)
    }

    @Test
    fun `long-press manual refresh reparses every page regardless of the counter`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(
            book().copy(chaptersLastPage = 2),
            (1..61).map { chapter(it) }.toMutableList(),
        )
        val source = FakePageSource(
            mapOf(
                1 to ChapterPage((1..31).map { ch(it) }, totalPages = 2),
                2 to ChapterPage((32..61).map { ch(it) }, totalPages = 2),
            ),
        )

        val result = provider(dao, source).refreshChaptersOutcome(url, forceFull = true)

        assertEquals(61, requireNotNull(result.getOrNull()).size)
        // The stored counter is ignored: both pages are re-read, not just page 2.
        assertEquals(listOf(1, 2), source.pageCalls)
        assertTrue(dao.replaced != null)
        assertEquals(2, dao.storedPage)
    }

    @Test
    fun `long-press manual refresh accepts a shorter list as the source truth`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(
            book().copy(chaptersLastPage = 2),
            (1..61).map { chapter(it) }.toMutableList(),
        )
        val source = FakePageSource(
            mapOf(
                1 to ChapterPage((1..31).map { ch(it) }, totalPages = 2),
                2 to ChapterPage((32..59).map { ch(it) }, totalPages = 2),
            ),
        )

        val result = provider(dao, source).refreshChaptersOutcome(url, forceFull = true)

        assertEquals(59, requireNotNull(result.getOrNull()).size)
        assertTrue(dao.replaced != null)
        assertEquals(2, dao.storedPage)
    }

    @Test
    fun `manual refresh on a non-paged source keeps the legacy drain`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(book(), (1..3).map { chapter(it) }.toMutableList())
        val source = LegacySource((1..4).map { ch(it) })

        val result = provider(dao, source).refreshChaptersOutcome(url)

        assertEquals(4, requireNotNull(result.getOrNull()).size)
        assertEquals(1, source.chapterCalls)
        assertNull(dao.storedPage)
    }
}
