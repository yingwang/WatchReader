package com.watchreader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookTocTest {
    private val prose = "It was a long day and the rain had not stopped since morning, which suited nobody in the house."

    /** Paragraphs the way a plain-text book stores them: one blank line between each. */
    private fun book(vararg paragraphs: String): String = TextNormalizer.normalize(paragraphs.joinToString("\n\n"))

    @Test
    fun namedHeadingsAreChapters() {
        val text = book("Chapter 1", prose, prose, "Chapter 2", prose, "第三章 雨", prose)
        assertEquals(listOf("Chapter 1", "Chapter 2", "第三章 雨"), BookToc.detect(text).map { it.title })
    }

    @Test
    fun bareHeadingsAmongProseAreChaptersAtTheirOffsets() {
        val text = book(
            "Start here", prose, prose, prose, prose,
            "Reading on the watch", prose, prose, prose, prose,
            "Settings", prose, prose, prose,
        )
        val toc = BookToc.detect(text)
        assertEquals(listOf("Start here", "Reading on the watch", "Settings"), toc.map { it.title })
        assertEquals(0, toc.first().start)
        assertEquals(text.indexOf("Settings"), toc.last().start)
    }

    @Test
    fun dialogueInStraightQuotesIsNotAContentsList() {
        val text = book(
            "The Road", prose,
            "\"Come in.\"", "\"Are you sure?\"", prose, "He nodded (once)", "She said nothing more that evening…", prose,
            "\"Come in.\"", "\"Are you sure?\"", prose, "He nodded (once)", "She said nothing more that evening…", prose,
        )
        assertTrue(BookToc.detect(text).isEmpty())
    }

    @Test
    fun linesEndingInQuotesBracketsOrEllipsesAreProse() {
        val text = book(
            "Start here", prose, prose, prose,
            "\"Bingley.\"", prose, prose,
            "他点了点头（一次）", prose, prose,
            "Reading aloud", prose, prose, prose,
        )
        assertEquals(listOf("Start here", "Reading aloud"), BookToc.detect(text).map { it.title })
    }

    @Test
    fun aHeadingEveryOtherParagraphIsNoise() {
        val text = book("One", prose, "Two", prose, "Three", prose, "Four", prose)
        assertTrue(BookToc.detect(text).isEmpty())
    }

    @Test
    fun aContentsPageIsNotTheChapters() {
        val text = book(
            "Contents",
            "Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4",
            "Chapter 1", prose, prose, prose,
            "Chapter 2", prose, prose, prose,
            "Chapter 3", prose, prose, prose,
            "Chapter 4", prose, prose, prose,
        )
        val toc = BookToc.detect(text)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4"), toc.map { it.title })
        // Each entry lands where the chapter is set, not on the line that merely names it.
        assertEquals(text.indexOf("Chapter 1", text.indexOf("Chapter 4")), toc.first().start)
        assertTrue(toc.all { text.startsWith(it.title, it.start) })
    }

    @Test
    fun contentsLinesCarryingPageNumbersStillMatchTheirChapters() {
        val text = book(
            "\u76ee\u5f55",
            "\u7b2c\u4e00\u7ae0 \u96e8 . . . . . 3", "\u7b2c\u4e8c\u7ae0 \u96ea ..... 27", "\u7b2c\u4e09\u7ae0 \u98ce _____ 51",
            "\u7b2c\u4e00\u7ae0 \u96e8", prose, prose, prose,
            "\u7b2c\u4e8c\u7ae0 \u96ea", prose, prose, prose,
            "\u7b2c\u4e09\u7ae0 \u98ce", prose, prose, prose,
        )
        val toc = BookToc.detect(text)
        assertEquals(listOf("\u7b2c\u4e00\u7ae0 \u96e8", "\u7b2c\u4e8c\u7ae0 \u96ea", "\u7b2c\u4e09\u7ae0 \u98ce"), toc.map { it.title })
        assertTrue(toc.all { text.startsWith(it.title, it.start) })
    }

    @Test
    fun shortChaptersTheBookNeverListsAreKept() {
        // Three headings close enough together to look like a list, but named nowhere else.
        val text = book("Chapter 1", prose, "Chapter 2", prose, "Chapter 3", prose, prose, prose)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), BookToc.detect(text).map { it.title })
    }

    @Test
    fun aBookStoredWithItsContentsPageComesRightWhenOpened() {
        val text = book(
            "Contents",
            "Chapter 1", "Chapter 2", "Chapter 3",
            "Chapter 1", prose, prose, prose,
            "Chapter 2", prose, prose, prose,
            "Chapter 3", prose, prose, prose,
        )
        // What an older import wrote: every heading, the listed ones first.
        val stored = BookToc.toJson(
            Regex("Chapter [123]").findAll(text).map { Chapter(it.value, it.range.first) }.toList()
        )
        val toc = BookToc.resolve(stored, text)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), toc.map { it.title })
        assertEquals(text.indexOf("Chapter 1", text.indexOf("Chapter 3")), toc.first().start)
    }

    @Test
    fun aStoredContentsThatNeverPlacedItsChaptersIsRescanned() {
        val text = book(
            "Chapter 1", prose, prose, prose,
            "Chapter 2", prose, prose, prose,
            "Chapter 3", prose, prose, prose,
            "Chapter 4", prose, prose, prose,
        )
        // What a single-document epub stored before its anchors were read: every chapter at the
        // offset the one document starts at, a few of them nudged apart by the document's title.
        val stored = BookToc.toJson(
            listOf(Chapter("One", 0), Chapter("Two", 1), Chapter("Three", 2), Chapter("Four", 3))
        )
        val toc = BookToc.resolve(stored, text)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4"), toc.map { it.title })
        assertEquals(text.indexOf("Chapter 4"), toc.last().start)
    }

    @Test
    fun entriesCollapsedOntoOneOffsetAreRescanned() {
        val text = book(
            "Chapter 1", prose, prose, prose,
            "Chapter 2", prose, prose, prose,
            "Chapter 3", prose, prose, prose,
        )
        val stored = BookToc.toJson(List(6) { Chapter("Part ${'$'}it", 0) })
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), BookToc.resolve(stored, text).map { it.title })
    }

    @Test
    fun aStoredContentsIsKeptWhenNothingCanBeScannedInstead() {
        // Prose with no headings at all: a broken contents still beats no contents.
        val text = book(prose, prose, prose, prose, prose, prose, prose, prose)
        val stored = BookToc.toJson(listOf(Chapter("One", 0), Chapter("Two", 1), Chapter("Three", 2)))
        assertEquals(listOf("One", "Two", "Three"), BookToc.resolve(stored, text).map { it.title })
    }

    @Test
    fun aContentsThatReachesTheEndOfTheBookIsTrusted() {
        val text = book(
            "Opening", prose, prose, prose, prose,
            "Middle", prose, prose, prose, prose,
            "Closing", prose, prose, prose, prose,
        )
        val stored = BookToc.toJson(listOf(
            Chapter("Opening", text.indexOf("Opening")),
            Chapter("Middle", text.indexOf("Middle")),
            Chapter("Closing", text.indexOf("Closing")),
        ))
        assertEquals(listOf("Opening", "Middle", "Closing"), BookToc.resolve(stored, text).map { it.title })
    }
}
