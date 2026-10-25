package com.watchreader.wear

import com.watchreader.wear.service.PartFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PartFilesTest {
    private val books: File = Files.createTempDirectory("books").toFile()

    @After fun cleanUp() {
        books.deleteRecursively()
    }

    private fun file(name: String, text: String = "x") = File(books, name).also { it.writeText(text) }

    @Test fun leftoversFromCutOffTransfersAreDeletedAndTheBooksAreNot() {
        val book = file("1b2c.txt", "A whole book")
        val contents = file("1b2c.txt.toc.json", "[]")
        val cutOff = file("1b2c.part", "A who")
        val another = file("9f8e.part")
        assertEquals(2, PartFiles.clearLeftovers(books))
        assertFalse(cutOff.exists())
        assertFalse(another.exists())
        assertTrue(book.exists())
        assertTrue(contents.exists())
        assertEquals("A whole book", book.readText())
    }

    @Test fun aBookIsReceivedIntoItsOwnPartFile() {
        assertEquals(File(books, "sample.part"), PartFiles.forBook(books, "sample"))
        file("sample.part")
        assertEquals(1, PartFiles.clearLeftovers(books))
        assertEquals(0, PartFiles.clearLeftovers(books))
    }

    @Test fun aMissingDirectoryHasNothingToClear() {
        assertEquals(0, PartFiles.clearLeftovers(File(books, "absent")))
    }
}
