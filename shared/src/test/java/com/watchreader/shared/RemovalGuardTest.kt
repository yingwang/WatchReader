package com.watchreader.shared

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RemovalGuardTest {
    /** A library of one book, and the rows a save writes for it. */
    private class Library {
        val books = mutableSetOf("book")
        val rows = mutableListOf<String>()
        val guard = RemovalGuard { it in books }
    }

    @Test fun aSaveForABookStillThereIsWritten() = runBlocking {
        val library = Library()
        assertEquals(Unit, library.guard.ifKept("book") { library.rows += "listened" })
        assertEquals(listOf("listened"), library.rows)
    }

    @Test fun aSaveAfterTheDeleteWritesNothing() = runBlocking {
        val library = Library()
        library.guard.removing { library.books -= "book"; library.rows.clear() }
        assertNull(library.guard.ifKept("book") { library.rows += "listened" })
        assertEquals(emptyList<String>(), library.rows)
    }

    @Test fun aSaveThatArrivesDuringTheDeleteWaitsForItAndThenWritesNothing() = runBlocking {
        val library = Library()
        val halfway = CompletableDeferred<Unit>()
        val goOn = CompletableDeferred<Unit>()
        val deleting = launch {
            library.guard.removing {
                library.rows.clear()
                halfway.complete(Unit)
                // The delete is part-way through, waiting on the data layer.
                goOn.await()
                library.books -= "book"
            }
        }
        halfway.await()
        // The read-aloud service's last save comes in now.
        val saving = async(start = CoroutineStart.UNDISPATCHED) { library.guard.ifKept("book") { library.rows += "listened" } }
        yield()
        assertFalse("The save went ahead in the middle of the delete", saving.isCompleted)
        goOn.complete(Unit)
        deleting.join()
        assertNull(saving.await())
        assertEquals(emptyList<String>(), library.rows)
    }
}
