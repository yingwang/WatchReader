package com.watchreader.wear

import com.watchreader.wear.data.model.WearBook
import com.watchreader.wear.ui.isPlainLaunch
import com.watchreader.wear.ui.viewmodel.continueReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchTest {
    private fun book(id: String, lastRead: Long) =
        WearBook(id = id, title = id, filePath = "/books/$id.txt", sizeBytes = 1, addedEpochMs = 1, lastReadEpochMs = lastRead)

    @Test
    fun theBookToGoBackToIsTheOneReadLast() {
        val books = listOf(book("sample", 0), book("a", 300), book("b", 900), book("c", 500))
        assertEquals("b", continueReading(books)?.id)
    }

    @Test
    fun aLibraryNothingHasBeenReadInHasNoBookToGoBackTo() {
        // The guide laid down on first run has never been opened, so the app opens on the library.
        assertNull(continueReading(listOf(book("sample", 0), book("new", 0))))
        assertNull(continueReading(emptyList()))
    }

    @Test
    fun onlyAPlainStartFromTheLauncherGoesStraightIntoTheBook() {
        val launcher = setOf("android.intent.category.LAUNCHER")
        val main = "android.intent.action.MAIN"
        assertTrue(isPlainLaunch(restoring = false, action = main, categories = launcher, bookId = null))
        // Brought back from saved state: the screens it had are restored instead.
        assertFalse(isPlainLaunch(restoring = true, action = main, categories = launcher, bookId = null))
        // The read-aloud notification names its own book.
        assertFalse(isPlainLaunch(restoring = false, action = null, categories = null, bookId = "b"))
        assertFalse(isPlainLaunch(restoring = false, action = main, categories = launcher, bookId = "b"))
        // Started by name, not from the launcher.
        assertFalse(isPlainLaunch(restoring = false, action = null, categories = null, bookId = null))
        assertFalse(isPlainLaunch(restoring = false, action = main, categories = null, bookId = null))
    }
}
