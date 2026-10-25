package com.watchreader.mobile

import com.watchreader.mobile.ui.screen.LineSpacing
import com.watchreader.mobile.ui.screen.PageMargin
import com.watchreader.mobile.ui.screen.ReadingPreset
import com.watchreader.mobile.ui.screen.TextSettings
import com.watchreader.mobile.ui.viewmodel.ReaderMove
import com.watchreader.mobile.ui.viewmodel.ReaderUiState
import com.watchreader.mobile.ui.viewmodel.ReturnPoint
import com.watchreader.shared.reader.Paginator
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

    @Test fun whileListeningOnlyAJumpTakesTheVoiceAlong() {
        // As on the watch, a page turned by hand is shown and the next sentence brings the
        // reader back; the contents, a bookmark, a search result and the way back move the voice.
        assertEquals(false, ReaderMove.TURN.movesVoice)
        assertEquals(true, ReaderMove.JUMP.movesVoice)
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

    private fun ready(start: Int, end: Int, total: Int) =
        ReaderUiState.Ready(Paginator.Page(start, end, emptyList(), ""), total, emptyList())

    @Test fun thePercentageIsWhereThePageBeginsAndTheWholeBookOnTheLastPage() {
        // The library shows a book's place the same way, so the two agree.
        assertEquals(0.25f, ready(2_500, 3_100, 10_000).fraction, 0f)
        assertEquals(0f, ready(0, 600, 10_000).fraction, 0f)
        assertEquals(1f, ready(9_600, 10_000, 10_000).fraction, 0f)
        assertEquals(0f, ready(0, 0, 0).fraction, 0f)
    }
}
