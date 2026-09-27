package com.watchreader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingDeletesTest {
    @Test fun readsTheBookIdFromItsOwnPath() {
        assertEquals("b1", PendingDeletes.bookId("/pending-delete/b1", "b1".toByteArray()))
    }
    @Test fun ignoresOtherPathsAndEmptyItems() {
        assertNull(PendingDeletes.bookId("/reading-progress/b1", "b1".toByteArray()))
        assertNull(PendingDeletes.bookId("/pending-delete/b1", null))
        assertNull(PendingDeletes.bookId("/pending-delete/b1", ByteArray(0)))
    }
}
