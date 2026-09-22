package com.watchreader.wear

import com.watchreader.shared.reader.LineMeasurer
import com.watchreader.shared.reader.PageGeometry
import com.watchreader.shared.reader.Paginator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginatorTest {
    /** Every character is 32px wide; a line takes as many as fit, never crossing [end]. */
    private val fakeMeasurer = LineMeasurer { _, start, end, widthPx -> minOf(end - start, widthPx / 32).coerceAtLeast(1) }

    private fun rect(lines: Int, charsPerLine: Int) =
        PageGeometry.rect(widthPx = charsPerLine * 32 + 20, heightPx = lines * 40 + 20, marginPx = 10f, lineHeightPx = 40f)

    @Test
    fun forwardPagesCoverTheWholeTextWithoutGapsOrOverlap() {
        val text = (1..40).joinToString("\n") { "第${it}段，句子一。句子二！句子三？" }
        val p = Paginator(text, rect(lines = 4, charsPerLine = 9), fakeMeasurer)
        var start = 0
        var pages = 0
        while (start < text.length) {
            val page = p.pageFrom(start)
            assertTrue("page must advance", page.end > start)
            assertTrue(page.lines.size <= 4)
            for (line in page.lines) {
                assertTrue(line.end - line.start <= 9)
                assertTrue(text.substring(line.start, line.end).none { it == '\n' })
            }
            start = page.end
            pages++
        }
        assertEquals(text.length, start)
        assertTrue(pages >= 20)
    }

    @Test
    fun backwardPageEndsExactlyWhereTheNextOneStarts() {
        val text = (1..30).joinToString("") { "句子$it。" }
        val p = Paginator(text, rect(lines = 3, charsPerLine = 10), fakeMeasurer)
        val end = text.length - 7
        val page = p.pageEndingAt(end)
        assertEquals(end, page.end)
        assertTrue(page.start in 0 until end)
        assertTrue(page.lines.all { it.end <= end })
        assertEquals(end, page.lines.last().next)
        // walking forward from that start reaches the same end, so pages stay consistent
        assertTrue(p.pageFrom(page.start).end >= end)
    }

    @Test
    fun paragraphGapsAreSkippedAndLinesBreakAtNewlines() {
        val text = "一段。\n\n\n二段较长一些。\n三段。"
        val p = Paginator(text, rect(lines = 5, charsPerLine = 20), fakeMeasurer)
        val page = p.pageFrom(0)
        assertEquals(listOf("一段。", "二段较长一些。", "三段。"), page.lines.map { text.substring(it.start, it.end) })
        assertEquals(text.length, page.end)
    }

    @Test
    fun jumpingLandsAtAParagraphStart() {
        val text = "第一段的内容。\n第二段的内容比较长一点。\n第三段。"
        val p = Paginator(text, rect(lines = 5, charsPerLine = 20), fakeMeasurer)
        assertEquals(8, p.paragraphStart(12))
        assertEquals(0, p.paragraphStart(3))
        assertEquals(21, p.nextParagraphStart(12))
        assertEquals(8, p.nextParagraphStart(3))
        // the last paragraph has none after it, so the offset stands
        assertEquals(22, p.nextParagraphStart(22))
    }

    @Test
    fun roundGeometryIsOneBlockThatFitsInsideTheCircle() {
        val g = PageGeometry.round(diameterPx = 384, marginPx = 18f, lineHeightPx = 44f)
        assertTrue(g.slots.size in 3..5)
        assertEquals(1, g.slots.map { it.left }.distinct().size)
        assertEquals(1, g.slots.map { it.width }.distinct().size)
        val c = 192f
        val r = c - 18f
        for (s in g.slots) {
            val edge = maxOf(kotlin.math.abs(s.top - c), kotlin.math.abs(s.top + 44f - c))
            val half = s.width / 2f
            assertTrue(edge * edge + half * half <= r * r + 1f)
        }
    }

    @Test
    fun defaultMarginsKeepTheShippedRoundLayout() {
        // What round() computed before the margin settings existed, spelled out.
        val d = 384; val m = 18f; val lh = 44f
        val r = d / 2f - m
        fun chord(h: Float) = 2 * kotlin.math.sqrt(maxOf(0f, r * r - h * h / 4))
        val n = (1..(2 * r / lh).toInt()).last { chord(it * lh) >= d * 0.72f }
        val g = PageGeometry.round(diameterPx = d, marginPx = m, lineHeightPx = lh)
        assertEquals(n, g.slots.size)
        assertEquals(chord(n * lh).toInt(), g.slots[0].width)
    }

    @Test
    fun smallerTopAndBottomMarginAddsLinesAndShortensThem() {
        val standard = PageGeometry.round(diameterPx = 456, marginPx = 18f, lineHeightPx = 44f)
        val narrow = PageGeometry.round(diameterPx = 456, marginPx = 18f, lineHeightPx = 44f, lineDelta = 2)
        val wide = PageGeometry.round(diameterPx = 456, marginPx = 18f, lineHeightPx = 44f, lineDelta = -1)
        assertEquals(standard.slots.size + 2, narrow.slots.size)
        assertEquals(standard.slots.size - 1, wide.slots.size)
        assertTrue(narrow.slots[0].width < standard.slots[0].width)
    }

    @Test
    fun sideMarginChangesTheWidthButNotTheLineCount() {
        val standard = PageGeometry.round(diameterPx = 456, marginPx = 18f, lineHeightPx = 44f)
        val wide = PageGeometry.round(diameterPx = 456, marginPx = 18f, lineHeightPx = 44f, sideMarginPx = 40f)
        val narrow = PageGeometry.round(diameterPx = 456, marginPx = 18f, lineHeightPx = 44f, sideMarginPx = 6f)
        assertEquals(standard.slots.size, wide.slots.size)
        assertEquals(standard.slots.size, narrow.slots.size)
        assertTrue(wide.slots[0].width < standard.slots[0].width)
        assertTrue(narrow.slots[0].width > standard.slots[0].width)
        // Still one block centred on the screen.
        assertEquals(456f, wide.slots[0].left * 2 + wide.slots[0].width, 2f)
    }

    @Test
    fun noMarginCombinationLeavesACrampedLine() {
        val g = PageGeometry.round(diameterPx = 456, marginPx = 18f, lineHeightPx = 30f, lineDelta = 8, sideMarginPx = 60f)
        assertTrue(g.slots[0].width >= 456 * 0.45f - 1)
    }

    @Test
    fun aWideMarginStillLeavesTwoLines() {
        val g = PageGeometry.round(diameterPx = 384, marginPx = 18f, lineHeightPx = 60f, lineDelta = -5)
        assertEquals(2, g.slots.size)
    }

    @Test
    fun rectTopMarginIsItsOwnSetting() {
        val g = PageGeometry.rect(widthPx = 400, heightPx = 400, marginPx = 10f, lineHeightPx = 40f, marginTopPx = 60f)
        assertEquals(7, g.slots.size)
        assertEquals(380, g.slots[0].width)
    }
}
