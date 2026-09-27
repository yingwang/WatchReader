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
