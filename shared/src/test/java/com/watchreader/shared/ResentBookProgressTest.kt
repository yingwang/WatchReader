package com.watchreader.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class ResentBookProgressTest {
    @Test fun completedBookKeepsEof() { assertEquals(100, resentBookOffset(100, 100, 100)) }
    @Test fun unfinishedBookKeepsPosition() { assertEquals(42, resentBookOffset(42, 100, 100)) }
    @Test fun changedLengthResetsPosition() { assertEquals(0, resentBookOffset(42, 100, 120)) }
    @Test fun absentOrInvalidPositionResets() {
        assertEquals(0, resentBookOffset(null, null, 100))
        assertEquals(0, resentBookOffset(-1, 100, 100))
        assertEquals(0, resentBookOffset(101, 100, 100))
    }
}
