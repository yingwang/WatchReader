package com.watchreader.shared.reader

/**
 * Answers "how many characters of text[start, end) fit on one line [widthPx] wide?", following the
 * platform's own line breaking (words for Latin text, characters with punctuation rules for CJK).
 * Backed by a TextMeasurer in the app. Must return at least 1 when start < end.
 */
fun interface LineMeasurer {
    fun charsOnLine(text: String, start: Int, end: Int, widthPx: Int): Int
}

/**
 * Lays a book out one page at a time, line by line, into the slots of a [PageGeometry].
 *
 * Only the pages the reader looks at are ever measured: opening a novel at 90% costs one page,
 * the saved offset becomes the start of the current page, and the previous page is found by
 * searching for the start that ends exactly there.
 */
class Paginator(
    val text: String,
    val geometry: PageGeometry,
    private val measurer: LineMeasurer,
    /** A phone page has room to show the gap between paragraphs; a watch page does not. */
    private val paragraphGaps: Boolean = false,
) {

    /** One laid-out line: the characters [start, end) drawn in [slot]; [next] is where the following line begins. */
    class Line(val slot: Int, val start: Int, val end: Int, val next: Int)

    /** [chars] is text[start, end), so the screen can draw the lines without the whole book. */
    class Page(val start: Int, val end: Int, val lines: List<Line>, val chars: String) {
        fun text(line: Line): String = chars.substring(line.start - start, line.end - start)
    }

    val length: Int get() = text.length

    /** The page that begins at [start] (leading blank lines and spaces are skipped). */
    fun pageFrom(start: Int): Page {
        var pos = skipBlank(start)
        val lines = ArrayList<Line>(geometry.slots.size)
        for (slot in geometry.slots.indices) {
            if (paragraphGaps && lines.isNotEmpty() && pos < text.length && text[pos] == '\n') {
                while (pos < text.length && text[pos] == '\n') pos++
                lines.add(Line(slot, pos, pos, pos))
                continue
            }
            while (pos < text.length && text[pos] == '\n') pos++
            if (pos >= text.length) break
            val limit = minOf(text.length, pos + MAX_LINE_CHARS)
            val newline = text.indexOf('\n', pos).let { if (it < 0 || it > limit) limit else it }
            val n = measurer.charsOnLine(text, pos, newline, geometry.slots[slot].width).coerceIn(1, newline - pos)
            val end = pos + n
            val next = if (end == newline && newline < text.length && text[newline] == '\n') newline + 1 else end
            lines.add(Line(slot, pos, end, next))
            pos = next
        }
        val first = lines.firstOrNull()?.start ?: text.length
        val end = lines.lastOrNull()?.next ?: text.length
        return Page(first, end, lines, text.substring(first, end))
    }

    /** The page that ends exactly at [end]: the earliest start whose page reaches [end], cut there. */
    fun pageEndingAt(end: Int): Page {
        val e = end.coerceIn(0, text.length)
        if (e == 0) return pageFrom(0)
        var lo = maxOf(0, e - MAX_PAGE_CHARS)
        var hi = e
        // pageFrom(s).end grows with s; find the smallest s that reaches e
        if (pageFrom(lo).end < e) {
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (pageFrom(mid).end >= e) hi = mid else lo = mid
            }
        } else {
            hi = lo
        }
        val full = pageFrom(hi)
        val cut = full.lines.filter { it.start < e }.map { line ->
            if (line.next <= e) line else Line(line.slot, line.start, minOf(line.end, e), e)
        }
        val first = cut.firstOrNull()?.start ?: e
        return Page(first, e, cut, text.substring(first, e))
    }

    /** First position at or after [pos] that is not a space, tab or line break. */
    fun skipBlank(pos: Int): Int {
        var p = pos.coerceIn(0, text.length)
        while (p < text.length && text[p].let { it == '\n' || it == ' ' || it == '\t' || it == '\r' }) p++
        return p
    }

    /** Start of the paragraph containing [offset]; used when jumping to a percentage. */
    fun paragraphStart(offset: Int): Int {
        val o = offset.coerceIn(0, text.length)
        var i = o - 1
        val floor = maxOf(0, o - JUMP_LOOKBACK)
        while (i >= floor) {
            if (text[i] == '\n') return skipBlank(i + 1)
            i--
        }
        return if (floor == 0) 0 else skipBlank(o)
    }

    companion object {
        /** Bounds the backward search; far more than any watch page holds. */
        const val MAX_PAGE_CHARS = 1500
        private const val MAX_LINE_CHARS = 200
        private const val JUMP_LOOKBACK = 400
    }
}
