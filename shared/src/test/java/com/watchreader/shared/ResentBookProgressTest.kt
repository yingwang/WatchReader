package com.watchreader.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class ResentBookProgressTest {
    @Test fun completedBookKeepsEof() { assertEquals(100, resentBookOffset(100, 100, 100)) }
    @Test fun unfinishedBookKeepsPosition() { assertEquals(42, resentBookOffset(42, 100, 100)) }
    @Test fun changedLengthKeepsTheShareRead() {
        assertEquals(50, resentBookOffset(42, 100, 120))
        assertEquals(120, resentBookOffset(100, 100, 120))
    }
    @Test fun aPlaceFromTheOtherDeviceStaysWithinThisCopy() {
        assertEquals(42, offsetWithin(42, 100))
        assertEquals(100, offsetWithin(100, 100))
        // The phone's copy is longer than the watch's: its place past the end is the end here.
        assertEquals(100, offsetWithin(130, 100))
        assertEquals(0, offsetWithin(-5, 100))
        // A length not known yet keeps the place as it came.
        assertEquals(130, offsetWithin(130, 0))
        assertEquals(0, offsetWithin(-5, 0))
    }
    @Test fun absentOrInvalidPositionResets() {
        assertEquals(0, resentBookOffset(null, null, 100))
        assertEquals(0, resentBookOffset(-1, 100, 100))
        assertEquals(0, resentBookOffset(101, 100, 100))
    }
}
