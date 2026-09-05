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
}
