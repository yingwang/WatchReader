package com.watchreader.mobile

import com.watchreader.mobile.tts.SleepTimer
import org.junit.Assert.*
import org.junit.Test

class SleepTimerTest {
    private class Alarm(val deadline: Long, val callback: () -> Unit) {
        var cancelled = false
        // Deliberately deliver even after cancel, like an already-dispatched main-thread callback.
        fun deliver() = callback()
    }

    private class Fixture {
        var elapsed = 123_000L
        var expirations = 0
        var failScheduling = false
        val alarms = mutableListOf<Alarm>()
        val changes = mutableListOf<SleepTimer.State?>()
        val timer = SleepTimer(
            elapsedRealtime = { elapsed },
            schedule = { deadline, callback ->
                if (failScheduling) throw SecurityException("Alarm rejected")
                val alarm = Alarm(deadline, callback).also(alarms::add)
                SleepTimer.Cancellation { alarm.cancelled = true }
            },
            onChanged = changes::add,
            onExpired = { expirations++ },
        )
    }

    @Test fun presetsUseElapsedDeadlinesAndExpireAtTheBoundary() {
        assertEquals(listOf(15, 30, 45, 60), SleepTimer.MINUTES)
        for (minutes in SleepTimer.MINUTES) {
            val f = Fixture()
            f.timer.start(minutes)
            assertEquals(123_000L + minutes * 60_000L, f.alarms.single().deadline)
            f.elapsed = f.alarms.single().deadline - 1
            assertFalse(f.timer.checkExpired())
            f.elapsed++
            f.alarms.single().deliver()
            assertEquals(1, f.expirations)
            assertNull(f.timer.state)
            assertNull(f.changes.last())
            assertTrue(f.alarms.single().cancelled)
        }
    }

    @Test fun countdownRoundsUpAndClampsAfterExpiry() {
        val state = SleepTimer.State(15, 900_000L)
        assertEquals(900L, state.remainingSeconds(0))
        assertEquals(900L, state.remainingSeconds(1))
        assertEquals(899L, state.remainingSeconds(1_000))
        assertEquals(1L, state.remainingSeconds(899_999))
        assertEquals(0L, state.remainingSeconds(900_000))
        assertEquals(0L, state.remainingSeconds(9_000_000))
    }

    @Test fun delayedDeliveryAfterDeviceSleepExpiresWithoutCountingTicks() {
        val f = Fixture()
        f.timer.start(30)
        f.elapsed += 40 * 60_000L
        f.alarms.single().deliver()
        assertEquals(1, f.expirations)
        assertNull(f.timer.state)
    }

    @Test fun repeatedDeliveryAndSpeechChecksExpireOnlyOnce() {
        val f = Fixture()
        f.timer.start(15)
        val alarm = f.alarms.single()
        f.elapsed = alarm.deadline
        assertTrue(f.timer.checkExpired())
        alarm.deliver()
        alarm.deliver()
        assertFalse(f.timer.checkExpired())
        assertEquals(1, f.expirations)
        assertEquals(2, f.changes.size)
    }

    @Test fun replacingDurationCancelsOldAlarmAndIgnoresItsQueuedCallback() {
        val f = Fixture()
        f.timer.start(15)
        val old = f.alarms.single()
        f.elapsed += 10 * 60_000L
        f.timer.start(60)
        val replacement = f.timer.state
        assertTrue(old.cancelled)
        f.elapsed = old.deadline
        old.deliver()
        assertEquals(replacement, f.timer.state)
        assertEquals(0, f.expirations)
        f.elapsed = f.alarms.last().deadline
        f.alarms.last().deliver()
        assertEquals(1, f.expirations)
    }

    @Test fun choosingTheSameDurationRestartsFromNow() {
        val f = Fixture()
        f.timer.start(45)
        val original = f.timer.state!!.deadlineMillis
        f.elapsed += 12_345L
        f.timer.start(45)
        assertEquals(original + 12_345L, f.timer.state!!.deadlineMillis)
        assertEquals(2, f.alarms.size)
        assertTrue(f.alarms.first().cancelled)
    }

    @Test fun choosingAShorterDurationUsesNewDeadline() {
        val f = Fixture()
        f.timer.start(60)
        f.elapsed += 5 * 60_000L
        f.timer.start(15)
        assertEquals(f.elapsed + 15 * 60_000L, f.timer.state!!.deadlineMillis)
        f.elapsed = f.timer.state!!.deadlineMillis
        f.alarms.last().deliver()
        assertEquals(1, f.expirations)
    }

    @Test fun cancellationClearsStateAndScheduledWorkWithoutPausing() {
        val f = Fixture()
        f.timer.start(15)
        val alarm = f.alarms.single()
        f.timer.cancel()
        f.timer.cancel()
        f.elapsed = alarm.deadline + 1
        alarm.deliver()
        assertNull(f.timer.state)
        assertTrue(alarm.cancelled)
        assertFalse(f.timer.checkExpired())
        assertEquals(0, f.expirations)
        assertEquals(2, f.changes.size)
    }

    @Test fun oldCallbackCannotCancelANewTimerAfterCancellation() {
        val f = Fixture()
        f.timer.start(15)
        val old = f.alarms.single()
        f.timer.cancel()
        f.timer.start(30)
        f.elapsed = old.deadline
        old.deliver()
        assertEquals(30, f.timer.state!!.minutes)
        assertEquals(0, f.expirations)
    }

    @Test fun earlyAlarmRearmsTheSameDeadline() {
        val f = Fixture()
        f.timer.start(15)
        val old = f.alarms.single()
        f.elapsed = old.deadline - 100
        old.deliver()
        assertEquals(0, f.expirations)
        assertEquals(2, f.alarms.size)
        assertEquals(old.deadline, f.alarms.last().deadline)
        assertTrue(old.cancelled)
        f.elapsed = old.deadline
        f.alarms.last().deliver()
        assertEquals(1, f.expirations)
    }

    @Test fun invalidDurationDoesNotChangeAnExistingTimer() {
        val f = Fixture()
        f.timer.start(15)
        val state = f.timer.state
        for (minutes in listOf(-1, 0, 1, 20, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { f.timer.start(minutes) }
            assertEquals(state, f.timer.state)
            assertFalse(f.alarms.single().cancelled)
        }
    }

    @Test fun rejectedSchedulingClearsPublishedStateAndOldAlarm() {
        val f = Fixture()
        f.timer.start(15)
        val old = f.alarms.single()
        f.failScheduling = true
        assertThrows(SecurityException::class.java) { f.timer.start(30) }
        assertNull(f.timer.state)
        assertNull(f.changes.last())
        assertTrue(old.cancelled)
        f.elapsed = old.deadline
        old.deliver()
        assertEquals(0, f.expirations)
    }

    @Test fun newServiceSessionStartsWithoutATimer() {
        val old = Fixture()
        old.timer.start(15)
        old.timer.cancel()
        val recreated = Fixture()
        assertNull(recreated.timer.state)
        assertTrue(recreated.alarms.isEmpty())
        assertFalse(recreated.timer.checkExpired())
    }

    @Test fun expiryClearsStateBeforePausingPlayback() {
        var elapsed = 0L
        var published: SleepTimer.State? = null
        var expirations = 0
        lateinit var timer: SleepTimer
        timer = SleepTimer(
            elapsedRealtime = { elapsed },
            schedule = { _, _ -> SleepTimer.Cancellation {} },
            onChanged = { published = it },
            onExpired = {
                assertNull(published)
                assertNull(timer.state)
                timer.cancel() // The service's pause path also cancels the timer.
                expirations++
            },
        )
        timer.start(15)
        elapsed = 900_000L
        assertTrue(timer.checkExpired())
        assertEquals(1, expirations)
    }
}
