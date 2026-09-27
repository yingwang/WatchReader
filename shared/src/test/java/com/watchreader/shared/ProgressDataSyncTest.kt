package com.watchreader.shared

import org.junit.Assert.*
import org.junit.Test

class ProgressDataSyncTest {
    private val current = ReadingProgress("book", 100, .5f, 200)

    @Test fun olderSaveCannotReplaceOfflineProgress() {
        assertFalse(ProgressDataSync.shouldPublish(current, current.copy(charOffset = 20, lastReadEpochMs = 100)))
    }
    @Test fun newerBackwardJumpIsKept() {
        assertTrue(ProgressDataSync.shouldPublish(current, current.copy(charOffset = 20, lastReadEpochMs = 300)))
    }
    @Test fun unchangedProgressDoesNotWakePeerAgain() {
        assertFalse(ProgressDataSync.shouldPublish(current, current))
        assertTrue(ProgressDataSync.shouldPublish(null, current))
    }
    @Test fun progressPayloadRoundTrips() {
        assertEquals(current, ProgressDataSync.decode("/reading-progress/book", current.toJson().toByteArray()))
    }
    @Test fun unrelatedOrMalformedItemsAreIgnored() {
        assertNull(ProgressDataSync.decode("/book/book", current.toJson().toByteArray()))
        assertNull(ProgressDataSync.decode("/reading-progress/book", "bad".toByteArray()))
        assertNull(ProgressDataSync.decode("/reading-progress/book", null))
    }
}
