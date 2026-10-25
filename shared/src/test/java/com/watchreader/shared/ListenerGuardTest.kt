package com.watchreader.shared

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ListenerGuardTest {
    @Test fun aFailingEventIsReportedAndTheRestOfTheBatchIsStillApplied() {
        val applied = ArrayList<String>()
        val failed = ArrayList<Pair<String, String?>>()
        ListenerGuard.each(listOf("progress", "bookmarks", "reading-time"), { item, e -> failed += item to e.message }) { item ->
            if (item == "bookmarks") throw IllegalStateException("Unknown bookmark format")
            applied += item
        }
        assertEquals(listOf("progress", "reading-time"), applied)
        assertEquals(listOf("bookmarks" to "Unknown bookmark format"), failed)
    }

    @Test fun everyFailureInABatchIsReportedOnItsOwn() {
        val failed = ArrayList<Int>()
        ListenerGuard.each(1..4, { item, _ -> failed += item }) { item -> if (item % 2 == 0) throw IOException("disk full") }
        assertEquals(listOf(2, 4), failed)
    }

    @Test fun aCancellationIsNotSwallowed() {
        val applied = ArrayList<Int>()
        try {
            ListenerGuard.each(1..3, { _, _ -> fail("A cancellation is not a failure") }) { item ->
                if (item == 2) throw CancellationException("stopped")
                applied += item
            }
            fail("The cancellation did not get through")
        } catch (_: CancellationException) {
        }
        assertEquals(listOf(1), applied)
    }

    @Test fun aSingleMessageThatFailsIsReported() {
        var reported: Exception? = null
        ListenerGuard.guard({ reported = it }) { throw IllegalArgumentException("bad receipt") }
        assertEquals("bad receipt", reported?.message)
        var ran = false
        ListenerGuard.guard({ fail("Nothing failed") }) { ran = true }
        assertEquals(true, ran)
    }
}
