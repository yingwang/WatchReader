package com.watchreader.mobile

import com.watchreader.mobile.ui.screen.AutoTurnTimer
import com.watchreader.mobile.ui.screen.AutoTurnTimer.Phase
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
        timer.setSeconds(seconds)
        timer.start()
        timer.pageShown(last = false)
    }

    @Test fun offUntilStarted() {
        timer.pageShown(last = false)
        assertEquals(Phase.OFF, timer.phase)
        assertFalse(timer.running)
        assertNull(timer.remaining())
        assertNull(timer.progress())
        now += 60_000
        assertEquals(Tick.WAIT, timer.tick())
    }

    @Test fun startingCountsDownThePageOnScreenAtOnce() {
        timer.pageShown(last = false)
        timer.start()
        assertEquals(Phase.TURNING, timer.phase)
        assertEquals(8_000L, timer.remaining())
        now += 8_000
        assertEquals(Tick.TURN, timer.tick())
    }

    @Test fun startingAgainKeepsThePageTimeItHasLeft() {
        on(10)
        now += 4_000
        timer.start()
        assertEquals(6_000L, timer.remaining())
    }

    @Test fun eachPageGetsItsFullTimeThenTurns() {
        on(8)
        assertEquals(8_000L, timer.remaining())
        now += 7_999
        assertEquals(Tick.WAIT, timer.tick())
        assertEquals(1L, timer.remaining())
        now += 1
        assertEquals(Tick.TURN, timer.tick())
        // Nothing more turns until the next page is up, which starts its own time; turning goes on.
        assertNull(timer.remaining())
        assertEquals(Tick.WAIT, timer.tick())
        assertEquals(Phase.TURNING, timer.phase)
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
        assertEquals(Phase.PAUSED, timer.phase)
        assertFalse(timer.running)
        assertNull(timer.remaining())
        now += 60_000
        assertEquals(Tick.WAIT, timer.tick())
        timer.hold("controls", false)
        assertEquals(Phase.TURNING, timer.phase)
        assertEquals(8_000L, timer.remaining())
        now += 8_000
        assertEquals(Tick.TURN, timer.tick())
    }

    @Test fun everyHoldMustLetGoBeforePagesTurnAgain() {
        on(8)
        timer.hold("selection", true)
        timer.hold("controls", true)
        timer.hold("controls", false)
        assertEquals(Phase.PAUSED, timer.phase)
        now += 30_000
        assertEquals(Tick.WAIT, timer.tick())
        // Holding twice for the same reason is one hold.
        timer.hold("selection", true)
        timer.hold("selection", false)
        assertTrue(timer.running)
        now += 8_000
        assertEquals(Tick.TURN, timer.tick())
    }

    @Test fun startedWhileHeldItWaitsAndTurnsWhenLetGo() {
        // Start in the sheet: the sheet holds the pages until it closes.
        timer.pageShown(last = false)
        timer.hold("sheet", true)
        timer.start()
        assertEquals(Phase.PAUSED, timer.phase)
        assertNull(timer.remaining())
        now += 30_000
        timer.hold("sheet", false)
        assertEquals(Phase.TURNING, timer.phase)
        assertEquals(8_000L, timer.remaining())
    }

    @Test fun stoppingEndsItWhetherTurningOrPaused() {
        on(8)
        timer.stop()
        assertEquals(Phase.OFF, timer.phase)
        assertNull(timer.remaining())
        now += 60_000
        assertEquals(Tick.WAIT, timer.tick())

        on(8)
        timer.hold("controls", true)
        timer.stop()
        assertEquals(Phase.OFF, timer.phase)
        // Letting go afterwards does not start it again.
        timer.hold("controls", false)
        assertEquals(Phase.OFF, timer.phase)
        assertNull(timer.remaining())
    }

    @Test fun theLastPageKeepsItsTimeAndThenTheTurningStops() {
        on(5)
        timer.pageShown(last = true)
        now += 4_999
        assertEquals(Tick.WAIT, timer.tick())
        now += 1
        assertEquals(Tick.END, timer.tick())
        assertEquals(Phase.OFF, timer.phase)
        assertFalse(timer.running)
        timer.pageShown(last = false)
        assertNull(timer.remaining())
        // Started again, it turns as before.
        timer.start()
        assertEquals(5_000L, timer.remaining())
    }

    @Test fun progressRunsFromNothingToTheWholePage() {
        on(10)
        assertEquals(0f, timer.progress()!!, 0.0001f)
        now += 2_500
        assertEquals(0.25f, timer.progress()!!, 0.0001f)
        now += 7_500
        assertEquals(1f, timer.progress()!!, 0.0001f)
        now += 1_000
        assertEquals(1f, timer.progress()!!, 0.0001f)
        timer.hold("controls", true)
        assertNull(timer.progress())
    }

    @Test fun changingTheSecondsTimesThePageAfreshAndStopEndsIt() {
        on(8)
        now += 5_000
        timer.setSeconds(20)
        assertEquals(20_000L, timer.remaining())
        // The same seconds again change nothing; the page keeps the time it has left.
        now += 5_000
        timer.setSeconds(20)
        assertEquals(15_000L, timer.remaining())
        timer.stop()
        assertNull(timer.remaining())
        now += 60_000
        assertEquals(Tick.WAIT, timer.tick())
    }
}
