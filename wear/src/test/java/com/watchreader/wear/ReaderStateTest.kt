package com.watchreader.wear

import com.watchreader.shared.reader.Paginator
import com.watchreader.wear.settings.ReaderPrefs
import com.watchreader.wear.ui.viewmodel.ReaderUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStateTest {
    private fun ready(start: Int, end: Int, total: Int) =
        ReaderUiState.Ready("Book", Paginator.Page(start, end, emptyList(), ""), total, emptyList())

    @Test
    fun thePercentageIsWhereThePageBeginsAndTheWholeBookOnTheLastPage() {
        // The library shows a book's place the same way, so the two agree and a book read to its
        // end reaches 100%.
        assertEquals(0.25f, ready(2_500, 2_700, 10_000).fraction, 0f)
        assertFalse(ready(2_500, 2_700, 10_000).atEnd)
        val last = ready(9_900, 10_000, 10_000)
        assertTrue(last.atEnd)
        assertEquals(1f, last.fraction, 0f)
        assertEquals(0f, ready(0, 0, 0).fraction, 0f)
    }

    @Test
    fun autoTurnSpeedsSnapToTheNearestStepOfTheLadder() {
        val ladder = ReaderPrefs.AUTO_TURN_LADDER
        assertEquals(ReaderPrefs.MIN_AUTO_TURN_SECONDS, ladder.first(), 0f)
        assertEquals(ReaderPrefs.MAX_AUTO_TURN_SECONDS, ladder.last(), 0f)
        assertTrue(ladder.toList().zipWithNext().all { (a, b) -> a < b })
        assertTrue(ladder.any { it == ReaderPrefs.DEFAULT_AUTO_TURN_SECONDS })
        for ((i, seconds) in ladder.withIndex()) assertEquals(i, ReaderPrefs.autoTurnIndex(seconds))
        // A pace saved before the ladder, or between two steps, goes to the closer one.
        assertEquals(ladder.toList().indexOf(8f), ReaderPrefs.autoTurnIndex(7.2f))
        assertEquals(0, ReaderPrefs.autoTurnIndex(0.2f))
        assertEquals(ladder.lastIndex, ReaderPrefs.autoTurnIndex(90f))
    }
}
