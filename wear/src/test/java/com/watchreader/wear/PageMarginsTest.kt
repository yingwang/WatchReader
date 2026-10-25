package com.watchreader.wear

import com.watchreader.shared.reader.PageGeometry
import com.watchreader.wear.reader.PageMargins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMarginsTest {
    private val density = 2f
    private val lineHeight = 22f * density * 1.4f

    private fun round(topBottom: Int, sides: Int) =
        PageMargins.geometry(456, 456, isRound = true, lineHeightPx = lineHeight, dpToPx = density, topBottom = topBottom, sides = sides)

    @Test
    fun theMiddleStepIsTheLayoutTheAppAlwaysHad() {
        val shipped = PageGeometry.round(diameterPx = 456, marginPx = 9f * density, lineHeightPx = lineHeight)
        val middle = round(PageMargins.DEFAULT, PageMargins.DEFAULT)
        assertEquals(shipped.slots.size, middle.slots.size)
        assertEquals(shipped.slots.map { it.width }, middle.slots.map { it.width })
        assertEquals(shipped.slots.map { it.top }, middle.slots.map { it.top })
    }

    @Test
    fun roundTopAndBottomStepsTakeLinesAwayFromNarrowestToWidest() {
        val counts = (0 until PageMargins.STEPS).map { round(it, PageMargins.DEFAULT).slots.size }
        assertEquals(counts.sortedDescending(), counts)
        assertTrue("every step should matter somewhere", counts.first() > counts.last())
    }

    @Test
    fun roundSideStepsNarrowTheLinesButKeepTheirCount() {
        val pages = (0 until PageMargins.STEPS).map { round(PageMargins.DEFAULT, it) }
        assertEquals(1, pages.map { it.slots.size }.distinct().size)
        val widths = pages.map { it.slots.first().width }
        assertEquals(widths.sortedDescending(), widths)
        assertTrue(widths.first() > widths.last())
    }

    @Test
    fun stepsOutOfRangeAreClampedRatherThanThrown() {
        assertEquals(round(0, 0).slots.map { it.width }, round(-3, -1).slots.map { it.width })
        val widest = PageMargins.STEPS - 1
        assertEquals(round(widest, widest).slots.size, round(99, 99).slots.size)
    }

    @Test
    fun squareScreensUseTheStepsAsPlainMargins() {
        val narrow = PageMargins.geometry(400, 400, isRound = false, lineHeightPx = 40f, dpToPx = 1f, topBottom = 0, sides = 0)
        val wide = PageMargins.geometry(400, 400, isRound = false, lineHeightPx = 40f, dpToPx = 1f, topBottom = 4, sides = 4)
        // 4 dp and 24 dp a side.
        assertEquals(392, narrow.slots.first().width)
        assertEquals(352, wide.slots.first().width)
        assertEquals(4f, narrow.slots.first().left)
        assertTrue(wide.slots.size < narrow.slots.size)
    }
}
