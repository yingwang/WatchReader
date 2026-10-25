package com.watchreader.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveStampsTest {
    @Test fun savesInOneMillisecondAreStampedInTheOrderTheyWereMade() {
        val stamps = SaveStamps()
        assertEquals(listOf(1_000L, 1_001L, 1_002L), List(3) { stamps.next(1_000) })
    }

    @Test fun aLaterSaveTakesTheTimeItWasMade() {
        val stamps = SaveStamps()
        stamps.next(1_000)
        stamps.next(1_000)
        assertEquals(5_000, stamps.next(5_000))
    }

    @Test fun aClockSetBackStillGivesALaterStamp() {
        val stamps = SaveStamps()
        stamps.next(5_000)
        assertEquals(5_001, stamps.next(4_000))
    }
}
