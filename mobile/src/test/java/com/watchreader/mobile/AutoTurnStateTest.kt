package com.watchreader.mobile

import androidx.compose.runtime.saveable.SaverScope
import com.watchreader.mobile.ui.screen.AutoTurnPrefs
import com.watchreader.mobile.ui.screen.AutoTurnState
import com.watchreader.mobile.ui.screen.AutoTurnTimer.Phase
import com.watchreader.mobile.ui.screen.AutoTurnTimer.Tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auto page turn as the reader screen drives it: started from its sheet, held, stopped, for one reading. */
class AutoTurnStateTest {
    private var now = 1_000_000L
    private val stored = HashMap<String, Any?>()

    private fun state() = AutoTurnState(AutoTurnPrefs(FakePreferences(stored))) { now }

    @Test fun aBookOpensWithThePagesStillWhateverAnOlderVersionSaved() {
        // An older version kept a switch and turned the pages again whenever a book was opened.
        stored["auto_turn_enabled"] = true
        stored["auto_turn_seconds"] = 15
        val auto = state()
        auto.pageShown(last = false)
        assertFalse(auto.on)
        assertEquals(Phase.OFF, auto.phase)
        assertNull(auto.deadline)
        // The pace is kept.
        assertEquals(15, auto.seconds)
    }

    @Test fun startInTheSheetTurnsThePagesTheMomentItCloses() {
        val auto = state()
        auto.pageShown(last = false)
        auto.openSheet()
        assertTrue(auto.sheetOpen)
        auto.start()
        // The open sheet holds the pages.
        assertEquals(Phase.PAUSED, auto.phase)
        assertNull(auto.deadline)
        now += 5_000
        auto.closeSheet()
        assertFalse(auto.sheetOpen)
        assertEquals(Phase.TURNING, auto.phase)
        assertEquals(now + 8_000, auto.deadline)
        now += 8_000
        assertEquals(Tick.TURN, auto.tick())
        // Still turning: the next page brings the next deadline.
        assertTrue(auto.on)
        auto.pageShown(last = false)
        assertEquals(now + 8_000, auto.deadline)
    }

    @Test fun theControlsPauseItAndLettingThemGoResumes() {
        val auto = state()
        auto.pageShown(last = false)
        auto.start()
        auto.hold("screen", true)
        assertTrue(auto.paused)
        assertTrue(auto.on)
        assertNull(auto.deadline)
        auto.hold("screen", false)
        assertEquals(Phase.TURNING, auto.phase)
        assertEquals(now + 8_000, auto.deadline)
    }

    @Test fun stopFromTheSheetOrThePanelTurnsItOff() {
        val auto = state()
        auto.pageShown(last = false)
        auto.start()
        auto.openSheet()
        auto.stop()
        auto.closeSheet()
        assertFalse(auto.on)
        assertNull(auto.deadline)

        auto.start()
        auto.hold("screen", true)
        auto.stop()
        auto.hold("screen", false)
        assertFalse(auto.on)
        assertNull(auto.deadline)
    }

    @Test fun theSpeedIsSavedAsItMovesAndTheNextReadingKeepsOnlyThat() {
        val auto = state()
        auto.pageShown(last = false)
        auto.start()
        auto.openSheet()
        auto.setSpeed(AutoTurnPrefs.SPEEDS - 1)
        assertEquals(2, auto.seconds)
        assertEquals(2, stored["auto_turn_seconds"])
        auto.closeSheet()
        assertEquals(now + 2_000, auto.deadline)

        val next = state()
        assertEquals(2, next.seconds)
        assertFalse(next.on)
    }

    @Test fun theLastPageEndsTheTurning() {
        val auto = state()
        auto.pageShown(last = true)
        auto.start()
        now += 8_000
        assertEquals(Tick.END, auto.tick())
        assertFalse(auto.on)
        assertNull(auto.deadline)
    }

    @Test fun aRotationKeepsTheTurningAndTheOpenSheet() {
        val saver = AutoTurnState.saver(AutoTurnPrefs(FakePreferences(stored))) { now }
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any) = true
        }
        val auto = state()
        auto.start()
        auto.openSheet()
        val saved = with(saver) { scope.save(auto) }!!
        val again = saver.restore(saved)!!
        assertTrue(again.on)
        assertTrue(again.sheetOpen)
        // The sheet still holds the pages after the rotation.
        assertTrue(again.paused)

        val still = saver.restore(with(saver) { scope.save(state()) }!!)!!
        assertFalse(still.on)
        assertFalse(still.sheetOpen)
    }
}
