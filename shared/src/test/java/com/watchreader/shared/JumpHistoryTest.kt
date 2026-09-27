package com.watchreader.shared

import com.watchreader.shared.reader.JumpHistory
import org.junit.Assert.*
import org.junit.Test

class JumpHistoryTest {
    @Test fun returnPointStartsEmpty() { assertNull(JumpHistory().take()) }
    @Test fun multipleJumpsKeepOriginalPosition() {
        val history = JumpHistory()
        history.record(120)
        history.record(400)
        assertEquals(120, history.take())
        assertNull(history.returnOffset)
    }
    @Test fun jumpAfterReadingReturnsToWhereReadingStopped() {
        // 7% -> 40%, read on to 46%, then 72%: going back must land on 46%, not 7%.
        val history = JumpHistory()
        history.record(70)
        history.turned()
        history.record(460)
        assertEquals(460, history.take())
    }
    @Test fun readingAfterAJumpKeepsItsReturnPoint() {
        val history = JumpHistory()
        history.record(70)
        history.turned()
        assertEquals(70, history.take())
    }
    @Test fun undoIsSingleUse() {
        val history = JumpHistory()
        history.record(0)
        assertEquals(0, history.take())
        assertNull(history.take())
    }
    @Test fun newJumpAfterUndoRecordsNewOrigin() {
        val history = JumpHistory()
        history.record(120)
        history.take()
        history.record(700)
        assertEquals(700, history.take())
    }
}
