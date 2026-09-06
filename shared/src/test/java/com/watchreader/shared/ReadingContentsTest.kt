package com.watchreader.shared

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ReadingContentsTest {
    @Test fun contentsSurviveTransferWithoutChangingBookText() {
        val text = "Opening\nSome words\nA new beginning\nMore words"
        val chapters = listOf(Chapter("Opening", 0), Chapter("A new beginning", 19))
        val meta = BookMetadata("book", "Book", text.length.toLong(), 1, text.length, BookToc.toJson(chapters))
        val output = ByteArrayOutputStream()
        BookTransfer.writeHeader(output, meta)
        output.write(text.toByteArray())
        val input = ByteArrayInputStream(output.toByteArray())
        val received = BookTransfer.readHeader(input)
        assertEquals(meta, received)
        assertEquals(text, input.readBytes().toString(Charsets.UTF_8))
        assertEquals(chapters, BookToc.resolve(received.tocJson, text))
    }

    @Test fun olderHeadersRemainCompatible() {
        val meta = BookMetadata.fromJson("""{"id":"book","title":"Book","sizeBytes":10,"addedEpochMs":1}""")
        assertNull(meta.tocJson)
    }

    @Test fun oversizedContentsFallBackWithoutBreakingTransfer() {
        val meta = BookMetadata("book", "Book", 4, 1, tocJson = "x".repeat(70000))
        val output = ByteArrayOutputStream()
        BookTransfer.writeHeader(output, meta)
        output.write("text".toByteArray())
        val input = ByteArrayInputStream(output.toByteArray())
        assertNull(BookTransfer.readHeader(input).tocJson)
        assertEquals("text", input.readBytes().toString(Charsets.UTF_8))
    }

    @Test fun invalidAndDuplicateChapterOffsetsAreRemoved() {
        val json = BookToc.toJson(listOf(Chapter("B", 5), Chapter("A", 0), Chapter("Again", 0), Chapter("", 2), Chapter("Bad", -1), Chapter("End", 10)))
        assertEquals(listOf(Chapter("A", 0), Chapter("B", 5)), BookToc.resolve(json, "0123456789"))
    }

    @Test fun anEmptyContentsListMeansNoChaptersAndNoScan() {
        val text = "Chapter 1\nFirst paragraph.\n\nChapter 2\nSecond paragraph."
        assertEquals(2, BookToc.resolve(null, text).size)
        assertTrue(BookToc.resolve("[]", text).isEmpty())
        assertEquals(emptyList<Chapter>(), BookToc.parse("[]"))
        assertNull(BookToc.parse(null))
        assertNull(BookToc.parse("not json"))
    }

    @Test fun oldBooksAndMalformedContentsUseDetectedHeadings() {
        val text = "Chapter 1\nFirst paragraph.\n\nChapter 2\nSecond paragraph."
        assertEquals(2, BookToc.resolve(null, text).size)
        assertEquals(BookToc.detect(text), BookToc.resolve("not json", text))
        assertTrue(BookToc.resolve(null, "Just a sentence.").isEmpty())
    }
}
