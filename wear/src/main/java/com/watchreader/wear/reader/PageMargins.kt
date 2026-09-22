package com.watchreader.wear.reader

import com.watchreader.shared.reader.PageGeometry

/**
 * The page margins the reader can choose, as steps from narrowest to widest. The middle step is
 * the layout the app always had. On a round watch the block of text is a rectangle inside the
 * circle, so the two settings are not independent the way they are on paper: each top and bottom
 * step adds or removes a line (more lines make every line a little shorter), and the side step
 * decides how close the ends of the lines come to the bezel.
 */
object PageMargins {
    const val STEPS = 5
    const val DEFAULT = 2

    /**
     * Round, top and bottom: lines added to or taken from the page the app lays out by itself. In
     * lines rather than a measure, so that every step changes the page on every screen and size.
     */
    private val ROUND_LINE_DELTA = intArrayOf(2, 1, 0, -1, -2)

    /** Round, sides: how far the ends of the lines keep from the bezel, in dp. */
    private val ROUND_SIDE_DP = floatArrayOf(3f, 6f, ROUND_EDGE_DP, 14f, 20f)

    /** Square screens: plain margins in dp, top and bottom and sides alike. */
    private val RECT_DP = floatArrayOf(4f, 8f, RECT_EDGE_DP, 18f, 24f)

    private const val ROUND_EDGE_DP = 9f
    private const val RECT_EDGE_DP = 12f

    fun geometry(
        widthPx: Int,
        heightPx: Int,
        isRound: Boolean,
        lineHeightPx: Float,
        dpToPx: Float,
        topBottom: Int,
        sides: Int,
    ): PageGeometry {
        val v = topBottom.coerceIn(0, STEPS - 1)
        val h = sides.coerceIn(0, STEPS - 1)
        return if (isRound) {
            PageGeometry.round(
                diameterPx = minOf(widthPx, heightPx),
                marginPx = ROUND_EDGE_DP * dpToPx,
                lineHeightPx = lineHeightPx,
                lineDelta = ROUND_LINE_DELTA[v],
                sideMarginPx = ROUND_SIDE_DP[h] * dpToPx,
            )
        } else {
            PageGeometry.rect(
                widthPx = widthPx,
                heightPx = heightPx,
                marginPx = RECT_DP[h] * dpToPx,
                lineHeightPx = lineHeightPx,
                marginTopPx = RECT_DP[v] * dpToPx,
            )
        }
    }
}
