package com.watchreader.mobile

import com.watchreader.mobile.ui.screen.LineSpacing
import com.watchreader.mobile.ui.screen.PageMargin
import com.watchreader.mobile.ui.screen.ReadingPreset
import com.watchreader.mobile.ui.screen.TextSettings
import com.watchreader.mobile.ui.viewmodel.ReturnPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderNavigationTest {
    @Test fun aSecondLookUpStillLeadsBackToWhereTheReadingWas() {
        val point = ReturnPoint()
        point.jumpedFrom(5_000)
        point.jumpedFrom(120)
        assertEquals(5_000, point.take())
        assertNull(point.offset)
    }

    @Test fun turningPagesBackToThePlaceForgetsIt() {
        val point = ReturnPoint()
        point.jumpedFrom(5_000)
        point.reached(4_000, 4_900, 10_000)
        assertEquals(5_000, point.offset)
        point.reached(4_900, 5_800, 10_000)
        assertNull(point.offset)
    }

    @Test fun theEndOfTheBookIsReachedOnTheLastPage() {
        val point = ReturnPoint()
        point.jumpedFrom(10_000)
        point.reached(9_500, 10_000, 10_000)
        assertNull(point.offset)
    }

    @Test fun theOldDefaultIsTheComfortablePreset() {
        assertEquals(ReadingPreset.COMFORTABLE, ReadingPreset.matching(TextSettings(18, LineSpacing.NORMAL, PageMargin.NORMAL)))
        assertEquals(LineSpacing.NORMAL, LineSpacing.fromKey(null))
        assertEquals(PageMargin.NORMAL, PageMargin.fromKey("unknown"))
    }

    @Test fun aValueChangedByHandMatchesNoPreset() {
        assertNull(ReadingPreset.matching(TextSettings(18, LineSpacing.LOOSE, PageMargin.NORMAL)))
    }
}
