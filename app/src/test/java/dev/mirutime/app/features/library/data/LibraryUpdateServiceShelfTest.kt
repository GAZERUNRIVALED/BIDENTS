package dev.mirutime.app.features.library.data

import dev.mirutime.core.api.PluginError
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.ShelfEntity
import dev.mirutime.data.library.bookKeyUrl
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryUpdateServiceShelfTest {

    private fun book(id: Long, shelfId: String, total: Int = 1) = BookEntity(
        bookId = id,
        installId = "plugin",
        url = "https://site/book/$id",
        title = "Book $id",
        author = "Author",
        coverUrl = null,
        contentType = "Novel",
        rating = 0.0,
        shelfId = shelfId,
        totalChapters = total,
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

    private fun shelves() = listOf(
        ShelfEntity(id = "reading", label = "Reading", position = 0),
        ShelfEntity(
            id = "archive",
            label = "Archive",
            excludeFromUpdates = true,
            position = 1,
        ),
    )

    private fun libraryDao(
        books: List<BookEntity>,
        recorded: MutableList<Pair<Long, Int>> = mutableListOf(),
        stamped: MutableList<Long> = mutableListOf(),
    ): LibraryDao =
        Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "libraryBooks" -> books
                "shelves" -> shelves()
                "bookByUrl" -> books.firstOrNull { it.url == (args?.get(0) as? String) }
                "recordUpdateCheck" -> {
                    recorded += (args?.get(0) as Long) to (args?.get(1) as Int)
                    Unit
                }
                "stampLastUpdateCheck" -> {
                    stamped += args?.get(0) as Long
                    Unit
                }
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao

    private class StubProvider(
        private val result: Int? = 1,
        private val added: Int = result ?: 0,
        private val failure: Exception? = null,
        private val hash: String? = null,
    ) : LibraryBookRefreshPort {
        val refreshed = mutableListOf<String>()
        val hashCalls = mutableListOf<String>()

        override suspend fun refreshChapterCount(bookUrl: String): Int? = result

        override suspend fun chapterListHash(bookUrl: String): String? {
            hashCalls += bookUrl
            return hash
        }

        override suspend fun refreshChapterCountResult(
            bookUrl: String,
            lastPage: Int?,
        ): RefreshResult {
            refreshed += bookUrl
            failure?.let { throw it }
            return RefreshResult(count = result, added = added)
        }
    }

    @Test
    fun `paged book skips the chapter-list hash probe`() = runTest {
        val books = listOf(book(1, "reading").copy(chaptersLastPage = 2))
        val provider = StubProvider(result = 3, hash = "h")
        val service = LibraryUpdateService(
            provider = provider,
            library = LibraryRepository(libraryDao(books)),
            now = { 1_000L },
        )

        service.checkAll()

        // A known page means the source is paginated: probing the hash costs a
        // request and cannot short-circuit, so it must not happen.
        assertTrue(provider.hashCalls.isEmpty())
        assertEquals(1, provider.refreshed.size)
    }

    @Test
    fun `non-paged book short-circuits on a matching hash`() = runTest {
        val books = listOf(book(1, "reading").copy(chapterListHash = "h"))
        val provider = StubProvider(result = 3, hash = "h")
        val service = LibraryUpdateService(
            provider = provider,
            library = LibraryRepository(libraryDao(books)),
            now = { 1_000L },
        )

        val summary = service.checkAll()

        assertEquals(1, provider.hashCalls.size)
        assertTrue(provider.refreshed.isEmpty())
        assertEquals(1, summary.checked)
        assertEquals(0, summary.failed)
    }

    @Test
    fun `books on an excluded shelf are skipped by checkAll and checkBook`() = runTest {
        val books = listOf(book(1, "reading"), book(2, "archive"))
        val dao = libraryDao(books)
        val provider = StubProvider()
        val service = LibraryUpdateService(
            provider = provider,
            library = LibraryRepository(dao),
            now = { 1_000L },
        )

        val summary = service.checkAll()

        assertEquals(1, summary.checked)
        assertEquals(
            listOf("https://site/book/1"),
            provider.refreshed.map(::bookKeyUrl),
        )

        assertEquals(0, service.checkBook("https://site/book/2"))
        assertEquals(
            listOf("https://site/book/1"),
            provider.refreshed.map(::bookKeyUrl),
        )
    }

    @Test
    fun `new chapters are recorded and reported`() = runTest {
        val books = listOf(book(1, "reading"))
        val recorded = mutableListOf<Pair<Long, Int>>()
        val service = LibraryUpdateService(
            provider = StubProvider(result = 3),
            library = LibraryRepository(libraryDao(books, recorded)),
            now = { 1_000L },
        )

        val summary = service.checkAll()

        assertEquals(
            UpdateCheckSummary(
                checked = 1,
                updated = 1,
                failed = 0,
                newChapters = 3,
                lines = listOf("Book 1" to 3),
            ),
            summary,
        )
        assertEquals(listOf(1L to 3), recorded)
    }

    @Test
    fun `empty refresh result is a failure and is not recorded`() = runTest {
        val books = listOf(book(1, "reading"))
        val recorded = mutableListOf<Pair<Long, Int>>()
        val stamped = mutableListOf<Long>()
        val service = LibraryUpdateService(
            provider = StubProvider(result = null),
            library = LibraryRepository(libraryDao(books, recorded, stamped)),
            now = { 1_000L },
        )

        val summary = service.checkAll()

        assertEquals(UpdateCheckSummary(checked = 0, updated = 0, failed = 1), summary)
        assertTrue(recorded.isEmpty())
        assertEquals(listOf(1L), stamped)
    }

    @Test
    fun `throwing refresh is a failure and is not recorded`() = runTest {
        val books = listOf(book(1, "reading"))
        val recorded = mutableListOf<Pair<Long, Int>>()
        val stamped = mutableListOf<Long>()
        val service = LibraryUpdateService(
            provider = StubProvider(failure = IllegalStateException("boom")),
            library = LibraryRepository(libraryDao(books, recorded, stamped)),
            now = { 1_000L },
        )

        val summary = service.checkAll()

        assertEquals(UpdateCheckSummary(checked = 0, updated = 0, failed = 1), summary)
        assertTrue(recorded.isEmpty())
        assertEquals(listOf(1L), stamped)
    }

    @Test
    fun `overlapping runs are serialized`() = runTest {
        // A run reads the library snapshot and only then touches the provider;
        // two runs at once would read the same stale total. The gate keeps the
        // second run waiting until the first has written its result.
        val books = mutableMapOf(1L to book(1, "reading", total = 100))
        val totals = mutableListOf<Int>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var refreshes = 0
        val dao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "libraryBooks" -> books.values.toList()
                "shelves" -> shelves()
                "recordUpdateCheck" -> {
                    val id = args!![0] as Long
                    val total = args[1] as Int
                    totals += total
                    books[id] = books.getValue(id).copy(totalChapters = total)
                    Unit
                }
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao
        val provider = object : LibraryBookRefreshPort {
            override suspend fun refreshChapterCount(bookUrl: String): Int? = 110

            override suspend fun refreshChapterCountResult(
                bookUrl: String,
                lastPage: Int?,
            ): RefreshResult {
                refreshes++
                entered.complete(Unit)
                release.await()
                return RefreshResult(count = 110, added = 10)
            }
        }
        val service = LibraryUpdateService(
            provider = provider,
            library = LibraryRepository(dao),
            now = { 1_000L },
        )

        val first = async { service.checkAll() }
        entered.await()
        val second = async { service.checkAll() }
        // Let the second run reach the gate while the first still holds it.
        yield()
        release.complete(Unit)
        val firstSummary = first.await()
        second.await()

        assertEquals(10, firstSummary.newChapters)
        assertEquals(2, refreshes)
        assertEquals(listOf(110, 110), totals)
    }

    private val networkError = PluginError.Network(
        cause = "network",
        status = 503,
        reason = "Service Unavailable",
        host = "site.example",
        path = "/book/1",
    )

    private fun sourceProvider(
        calls: MutableList<String>,
        result: (Int) -> RefreshResult,
    ): LibraryBookRefreshPort = object : LibraryBookRefreshPort {
        override suspend fun refreshChapterCount(bookUrl: String): Int? = null

        override suspend fun refreshChapterCountResult(
            bookUrl: String,
            lastPage: Int?,
        ): RefreshResult {
            calls += bookUrl
            return result(calls.size)
        }
    }

    @Test
    fun `third consecutive failure cools the source down and skips the rest`() = runTest {
        val books = (1L..5L).map { book(it, "reading") }
        val calls = mutableListOf<String>()
        val provider = sourceProvider(calls) { call ->
            if (call <= 3) {
                RefreshResult(error = networkError)
            } else {
                RefreshResult(count = 1, added = 1)
            }
        }
        val service = LibraryUpdateService(
            provider = provider,
            library = LibraryRepository(libraryDao(books)),
            now = { 1_000L },
        )

        val summary = service.checkAll()

        assertEquals(3, summary.failed)
        assertEquals(2, summary.skipped)
        assertEquals(0, summary.checked)
        assertEquals(3, calls.size)
    }

    @Test
    fun `a success resets the consecutive failure count`() = runTest {
        val books = (1L..7L).map { book(it, "reading") }
        val calls = mutableListOf<String>()
        val provider = sourceProvider(calls) { call ->
            if (call == 3) RefreshResult(count = 1) else RefreshResult(error = networkError)
        }
        val service = LibraryUpdateService(
            provider = provider,
            library = LibraryRepository(libraryDao(books)),
            now = { 1_000L },
        )

        val summary = service.checkAll()

        // Calls 1-2 fail, 3 succeeds and resets, 4-6 fail again: the seventh
        // book is skipped, not failed.
        assertEquals(5, summary.failed)
        assertEquals(1, summary.checked)
        assertEquals(1, summary.skipped)
        assertEquals(6, calls.size)
    }

    @Test
    fun `per-source grouping respects maxConcurrency`() = runTest {
        val books = listOf(
            book(1, "reading").copy(installId = "a"),
            book(2, "reading").copy(installId = "a"),
            book(3, "reading").copy(installId = "b"),
            book(4, "reading").copy(installId = "b"),
        )
        var active = 0
        var maxActive = 0
        val calls = mutableListOf<String>()
        val provider = object : LibraryBookRefreshPort {
            override suspend fun refreshChapterCount(bookUrl: String): Int? = 1

            override suspend fun refreshChapterCountResult(
                bookUrl: String,
                lastPage: Int?,
            ): RefreshResult {
                calls += bookUrl
                active++
                maxActive = maxOf(maxActive, active)
                yield()
                active--
                return RefreshResult(count = 1)
            }
        }
        val service = LibraryUpdateService(
            provider = provider,
            library = LibraryRepository(libraryDao(books)),
            now = { 1_000L },
            maxConcurrency = 1,
        )

        val summary = service.checkAll()

        assertEquals(1, maxActive)
        assertEquals(4, calls.size)
        assertEquals(4, summary.checked)
    }
}
