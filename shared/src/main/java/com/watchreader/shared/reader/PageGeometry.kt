package com.watchreader.shared.reader

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** One line's place on the page, in pixels. */
class LineSlot(val left: Float, val top: Float, val width: Int)

/**
 * Where lines may go on the screen. Lines sit in one left-aligned block, centred on the screen.
 * On a round watch the block is a rectangle inside the circle: every line it adds costs width, so
 * it stops adding them once the measure would get too short to read comfortably.
 */
class PageGeometry(val slots: List<LineSlot>, val lineHeight: Float) {

    companion object {
        /** How wide a line should stay, as a share of the screen, before another line is added. */
        private const val MIN_WIDTH_FRACTION = 0.72f

        /** No margin setting may leave a line shorter than this share of the screen. */
        private const val MIN_READABLE_FRACTION = 0.45f

        /**
         * [marginPx] keeps the block's corners off the bezel while lines are counted. [lineDelta]
         * adds lines to the block (or takes them away) from the count the app picks by itself,
         * which is what a smaller or larger top and bottom margin means on a circle: every added
         * line makes the block taller and so every line a little shorter. [sideMarginPx] is how
         * close the ends of the lines may come to the bezel; it sets the width only, never the
         * number of lines. The defaults are the layout the app shipped with.
         */
        fun round(
            diameterPx: Int,
            marginPx: Float,
            lineHeightPx: Float,
            lineDelta: Int = 0,
            sideMarginPx: Float = marginPx,
        ): PageGeometry {
            val r = diameterPx / 2f - marginPx
            val maxLines = floor(2 * r / lineHeightPx).toInt().coerceAtLeast(1)
            // Taller blocks are narrower ones inside a circle. Lines are worth having, but not at
            // the cost of a cramped measure, so the block grows until the line would get short.
            val roomy = diameterPx * MIN_WIDTH_FRACTION
            val natural = (1..maxLines).lastOrNull { blockWidth(r, it * lineHeightPx) >= roomy } ?: 1
            // A wider margin never takes the page below two lines unless it was already there.
            var n = (natural + lineDelta).coerceIn(minOf(2, natural), maxLines)
            val rSide = diameterPx / 2f - sideMarginPx
            // Small top and bottom margins with wide sides would leave a few words a line; give
            // back lines until the measure is readable again.
            while (n > 1 && blockWidth(rSide, n * lineHeightPx) < diameterPx * MIN_READABLE_FRACTION) n--
            val width = blockWidth(rSide, n * lineHeightPx)
            val left = diameterPx / 2f - width / 2f
            val top0 = (diameterPx - n * lineHeightPx) / 2f
            val slots = (0 until n).map { i -> LineSlot(left, top0 + i * lineHeightPx, width.toInt()) }
            return PageGeometry(slots, lineHeightPx)
        }

        private fun blockWidth(r: Float, height: Float): Float = 2 * sqrt(max(0f, r * r - height * height / 4))

        /** [marginPx] is the side margin; [marginTopPx] the top and bottom one, the same unless given. */
        fun rect(widthPx: Int, heightPx: Int, marginPx: Float, lineHeightPx: Float, marginTopPx: Float = marginPx): PageGeometry {
            val n = floor((heightPx - 2 * marginTopPx) / lineHeightPx).toInt().coerceAtLeast(1)
            val top0 = (heightPx - n * lineHeightPx) / 2f
            val width = (widthPx - 2 * marginPx).toInt()
            val slots = (0 until n).map { i -> LineSlot(marginPx, top0 + i * lineHeightPx, width) }
            return PageGeometry(slots, lineHeightPx)
        }
    }
}
