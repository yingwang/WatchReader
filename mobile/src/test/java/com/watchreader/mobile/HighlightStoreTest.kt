package com.watchreader.mobile

import com.watchreader.mobile.highlight.Highlight
import com.watchreader.mobile.highlight.HighlightCodec
import com.watchreader.mobile.highlight.HighlightColor
import com.watchreader.mobile.highlight.HighlightFileStorage
import com.watchreader.mobile.highlight.HighlightFiles
import com.watchreader.mobile.highlight.HighlightRepository
import com.watchreader.mobile.highlight.HighlightStorage
import com.watchreader.mobile.highlight.HighlightStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HighlightStoreTest {
    private val dir: File = Files.createTempDirectory("highlights").toFile()
    private val file = HighlightFiles.fileFor(dir, "book-1")
    private var clock = 1_000L
    private var ids = 0

    private fun store(storage: HighlightStorage = HighlightFileStorage(file, "book-1")) =
        HighlightStore(storage, now = { clock }, newId = { "h${++ids}" })

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test fun aHighlightIsOnDiskBeforeItShows() = runBlocking<Unit> {
        val s = store()
        s.load()
        val made = s.add(10, 20, "ten to twenty", HighlightColor.BLUE, note = "  why it matters  ")
        assertEquals(Highlight("h1", 10, 20, "ten to twenty", HighlightColor.BLUE, "why it matters", 1_000, 1_000), made)
        assertEquals(listOf(made), s.items.value)
        assertEquals(listOf(made), HighlightCodec.decode(file.readText()))
        // Another store on the same file, as after the app restarts, reads it back.
        val again = store()
        again.load()
        assertEquals(listOf(made), again.items.value)
    }

    @Test fun itemsAreKeptInBookOrder() = runBlocking<Unit> {
        val s = store()
        s.add(500, 510, "late", HighlightColor.YELLOW)
        clock = 2_000
        s.add(10, 40, "early and long", HighlightColor.YELLOW)
        s.add(10, 20, "early and short", HighlightColor.YELLOW)
        s.add(200, 210, "middle", HighlightColor.YELLOW)
        assertEquals(listOf("early and short", "early and long", "middle", "late"), s.items.value.map { it.quote })
        assertEquals(s.items.value, HighlightCodec.decode(file.readText()))
    }

    @Test fun markingTheSameWordsAgainChangesTheHighlightThere() = runBlocking<Unit> {
        val s = store()
        val first = s.add(10, 20, "q", HighlightColor.YELLOW, note = "kept")
        clock = 5_000
        val again = s.add(10, 20, "q", HighlightColor.PINK)
        assertEquals(1, s.items.value.size)
        assertEquals(first.id, again.id)
        assertEquals(HighlightColor.PINK, again.color)
        assertEquals("kept", again.note)
        assertEquals(1_000, again.createdAt)
        assertEquals(5_000, again.updatedAt)
    }

    @Test fun colourNoteDeleteAndUndo() = runBlocking<Unit> {
        val s = store()
        val made = s.add(10, 20, "q", HighlightColor.YELLOW)
        clock = 3_000
        assertEquals(HighlightColor.GREEN, s.recolor(made.id, HighlightColor.GREEN)?.color)
        assertEquals("A thought", s.annotate(made.id, " A thought ")?.note)
        assertEquals(3_000, s.items.value.single().updatedAt)
        assertEquals("", s.annotate(made.id, "   ")?.note)
        assertNull(s.recolor("missing", HighlightColor.BLUE))

        val gone = s.delete(made.id)
        assertTrue(s.items.value.isEmpty())
        assertTrue(HighlightCodec.decode(file.readText()).isEmpty())
        s.restore(gone!!)
        assertEquals(listOf(gone), s.items.value)
        assertEquals(listOf(gone), HighlightCodec.decode(file.readText()))
    }

    @Test fun aWriteThatFailsLeavesTheFileAndTheListAsTheyWere() = runBlocking<Unit> {
        val s = store()
        val kept = s.add(10, 20, "kept", HighlightColor.YELLOW)
        val before = file.readText()
        // The temporary file's name taken by a directory: the new contents cannot even be written.
        val pending = File(file.parentFile, file.name + ".pending").apply { mkdirs() }
        try {
            s.add(30, 40, "lost", HighlightColor.YELLOW)
            fail("The write should have failed")
        } catch (_: IOException) {
        }
        assertEquals(before, file.readText())
        assertEquals(listOf(kept), s.items.value)
        pending.delete()
    }

    @Test fun aWriteCutShortBeforeItsRenameIsNeverRead() = runBlocking<Unit> {
        val s = store()
        val kept = s.add(10, 20, "kept", HighlightColor.YELLOW)
        File(file.parentFile, file.name + ".pending").writeText("""{"version":1,"highlights":[{"id":"half""")
        val again = store()
        again.load()
        assertEquals(listOf(kept), again.items.value)
        // The next save writes over the leftover and renames it into place.
        again.add(30, 40, "next", HighlightColor.GREEN)
        assertEquals(2, HighlightCodec.decode(file.readText()).size)
        assertFalse(File(file.parentFile, file.name + ".pending").exists())
    }

    @Test fun anUnreadableFileIsKeptAndNothingIsSavedOverIt() = runBlocking<Unit> {
        file.parentFile!!.mkdirs()
        file.writeText("not json at all")
        val s = store()
        try {
            s.load()
            fail("A damaged file should not load")
        } catch (_: Exception) {
        }
        try {
            s.add(1, 2, "q", HighlightColor.YELLOW)
            fail("Nothing should be saved over a damaged file")
        } catch (_: Exception) {
        }
        assertEquals("not json at all", file.readText())
    }

    @Test fun forgettingABookDeletesItsFileAndRefusesLaterChanges() = runBlocking<Unit> {
        val s = store()
        s.add(1, 2, "q", HighlightColor.YELLOW)
        assertTrue(file.exists())
        s.forget()
        assertFalse(file.exists())
        assertTrue(s.items.value.isEmpty())
        try {
            s.add(3, 4, "q", HighlightColor.YELLOW)
            fail("A deleted book's store should refuse changes")
        } catch (_: IllegalStateException) {
        }
        assertFalse(file.exists())
    }

    @Test fun deletingABookFromTheLibraryDeletesItsHighlights() = runBlocking<Unit> {
        // A book with a store open in this process, and one whose file was only ever on disk.
        val open = HighlightRepository.store(dir, "open-book")
        open.add(1, 5, "words", HighlightColor.BLUE)
        val openFile = HighlightFiles.fileFor(dir, "open-book")
        val closedFile = HighlightFiles.fileFor(dir, "closed-book")
        HighlightFileStorage(closedFile, "closed-book").write(listOf(Highlight("x", 1, 2, "q", HighlightColor.YELLOW, "", 1, 1)))
        File(closedFile.parentFile, closedFile.name + ".pending").writeText("{")
        val otherFile = HighlightFiles.fileFor(dir, "other-book")
        HighlightFileStorage(otherFile, "other-book").write(emptyList())

        HighlightRepository.forget(dir, "open-book")
        HighlightRepository.forget(dir, "closed-book")

        assertFalse(openFile.exists())
        assertFalse(closedFile.exists())
        assertFalse(File(closedFile.parentFile, closedFile.name + ".pending").exists())
        assertTrue(otherFile.exists())
        // A book added again later under the same id starts with a clean slate.
        val fresh = HighlightRepository.store(dir, "open-book")
        assertNotEquals(open, fresh)
        fresh.load()
        assertTrue(fresh.items.value.isEmpty())
        HighlightRepository.forget(dir, "open-book")
    }

    @Test fun filesAreNamedByAHashOfTheBook() {
        val name = HighlightFiles.fileFor(dir, "../../etc/passwd").name
        assertTrue(name.matches(Regex("[0-9a-f]{64}\\.json")))
        assertNotEquals(name, HighlightFiles.fileFor(dir, "book-2").name)
    }
}
