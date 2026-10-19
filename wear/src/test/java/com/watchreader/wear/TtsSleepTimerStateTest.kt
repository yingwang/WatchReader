package com.watchreader.wear

import com.watchreader.wear.tts.SleepTimer
import com.watchreader.wear.tts.TtsPlayback
import com.watchreader.wear.tts.TtsState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TtsSleepTimerStateTest {
    @Before fun prepare() = TtsPlayback.set(TtsState.IDLE, null, null)
    @After fun clear() = TtsPlayback.set(TtsState.IDLE, null, null)

    @Test fun servicePublishesAndClearsDeadline() {
        TtsPlayback.set(TtsState.PLAYING, "one", 0..5)
        val timer = SleepTimer.State(15, 900_000L)
        TtsPlayback.sleepTimer(timer)
        assertEquals(timer, TtsPlayback.sleepTimer.value)
        TtsPlayback.sleepTimer(null)
        assertNull(TtsPlayback.sleepTimer.value)
        assertEquals(TtsState.PLAYING, TtsPlayback.state.value)
    }

    @Test fun endingPlaybackClearsDeadline() {
        TtsPlayback.set(TtsState.PLAYING, "one", 0..5)
        TtsPlayback.sleepTimer(SleepTimer.State(30, 1_800_000L))
        TtsPlayback.set(TtsState.IDLE, null, null)
        assertNull(TtsPlayback.sleepTimer.value)
    }

    @Test fun changingBookDoesNotExposeOldDeadline() {
        TtsPlayback.set(TtsState.PLAYING, "one", 0..5)
        TtsPlayback.sleepTimer(SleepTimer.State(45, 2_700_000L))
        TtsPlayback.set(TtsState.LOADING, "two", null)
        assertNull(TtsPlayback.sleepTimer.value)
    }
}
