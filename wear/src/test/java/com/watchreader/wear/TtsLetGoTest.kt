package com.watchreader.wear

import com.watchreader.wear.tts.TtsPlayback
import com.watchreader.wear.tts.TtsState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A jump made while a book is read aloud lets the voice go of the page until reading starts again. */
class TtsLetGoTest {
    @Before fun prepare() {
        TtsPlayback.takeBack()
        TtsPlayback.set(TtsState.PLAYING, "one", 0..5)
    }

    @After fun clear() {
        TtsPlayback.takeBack()
        TtsPlayback.set(TtsState.IDLE, null, null)
    }

    @Test fun aJumpLetsGoOfThatBookOnly() {
        TtsPlayback.letGo("one")
        assertTrue(TtsPlayback.isLetGo("one"))
        assertFalse(TtsPlayback.isLetGo("two"))
        assertFalse(TtsPlayback.isLetGo(null))
    }

    @Test fun theVoiceStoppingDoesNotTakeThePageBack() {
        // The service goes IDLE before its onDestroy() saves; the jump must still hold then.
        TtsPlayback.letGo("one")
        TtsPlayback.set(TtsState.IDLE, null, null)
        assertTrue(TtsPlayback.isLetGo("one"))
    }

    @Test fun readingAloudAgainTakesThePageBack() {
        TtsPlayback.letGo("one")
        TtsPlayback.takeBack()
        assertFalse(TtsPlayback.isLetGo("one"))
    }
}
