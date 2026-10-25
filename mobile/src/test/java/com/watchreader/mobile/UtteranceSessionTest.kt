package com.watchreader.mobile

import com.watchreader.mobile.tts.UtteranceSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone's copy of the watch's UtteranceSession, held to the same rules. */
class UtteranceSessionTest {
    @Test fun aCallbackFromAnEarlierBookIsRejectedEvenAtTheSameIndex() {
        val session = UtteranceSession()
        session.invalidate()
        val old = session.id(0)
        session.invalidate()
        assertNull(session.index(old))
        assertEquals(0, session.index(session.id(0)))
    }

    @Test fun aSkipOrSeekInvalidatesWhatWasQueuedBeforeIt() {
        // Every move to another sentence bumps the generation, so a sentence queued before the
        // move and finishing after it cannot turn the page back.
        val session = UtteranceSession()
        val queued = session.id(24)
        session.invalidate()
        assertNull(session.index(queued))
        assertEquals(24, session.index(session.id(24)))
    }

    @Test fun malformedIdsAreIgnored() {
        val session = UtteranceSession()
        for (id in listOf("s0", "0:-1", "0:no", "0:0:1", "garbage", "")) assertNull(session.index(id))
    }
}
