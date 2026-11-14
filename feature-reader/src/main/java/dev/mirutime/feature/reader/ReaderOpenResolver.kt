package dev.mirutime.feature.reader

import dev.mirutime.app.features.reader.document.ReaderBook

sealed interface ReaderOpenTarget {
    data class Chapter(val chapterIndex: Int, val restoreAnchor: Boolean) : ReaderOpenTarget
    data class End(val chapterIndex: Int) : ReaderOpenTarget
    data object Empty : ReaderOpenTarget
}

object ReaderOpenResolver {
    fun forContinue(book: ReaderBook, saved: SavedReading?): ReaderOpenTarget {
        if (book.chapters.isEmpty()) return ReaderOpenTarget.Empty
        val savedChapter = saved?.anchor?.chapterUrl?.let { url ->
            book.chapters.firstOrNull { it.chapterUrl == url }
        }
        // Continue always reopens the saved chapter, even when it is already
        // marked read: the label and the tap both use the saved position.
        if (savedChapter != null) {
            return ReaderOpenTarget.Chapter(savedChapter.index, restoreAnchor = true)
        }
        val firstUnread = book.chapters.firstOrNull { !it.isRead }
        if (firstUnread != null) {
            return ReaderOpenTarget.Chapter(firstUnread.index, restoreAnchor = false)
        }
        return ReaderOpenTarget.End(book.chapters.last().index)
    }

    fun forRequested(
        book: ReaderBook,
        chapterIndex: Int,
        restoreSaved: Boolean,
        saved: SavedReading?,
    ): ReaderOpenTarget {
        if (book.chapters.isEmpty()) return ReaderOpenTarget.Empty
        val chapter = book.chapters.firstOrNull { it.index == chapterIndex }
            ?: return forContinue(book, null)
        val restore = restoreSaved && saved?.anchor?.chapterUrl == chapter.chapterUrl
        return ReaderOpenTarget.Chapter(chapter.index, restoreAnchor = restore)
    }

    fun continueChapter(book: ReaderBook, saved: SavedReading?): Int =
        when (val target = forContinue(book, saved)) {
            is ReaderOpenTarget.Chapter -> target.chapterIndex
            is ReaderOpenTarget.End -> target.chapterIndex
            ReaderOpenTarget.Empty -> 1
        }

    /**
     * Process-death re-entry. The restored route still names the chapter the
     * reader was first opened on, but the reader may have moved since, so the
     * saved anchor wins — even when its chapter is already marked read. A
     * missing chapter-list entry falls back to the requested chapter, never to
     * chapter 1 (FR-117/FR-118).
     */
    fun forReopened(
        book: ReaderBook,
        requestedIndex: Int,
        saved: SavedReading?,
    ): ReaderOpenTarget {
        if (book.chapters.isEmpty()) return ReaderOpenTarget.Empty
        val savedChapter = saved?.anchor?.chapterUrl?.let { url ->
            book.chapters.firstOrNull { it.chapterUrl == url }
        }
        return if (savedChapter != null) {
            ReaderOpenTarget.Chapter(savedChapter.index, restoreAnchor = true)
        } else {
            val requested = book.chapters.firstOrNull { it.index == requestedIndex }
            if (requested != null) {
                ReaderOpenTarget.Chapter(requested.index, restoreAnchor = false)
            } else {
                forContinue(book, null)
            }
        }
    }
}
