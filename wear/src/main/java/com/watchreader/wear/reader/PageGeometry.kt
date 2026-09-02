package com.watchreader.wear.reader

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** One line's place on the page, in pixels. */
class LineSlot(val left: Float, val top: Float, val width: Int)

/**
 * Where lines may go on the screen. Lines sit in one left-aligned block, centred on the screen.
 * On a round watch the block is the largest rectangle that fits inside the circle, which is a
 * square: widening it past that costs more lines than it gains characters.
 */
class PageGeometry(val slots: List<LineSlot>, val lineHeight: Float) {

    companion object {
        fun round(diameterPx: Int, marginPx: Float, lineHeightPx: Float): PageGeometry {
            val r = diameterPx / 2f - marginPx
            val maxLines = floor(2 * r / lineHeightPx).toInt().coerceAtLeast(1)
            // Taller blocks are narrower ones inside a circle; take the shape that fits the most text.
            val n = (1..maxLines).maxByOrNull { it * blockWidth(r, it * lineHeightPx) } ?: 1
            val width = blockWidth(r, n * lineHeightPx)
            val left = diameterPx / 2f - width / 2f
            val top0 = (diameterPx - n * lineHeightPx) / 2f
            val slots = (0 until n).map { i -> LineSlot(left, top0 + i * lineHeightPx, width.toInt()) }
            return PageGeometry(slots, lineHeightPx)
        }

        private fun blockWidth(r: Float, height: Float): Float = 2 * sqrt(max(0f, r * r - height * height / 4))

        fun rect(widthPx: Int, heightPx: Int, marginPx: Float, lineHeightPx: Float): PageGeometry {
            val n = floor((heightPx - 2 * marginPx) / lineHeightPx).toInt().coerceAtLeast(1)
            val top0 = (heightPx - n * lineHeightPx) / 2f
            val width = (widthPx - 2 * marginPx).toInt()
            val slots = (0 until n).map { i -> LineSlot(marginPx, top0 + i * lineHeightPx, width) }
            return PageGeometry(slots, lineHeightPx)
        }
    }
}
