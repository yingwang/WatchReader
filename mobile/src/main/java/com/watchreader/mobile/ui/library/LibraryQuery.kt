package com.watchreader.mobile.ui.library

import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.ReadingMilestone
import com.watchreader.mobile.data.model.SyncStatus

enum class LibraryFilter { ALL, READING, FINISHED, ON_WATCH }

enum class LibrarySort { ADDED, RECENTLY_READ }

enum class LibraryLayout {
    COVERS, COMPACT;

    companion object {
        fun fromPreference(value: String?): LibraryLayout = entries.firstOrNull { it.name == value } ?: COVERS
    }
}

/** Library selection without UI or storage dependencies. Completion comes only from stats. */
data class LibraryQuery(
    val search: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: LibrarySort = LibrarySort.ADDED,
) {
    val isRestricted: Boolean get() = search.isNotBlank() || filter != LibraryFilter.ALL

    fun cleared(): LibraryQuery = copy(search = "", filter = LibraryFilter.ALL)

    fun select(books: List<Book>, milestones: Map<String, ReadingMilestone>): List<Book> {
        val titleQuery = search.trim()
        val order = when (sort) {
            LibrarySort.ADDED -> compareByDescending<Book> { it.addedEpochMs }.thenBy { it.id }
            LibrarySort.RECENTLY_READ -> compareByDescending<Book> { it.lastReadEpochMs }
                .thenByDescending { it.addedEpochMs }.thenBy { it.id }
        }
        return books.asSequence().filter { book ->
            book.title.contains(titleQuery, ignoreCase = true) && when (filter) {
                LibraryFilter.ALL -> true
                LibraryFilter.READING -> readingState(book, milestones[book.id]) == LibraryReadingState.READING
                LibraryFilter.FINISHED -> readingState(book, milestones[book.id]) == LibraryReadingState.FINISHED
                // A queued or in-flight transfer is not a receipt from the watch.
                LibraryFilter.ON_WATCH -> book.syncStatus == SyncStatus.SENT
            }
        }.sortedWith(order).toList()
    }

    companion object {
        fun readingState(book: Book, milestone: ReadingMilestone?): LibraryReadingState = when {
            (milestone?.finishedEpochMs ?: 0) > 0 -> LibraryReadingState.FINISHED
            (milestone?.firstOpenedEpochMs ?: 0) > 0 || book.lastReadEpochMs > 0 ||
                book.readOffsetChars > 0 || book.readProgress > 0f -> LibraryReadingState.READING
            else -> LibraryReadingState.NOT_STARTED
        }

        /** Pass the selected books so the continuation card also respects search and filters. */
        fun continuation(books: List<Book>, milestones: Map<String, ReadingMilestone>): Book? =
            books.filter {
                it.lastReadEpochMs > 0 && readingState(it, milestones[it.id]) == LibraryReadingState.READING
            }.maxByOrNull { it.lastReadEpochMs }
    }
}

enum class LibraryReadingState { NOT_STARTED, READING, FINISHED }
