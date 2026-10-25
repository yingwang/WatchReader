package com.watchreader.shared

import com.watchreader.shared.reader.LineMeasurer
import com.watchreader.shared.reader.PageGeometry
import com.watchreader.shared.reader.Paginator
import com.watchreader.shared.search.TextExcerpt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paginator's handling of characters outside the Basic Multilingual Plane: emoji, and the rare
 * ideographs of CJK Extension B and on, each two UTF-16 units. The page layout itself is covered by
 * the watch's PaginatorTest.
 */
class PaginatorTest {
    /** Up to [widthPx] / 32 characters a line, a surrogate pair counting as one and never split. */
    private val codePointMeasurer = LineMeasurer { text, start, end, widthPx ->
        var i = start
        var fitted = 0
        while (i < end && fitted < maxOf(1, widthPx / 32)) {
            i += Character.charCount(text.codePointAt(i))
            fitted++
        }
        minOf(i, end) - start
    }

    private fun rect(lines: Int, charsPerLine: Int) =
        PageGeometry.rect(widthPx = charsPerLine * 32 + 20, heightPx = lines * 40 + 20, marginPx = 10f, lineHeightPx = 40f)

    /** "𠀀" (U+20000) between ordinary ideographs, so pairs fall at odd and even offsets alike. */
    private val text = (1..120).joinToString("") { if (it % 3 == 0) "𠀀" else "字" }

    private fun atBoundary(i: Int) = TextExcerpt.isBoundary(text, i)

    @Test
    fun aPageOpenedInsideAPairStartsOnTheWholeCharacter() {
        val p = Paginator(text, rect(lines = 3, charsPerLine = 5), codePointMeasurer)
        val inside = text.indexOf('\uDC00')
        assertTrue(!atBoundary(inside))
        val page = p.pageFrom(inside)
        assertEquals(inside - 1, page.start)
        assertTrue(page.lines.all { atBoundary(it.start) && atBoundary(it.end) })
    }

    @Test
    fun aPageOpenedByAJumpCanBeBookmarked() {
        // The phone's slider moves to a share of the book's length, which can be any offset; a
        // bookmark goes at the start of the page that opens there and needs a whole character.
        val p = Paginator(text, rect(lines = 3, charsPerLine = 5), codePointMeasurer)
        for (offset in text.indices) {
            val page = p.pageFrom(offset)
            assertTrue("page from $offset starts inside a pair", atBoundary(page.start))
            TextExcerpt.bookmark(text, page.start)
        }
    }

    @Test
    fun goingBackNeverStartsAPageInsideAPair() {
        // On a real screen the half of a pair is drawn as a narrow box rather than the wide
        // ideograph, so a page from inside a pair holds a character more than one from its start.
        val narrowHalves = LineMeasurer { t, start, end, widthPx ->
            var i = start
            var used = 0
            while (i < end) {
                val lone = t[i].isLowSurrogate()
                val w = if (lone) 16 else 32
                if (used + w > widthPx && i > start) break
                used += w
                i += if (lone) 1 else Character.charCount(t.codePointAt(i))
            }
            minOf(i, end) - start
        }
        // 150 px a line: four ideographs and a half fit where a whole fifth does not.
        val geometry = PageGeometry.rect(widthPx = 170, heightPx = 3 * 40 + 20, marginPx = 10f, lineHeightPx = 40f)
        val p = Paginator(text, geometry, narrowHalves)
        var e = text.length
        var pages = 0
        while (e > 0) {
            val page = p.pageEndingAt(e)
            assertEquals(e, page.end)
            assertTrue("page ending at $e starts inside a pair at ${page.start}", atBoundary(page.start))
            assertTrue(page.start < e)
            e = page.start
            pages++
        }
        assertTrue(pages > 5)
    }
}
