package com.watchreader.mobile

import com.watchreader.mobile.ui.screen.AutoTurnTimer
import com.watchreader.mobile.ui.screen.AutoTurnTimer.Tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTurnTimerTest {
    private var now = 1_000_000L
    private val timer = AutoTurnTimer { now }

    private fun on(seconds: Int = 8) {
        timer.configure(enabled = true, seconds = seconds)
        timer.pageShown(last = false)
    }

    @Test fun offUntilSwitchedOn() {
        timer.pageShown(last = false)
        assertFalse(timer.running)
        assertNull(timer.remaining())
        now += 60_000
        assertEquals(Tick.WAIT, timer.tick())
    }

    @Test fun eachPageGetsItsFullTimeThenTurns() {
        on(8)
        assertEquals(8_000L, timer.remaining())
        now += 7_999
        assertEquals(Tick.WAIT, timer.tick())
        assertEquals(1L, timer.remaining())
        now += 1
        assertEquals(Tick.TURN, timer.tick())
        // Nothing more turns until the next page is up, which starts its own time.
        assertNull(timer.remaining())
        assertEquals(Tick.WAIT, timer.tick())
        now += 3_000
        timer.pageShown(last = false)
        assertEquals(8_000L, timer.remaining())
    }

    @Test fun aPageTurnedByHandStartsItsTimeAgain() {
        on(10)
        now += 9_000
        timer.pageShown(last = false)
        now += 9_000
        assertEquals(Tick.WAIT, timer.tick())
        now += 1_000
        assertEquals(Tick.TURN, timer.tick())
    }

    @Test fun aHoldPausesTheTurningAndLettingGoGivesThePageItsTimeAgain() {
        on(8)
        now += 6_000
        timer.hold("controls", true)
        assertFalse(timer.running)
        assertNull(timer.remaining())
        now += 60_000
        assertEquals(Tick.WAIT, timer.tick())
        timer.hold("controls", false)
        assertTrue(timer.running)
        assertEquals(8_000L, timer.remaining())
        now += 8_000
        assertEquals(Tick.TURN, timer.tick())
    }

    @Test fun everyHoldMustLetGoBeforePagesTurnAgain() {
        on(8)
        timer.hold("selection", true)
        timer.hold("controls", true)
        timer.hold("controls", false)
        assertFalse(timer.running)
        now += 30_000
        assertEquals(Tick.WAIT, timer.tick())
        // Holding twice for the same reason is one hold.
        timer.hold("selection", true)
        timer.hold("selection", false)
        assertTrue(timer.running)
        now += 8_000
        assertEquals(Tick.TURN, timer.tick())
    }

    @Test fun theLastPageKeepsItsTimeAndThenTheTurningStops() {
        on(5)
        timer.pageShown(last = true)
        now += 4_999
        assertEquals(Tick.WAIT, timer.tick())
        now += 1
        assertEquals(Tick.END, timer.tick())
        assertFalse(timer.enabled)
        assertFalse(timer.running)
        timer.pageShown(last = false)
        assertNull(timer.remaining())
    }

    @Test fun changingTheSecondsTimesThePageAfreshAndOffStopsIt() {
        on(8)
        now += 5_000
        timer.configure(enabled = true, seconds = 20)
        assertEquals(20_000L, timer.remaining())
        // The same settings again change nothing; the page keeps the time it has left.
        now += 5_000
        timer.configure(enabled = true, seconds = 20)
        assertEquals(15_000L, timer.remaining())
        timer.configure(enabled = false, seconds = 20)
        assertNull(timer.remaining())
        now += 60_000
        assertEquals(Tick.WAIT, timer.tick())
    }
}
