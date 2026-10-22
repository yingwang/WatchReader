package com.watchreader.mobile.ui.screen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import com.watchreader.mobile.highlight.PageSpans
import com.watchreader.shared.reader.PageGeometry
import com.watchreader.shared.reader.Paginator

/**
 * The page in front of the reader as places on the screen: which character lies under a finger,
 * which gap between characters a handle is nearest, and where a stretch of the book is drawn.
 * Offsets in and out are the book's. A line is measured only when one of these asks about it, so
 * a page with nothing marked on it costs nothing beyond drawing it, and a long press measures one
 * line. [measure] must lay a line out exactly as the page draws it, or the marks drift off the words.
 */
class PageTextLayout(
    val page: Paginator.Page,
    private val geometry: PageGeometry,
    /** The type size in pixels, which sets how tall a mark is. */
    private val fontPx: Float,
    private val measure: (text: String, width: Int) -> TextLayoutResult,
) {
    private val measured = arrayOfNulls<TextLayoutResult>(page.lines.size)

    private fun layoutOf(index: Int): TextLayoutResult? {
        measured[index]?.let { return it }
        val line = page.lines[index]
        val slot = geometry.slots.getOrNull(line.slot) ?: return null
        return measure(page.text(line), slot.width).also { measured[index] = it }
    }

    private fun top(index: Int): Float? = geometry.slots.getOrNull(page.lines[index].slot)?.top

    private fun left(index: Int): Float? = geometry.slots.getOrNull(page.lines[index].slot)?.left

    /** The line drawn at [y], or null above, below or in a gap between paragraphs. */
    private fun lineAt(y: Float): Int? = page.lines.indices.firstOrNull { i ->
        val line = page.lines[i]
        val top = top(i) ?: return@firstOrNull false
        line.end > line.start && y >= top && y < top + geometry.lineHeight
    }

    /** The line with text nearest to [y], for a handle dragged into a gap or off the page. */
    private fun nearestLine(y: Float): Int? = page.lines.indices
        .filter { page.lines[it].end > page.lines[it].start && top(it) != null }
        .minByOrNull { i -> kotlin.math.abs(y - (top(i)!! + geometry.lineHeight / 2)) }

    /** The book offset of the character under [position], or null over blank page. */
    fun charAt(position: Offset): Int? {
        val index = lineAt(position.y) ?: return null
        val layout = layoutOf(index) ?: return null
        val x = position.x - (left(index) ?: return null)
        val length = page.lines[index].end - page.lines[index].start
        // The end of a paragraph leaves the rest of its last line blank.
        if (x < layout.getLineLeft(0) || x > layout.getLineRight(0)) return null
        val local = layout.getOffsetForPosition(Offset(x, layout.size.height / 2f)).coerceIn(0, length)
        // The nearest gap between characters has a character on either side; the one the finger
        // is over is the one whose box holds it.
        val under = listOf(local - 1, local).firstOrNull { it in 0 until length && layout.getBoundingBox(it).let { box -> x >= box.left && x <= box.right } }
            ?: local.coerceAtMost(length - 1)
        return page.lines[index].start + under
    }

    /** The book offset of the gap between characters nearest to [position], on the nearest line of text. */
    fun boundaryAt(position: Offset): Int? {
        val index = lineAt(position.y) ?: nearestLine(position.y) ?: return null
        val layout = layoutOf(index) ?: return null
        val line = page.lines[index]
        val x = position.x - (left(index) ?: return null)
        val local = layout.getOffsetForPosition(Offset(x, layout.size.height / 2f))
        return line.start + local.coerceIn(0, line.end - line.start)
    }

    /**
     * Where [start, end) is drawn on this page: one box per line it covers, around the letters
     * rather than the whole line's height, so marks on neighbouring lines stay apart.
     */
    fun rects(start: Int, end: Int): List<Rect> {
        if (end <= page.start || start >= page.end) return emptyList()
        return PageSpans.onPage(page.lines, start, end).mapNotNull { span ->
            val layout = layoutOf(span.line) ?: return@mapNotNull null
            val left = left(span.line) ?: return@mapNotNull null
            val top = top(span.line) ?: return@mapNotNull null
            val a = layout.getHorizontalPosition(span.from, usePrimaryDirection = true)
            val b = layout.getHorizontalPosition(span.to, usePrimaryDirection = true)
            // Centred a third of the type size above the baseline, where both Latin capitals and
            // Chinese characters have their middle, and kept within the line.
            val baseline = layout.firstBaseline
            val lineTop = layout.getLineTop(0)
            val lineBottom = layout.getLineBottom(0)
            val markTop = maxOf(lineTop, baseline - fontPx * MARK_ABOVE)
            val markBottom = minOf(lineBottom, baseline + fontPx * MARK_BELOW)
            Rect(left + minOf(a, b), top + markTop, left + maxOf(a, b), top + markBottom)
        }
    }

    private companion object {
        const val MARK_ABOVE = 1.02f
        const val MARK_BELOW = 0.34f
    }
}
