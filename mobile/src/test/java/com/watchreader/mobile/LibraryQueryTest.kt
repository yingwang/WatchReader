package com.watchreader.mobile

import com.watchreader.mobile.data.model.*
import com.watchreader.mobile.ui.library.*
import org.junit.Assert.*
import org.junit.Test

class LibraryQueryTest {
    private val fresh = Book("new", "New book", "new.txt", 100, 30)
    private val reading = fresh.copy(id = "reading", title = "Pride and Prejudice", addedEpochMs = 10, lastReadEpochMs = 60)
    private val finished = fresh.copy(id = "done", title = "Finished book", addedEpochMs = 20, readProgress = .91f, lastReadEpochMs = 80)
    private val books = listOf(fresh, reading, finished)
    private val milestones = mapOf("done" to ReadingMilestone("done", 20, 90))

    @Test fun completionUsesMilestoneEvenWhenLastPageStartsBelowOneHundredPercent() {
        assertEquals(listOf(finished), LibraryQuery(filter = LibraryFilter.FINISHED).select(books, milestones))
        assertEquals(listOf(reading), LibraryQuery(filter = LibraryFilter.READING).select(books, milestones))
        assertEquals(reading, LibraryQuery.continuation(books, milestones))
    }

    @Test fun onWatchRequiresReceiptAndCombinesWithCaseInsensitiveSearch() {
        val copies = SyncStatus.entries.map { reading.copy(id = it.name, syncStatus = it) }
        assertEquals(listOf("SENT"), LibraryQuery(" PRIDE ", LibraryFilter.ON_WATCH).select(copies, emptyMap()).map { it.id })
        assertTrue(LibraryQuery("unknown", LibraryFilter.ON_WATCH).select(copies, emptyMap()).isEmpty())
    }

    @Test fun recentAndAddedOrderDifferAndNoResultsCanBeCleared() {
        assertEquals(listOf("new", "done", "reading"), LibraryQuery().select(books, milestones).map { it.id })
        assertEquals(listOf("done", "reading", "new"), LibraryQuery(sort = LibrarySort.RECENTLY_READ).select(books, milestones).map { it.id })
        val restricted = LibraryQuery("missing", LibraryFilter.FINISHED, LibrarySort.RECENTLY_READ)
        assertTrue(restricted.select(books, milestones).isEmpty())
        assertFalse(restricted.cleared().isRestricted)
        assertEquals(3, restricted.cleared().select(books, milestones).size)
        assertEquals(LibrarySort.RECENTLY_READ, restricted.cleared().sort)
    }
}
