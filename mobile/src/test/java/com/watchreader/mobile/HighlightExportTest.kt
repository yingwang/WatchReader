package com.watchreader.mobile

import com.watchreader.mobile.highlight.Handoff
import com.watchreader.mobile.highlight.HandoffChoice
import com.watchreader.mobile.highlight.Highlight
import com.watchreader.mobile.highlight.HighlightChapters
import com.watchreader.mobile.highlight.HighlightColor
import com.watchreader.mobile.highlight.HighlightMarkdown
import com.watchreader.mobile.highlight.TextHandler
import com.watchreader.mobile.highlight.percentOf
import com.watchreader.shared.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighlightExportTest {
    private val chapters = listOf(Chapter("Chapter 1", 100), Chapter("Chapter 2", 5_000), Chapter("Chapter 3", 9_000))

    private fun h(id: String, start: Int, end: Int, quote: String, note: String = "", created: Long = 1) =
        Highlight(id, start, end, quote, HighlightColor.YELLOW, note, created, created)

    @Test fun aHighlightBelongsToTheLastChapterStartedBeforeIt() {
        assertNull(HighlightChapters.at(chapters, 0))
        assertNull(HighlightChapters.at(chapters, 99))
        assertEquals("Chapter 1", HighlightChapters.at(chapters, 100)?.title)
        assertEquals("Chapter 1", HighlightChapters.at(chapters, 4_999)?.title)
        assertEquals("Chapter 2", HighlightChapters.at(chapters, 5_000)?.title)
        assertEquals("Chapter 3", HighlightChapters.at(chapters, 1_000_000)?.title)
        assertNull(HighlightChapters.at(emptyList(), 10))
    }

    @Test fun theListIsInBookOrder() {
        val made = listOf(
            h("late", 9_100, 9_120, "late"),
            h("long", 200, 400, "long", created = 1),
            h("short", 200, 220, "short", created = 5),
            h("first", 150, 160, "first", created = 9),
            h("twin-b", 200, 220, "twin", created = 6),
        )
        assertEquals(listOf("first", "short", "twin-b", "long", "late"), made.sortedWith(Highlight.BOOK_ORDER).map { it.id })
    }

    @Test fun percentagesAreTruncatedAsTheReadersAre() {
        assertEquals(0, percentOf(0, 10_000))
        assertEquals(49, percentOf(4_999, 10_000))
        assertEquals(100, percentOf(10_000, 10_000))
        assertEquals(0, percentOf(5, 0))
    }

    @Test fun theExportIsMarkdownGroupedByChapter() {
        val highlights = listOf(
            h("c", 5_200, 5_230, "Second chapter\n\nover two paragraphs", note = "Worth rereading."),
            h("a", 20, 40, "  Before any chapter.  "),
            h("b", 1_000, 1_020, "In the first chapter."),
            h("b2", 2_000, 2_020, "Still the first.", note = "Line one\nline two"),
        )
        val expected = """
            # Pride and Prejudice

            *Jane Austen*

            > Before any chapter.

            *0%*

            ## Chapter 1

            > In the first chapter.

            *10%*

            > Still the first.

            Line one
            line two

            *20%*

            ## Chapter 2

            > Second chapter
            >
            > over two paragraphs

            Worth rereading.

            *52%*

        """.trimIndent()
        assertEquals(expected, HighlightMarkdown.build("Pride and Prejudice", "Jane Austen", highlights, chapters, 10_000))
    }

    @Test fun aBookWithoutAuthorOrChaptersExportsTitleAndQuotes() {
        val text = HighlightMarkdown.build("Notes\nfrom a file", null, listOf(h("a", 0, 5, "Hello")), emptyList(), 100)
        assertEquals("# Notes from a file\n\n> Hello\n\n*0%*\n", text)
    }

    @Test fun aBookWithNoHighlightsExportsItsTitle() {
        assertEquals("# Title\n\n*Someone*\n", HighlightMarkdown.build("Title", "Someone", emptyList(), chapters, 100))
    }

    private val translate = TextHandler("com.google.android.apps.translate", "Translate", "Translate")
    private val search = TextHandler("com.google.android.googlequicksearchbox", "Search", "Search")
    private val dictionary = TextHandler("livio.pack.lang.en_US", "Define", "English Dictionary")
    private val pleco = TextHandler("com.pleco.chinesesystem", "Lookup", "Pleco")
    private val deepl = TextHandler("com.deepl.mobiletranslator", "Process", "DeepL")

    @Test fun lookUpGoesToADictionaryWhenThereIsOne() {
        assertEquals(Handoff.Open(dictionary), HandoffChoice.lookUp(listOf(search, translate, dictionary)))
        assertEquals(Handoff.Choose(listOf(search, translate)), HandoffChoice.lookUp(listOf(search, dictionary, translate, pleco)))
        assertEquals(Handoff.Choose(emptyList()), HandoffChoice.lookUp(listOf(search, translate)))
        // A lone app that is no dictionary is offered, not opened unasked.
        assertEquals(Handoff.Choose(emptyList()), HandoffChoice.lookUp(listOf(search)))
        assertEquals(Handoff.WebSearch, HandoffChoice.lookUp(emptyList()))
    }

    @Test fun translateGoesToGoogleTranslateFirst() {
        assertEquals(Handoff.Open(translate), HandoffChoice.translate(listOf(search, deepl, translate)))
        assertEquals(Handoff.Open(deepl), HandoffChoice.translate(listOf(search, deepl, dictionary)))
        assertEquals(Handoff.Choose(emptyList()), HandoffChoice.translate(listOf(search, dictionary)))
        assertEquals(Handoff.WebSearch, HandoffChoice.translate(emptyList()))
    }
}
