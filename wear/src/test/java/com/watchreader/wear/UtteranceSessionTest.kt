package com.watchreader.wear

import com.watchreader.wear.tts.UtteranceSession
import org.junit.Assert.*
import org.junit.Test

class UtteranceSessionTest {
    @Test fun switchingBooksRejectsOldCallbackEvenWithSameIndex() {
        val session = UtteranceSession()
        session.invalidate()
        val old = session.id(0)
        session.invalidate()
        assertNull(session.index(old))
        assertEquals(0, session.index(session.id(0)))
    }
    @Test fun callbackQueuedBeforePauseIsRejectedWhenDispatchedAfterResume() {
        val session = UtteranceSession()
        val oldDone = session.id(24)
        session.invalidate()
        val resumed = session.id(24)
        assertNull(session.index(oldDone))
        assertEquals(24, session.index(resumed))
    }
    @Test fun stopInvalidatesAllOutstandingCallbacks() {
        val session = UtteranceSession()
        val old = session.id(900)
        session.invalidate()
        assertNull(session.index(old))
    }
    @Test fun malformedIdsAreIgnored() {
        val session = UtteranceSession()
        for (id in listOf("s0", "0:-1", "0:no", "0:0:1", "garbage")) assertNull(session.index(id))
    }
}
