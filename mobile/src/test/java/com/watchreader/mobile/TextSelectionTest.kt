package com.watchreader.mobile

import com.watchreader.mobile.highlight.LineSpan
import com.watchreader.mobile.highlight.PageSpans
import com.watchreader.mobile.highlight.SelectionDrag
import com.watchreader.mobile.highlight.TextSpan
import com.watchreader.mobile.highlight.WordSelection
import com.watchreader.shared.reader.LineMeasurer
import com.watchreader.shared.reader.PageGeometry
import com.watchreader.shared.reader.Paginator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSelectionTest {
    private fun word(text: String, at: Int, locale: Locale = Locale.ENGLISH): String? =
        WordSelection.wordAt(text, at, locale)?.let { text.substring(it.start, it.end) }

    @Test fun aPressSelectsTheWholeLatinWord() {
        val text = "It is a truth universally acknowledged, isn't it? Pi is 3.14 here."
        assertEquals("truth", word(text, text.indexOf("truth") + 2))
        assertEquals("truth", word(text, text.indexOf("truth")))
        assertEquals("acknowledged", word(text, text.indexOf("acknowledged") + 11))
        assertEquals("isn't", word(text, text.indexOf("isn't") + 4))
        assertEquals("3.14", word(text, text.indexOf("3.14") + 1))
    }

    @Test fun aHyphenatedWordIsSelectedWholeOrInItsParts() {
        // The JVM's word rules keep "well-known" together; Android's (ICU, after Unicode's rules)
        // break at the hyphen. Either is a word; neither takes in the words around it.
        val text = "a well-known saying"
        assertTrue(word(text, 3) in setOf("well", "well-known"))
        assertTrue(word(text, text.indexOf("known") + 2) in setOf("known", "well-known"))
    }

    @Test fun accentedWordsStayWhole() {
        val text = "Där fanns en gång en Ålänning, déjà vu."
        assertEquals("Ålänning", word(text, text.indexOf("Ålänning") + 3, Locale("sv")))
        assertEquals("déjà", word(text, text.indexOf("déjà") + 3, Locale.FRENCH))
    }

    @Test fun punctuationIsSelectedOnItsOwnAndASpaceSelectsNothing() {
        val text = "Yes, she said.\nThen"
        assertEquals(",", word(text, 3))
        assertNull(word(text, 4))
        assertNull(word(text, text.indexOf('\n')))
        assertNull(word(text, -1))
        assertNull(word(text, text.length))
    }

    @Test fun chineseSelectsAShortWordOrTheOneCharacter() {
        val text = "我们今天去公园散步，天气很好。"
        for (at in text.indices) {
            val span = WordSelection.wordAt(text, at, Locale.SIMPLIFIED_CHINESE) ?: error("Nothing selected at $at")
            assertTrue("A selection at $at holds the character pressed", at in span.start until span.end)
            assertTrue("A selection at $at is a word, not a sentence: ${text.substring(span.start, span.end)}", span.end - span.start <= 4)
        }
        assertEquals("，", word(text, text.indexOf('，'), Locale.SIMPLIFIED_CHINESE))
    }

    @Test fun japaneseSelectsAShortWordOrTheOneCharacter() {
        val text = "吾輩は猫である。名前はまだ無い。"
        val at = text.indexOf('猫')
        val span = WordSelection.wordAt(text, at, Locale.JAPANESE)!!
        assertTrue(at in span.start until span.end)
        assertTrue(span.end - span.start <= 4)
    }

    @Test fun aCharacterOutsideTheBasicPlaneIsNeverSplit() {
        // 𠀀 (U+20000) is two UTF-16 units; pressing the second of them selects the whole character.
        val text = "古字𠀀在此"
        val low = text.indexOf("𠀀") + 1
        val span = WordSelection.wordAt(text, low, Locale.CHINESE)!!
        assertTrue(span.start <= low - 1 && span.end >= low + 1)
        assertTrue(text.substring(span.start, span.end).contains("𠀀"))
        assertEquals(low - 1, WordSelection.snapDown(text, low))
    }

    @Test fun trimmingLeavesTheWordsWithoutSpacesAround() {
        val text = "  some words \n"
        assertEquals(TextSpan(2, 12), WordSelection.trim(text, TextSpan(0, text.length)))
        assertNull(WordSelection.trim(text, TextSpan(0, 2)))
    }

    @Test fun handlesStopOneCharacterApart() {
        val text = "0123456789"
        val pageStart = 100
        val selected = TextSpan(103, 106)
        assertEquals(TextSpan(101, 106), SelectionDrag.moveStart(text, pageStart, selected, 101))
        // Dragged past the other end, a handle stops beside it rather than swapping.
        assertEquals(TextSpan(105, 106), SelectionDrag.moveStart(text, pageStart, selected, 109))
        assertEquals(TextSpan(103, 104), SelectionDrag.moveEnd(text, pageStart, selected, 100))
        // And never leaves the page.
        assertEquals(TextSpan(100, 106), SelectionDrag.moveStart(text, pageStart, selected, 40))
        assertEquals(TextSpan(103, 110), SelectionDrag.moveEnd(text, pageStart, selected, 400))
    }

    @Test fun aHandleLetGoInsideAWordOfLettersTakesTheWholeWord() {
        val text = "old rag mats. At isn't"
        val pageStart = 50
        fun picked(span: TextSpan) = text.substring(span.start - pageStart, span.end - pageStart)
        val rag = TextSpan(pageStart + 4, pageStart + 7)
        // The end let go after the "m" of "mats" runs on to the end of the word, before the full stop.
        assertEquals("rag mats", picked(SelectionDrag.moveEnd(text, pageStart, rag, pageStart + 9)))
        // The start let go inside "old" goes back to its first letter.
        assertEquals("old rag", picked(SelectionDrag.moveStart(text, pageStart, rag, pageStart + 1)))
        // An end at a word's edge stays there, and an apostrophe between letters is part of the word.
        assertEquals("rag mats.", picked(SelectionDrag.moveEnd(text, pageStart, rag, pageStart + 13)))
        assertEquals("rag mats. At isn't", picked(SelectionDrag.moveEnd(text, pageStart, rag, pageStart + 19)))
    }

    @Test fun aHandleNeverCutsACharacterOutsideTheBasicPlaneInHalf() {
        // 𠀀 (U+20000) and 😀 are two UTF-16 units each; a selection of one of them alone, with the
        // other handle pulled onto it, keeps both units.
        val text = "古字𠀀在此😀。"
        val rare = text.indexOf("𠀀")
        val selected = TextSpan(rare, rare + 4)
        assertEquals(TextSpan(rare, rare + 2), SelectionDrag.moveEnd(text, 0, selected, 0))
        assertEquals(TextSpan(rare, rare + 2), SelectionDrag.moveEnd(text, 0, selected, rare + 1))
        val emoji = text.indexOf("😀")
        val pageStart = 30
        val tail = TextSpan(pageStart + rare, pageStart + emoji + 2)
        assertEquals(TextSpan(pageStart + emoji, pageStart + emoji + 2), SelectionDrag.moveStart(text, pageStart, tail, pageStart + text.length))
    }

    @Test fun chineseHandlesStillMoveOneCharacterAtATime() {
        val text = "春天的早晨，河面上起了一层薄雾。"
        val selected = TextSpan(2, 4)
        assertEquals(TextSpan(2, 7), SelectionDrag.moveEnd(text, 0, selected, 7))
        assertEquals(TextSpan(1, 4), SelectionDrag.moveStart(text, 0, selected, 1))
    }

    /** Ten characters to a line, or up to the paragraph's end; five lines to a page. */
    private fun paginator(text: String) = Paginator(
        text,
        PageGeometry.rect(widthPx = 100, heightPx = 50, marginPx = 0f, lineHeightPx = 10f),
        LineMeasurer { _, start, end, _ -> minOf(10, end - start) },
        paragraphGaps = true,
    )

    /** Paragraphs are set apart by a blank line, as the normalised text of a book has them. */
    private val book = "aaaaaaaaaabbbbbbbbbbcccccccccc\n\ndddddddddd\n\neeeeeeeeeeffffffffffgggggggggghhhhhhhhhh"

    @Test fun aRangeOnThePageIsCutAtTheLineEnds() {
        val page = paginator(book).pageFrom(0)
        // Lines: a [0,10), b [10,20), c [20,30), the blank line between paragraphs, d [32,42).
        assertEquals(listOf(0 to 10, 10 to 20, 20 to 30, 32 to 32, 32 to 42), page.lines.map { it.start to it.end })
        assertEquals(listOf(LineSpan(0, 5, 10), LineSpan(1, 0, 3)), PageSpans.onPage(page.lines, 5, 13))
        assertEquals(listOf(LineSpan(1, 2, 3)), PageSpans.onPage(page.lines, 12, 13))
    }

    @Test fun aRangeOverAParagraphBreakLeavesTheBreakOut() {
        val page = paginator(book).pageFrom(0)
        assertEquals(listOf(LineSpan(2, 5, 10), LineSpan(4, 0, 4)), PageSpans.onPage(page.lines, 25, 36))
    }

    @Test fun aRangeThatBeganOnAnEarlierPageShowsItsTail() {
        val p = paginator(book)
        val second = p.pageFrom(p.pageFrom(0).end)
        assertEquals(44, second.start)
        assertEquals(listOf(LineSpan(0, 0, 5)), PageSpans.onPage(second.lines, 20, 49))
    }

    @Test fun aRangeThatRunsOnToTheNextPageShowsItsHead() {
        val page = paginator(book).pageFrom(0)
        assertEquals(listOf(LineSpan(4, 6, 10)), PageSpans.onPage(page.lines, 38, 60))
    }

    @Test fun aRangeElsewhereInTheBookShowsNothing() {
        val page = paginator(book).pageFrom(0)
        assertTrue(PageSpans.onPage(page.lines, 50, 70).isEmpty())
        assertTrue(PageSpans.onPage(page.lines, 42, 44).isEmpty())
        assertTrue(PageSpans.onPage(page.lines, 7, 7).isEmpty())
    }

    @Test fun aRangeCoveringThePageCoversEveryLineOfText() {
        val page = paginator(book).pageFrom(0)
        assertEquals(
            listOf(LineSpan(0, 0, 10), LineSpan(1, 0, 10), LineSpan(2, 0, 10), LineSpan(4, 0, 10)),
            PageSpans.onPage(page.lines, 0, book.length),
        )
    }
}
