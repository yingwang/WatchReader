package com.watchreader.wear.reader

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** One line's place on the page, in pixels. */
class LineSlot(val left: Float, val top: Float, val width: Int)

/**
 * Where lines may go on the screen. On a round watch the lines follow the circle: the block of
 * lines is centred, and each line is as wide as the chord of the circle at its own edges, so the
 * top and bottom lines are shorter and no corner is ever clipped. A square screen gets a plain
 * column with a margin.
 */
class PageGeometry(val slots: List<LineSlot>, val lineHeight: Float) {

    companion object {
        fun circle(diameterPx: Int, marginPx: Float, lineHeightPx: Float, minWidthPx: Float): PageGeometry {
            val r = diameterPx / 2f - marginPx
            val c = diameterPx / 2f
            val maxLines = floor(2 * r / lineHeightPx).toInt()
            // Fewer lines re-centre the block and widen the outer ones; take the most lines whose
            // outer lines are still wide enough to read.
            for (n in maxLines downTo 1) {
                val top0 = c - n * lineHeightPx / 2f
                val slots = (0 until n).map { i ->
                    val top = top0 + i * lineHeightPx
                    val bottom = top + lineHeightPx
                    val edge = max(abs(top - c), abs(bottom - c))
                    val half = sqrt(max(0f, r * r - edge * edge))
                    LineSlot(left = c - half, top = top, width = (2 * half).toInt())
                }
                if (slots.all { it.width >= minWidthPx }) return PageGeometry(slots, lineHeightPx)
            }
            return PageGeometry(emptyList(), lineHeightPx)
        }

        fun rect(widthPx: Int, heightPx: Int, marginPx: Float, lineHeightPx: Float): PageGeometry {
            val n = floor((heightPx - 2 * marginPx) / lineHeightPx).toInt().coerceAtLeast(1)
            val top0 = (heightPx - n * lineHeightPx) / 2f
            val width = (widthPx - 2 * marginPx).toInt()
            val slots = (0 until n).map { i -> LineSlot(marginPx, top0 + i * lineHeightPx, width) }
            return PageGeometry(slots, lineHeightPx)
        }
    }
}
