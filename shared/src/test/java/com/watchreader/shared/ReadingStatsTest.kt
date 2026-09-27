package com.watchreader.shared

import com.watchreader.shared.stats.ReadingClock
import com.watchreader.shared.stats.ReadingDay
import com.watchreader.shared.stats.ReadingReport
import com.watchreader.shared.stats.ReadingSource
import com.watchreader.shared.stats.ReadingStatsSync
import com.watchreader.shared.stats.charsPerMinute
import com.watchreader.shared.stats.dayOf
import com.watchreader.shared.stats.formatDuration
import com.watchreader.shared.stats.lastDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ReadingStatsTest {
    @Test fun aPageCountsUntilTheNextOne() {
        val clock = ReadingClock()
        clock.start(1_000)
        assertEquals(20_000, clock.turn(21_000))
        assertEquals(15_000, clock.turn(36_000))
    }

    @Test fun aPageLeftOpenCountsForTwoMinutesAtMost() {
        val clock = ReadingClock()
        clock.start(0)
        assertEquals(ReadingClock.CAP_MS, clock.turn(30 * 60 * 1000L))
    }

    @Test fun nothingCountsWhileStopped() {
        val clock = ReadingClock()
        assertEquals(0, clock.turn(5_000))
        clock.start(10_000)
        assertEquals(5_000, clock.stop(15_000))
        assertFalse(clock.running)
        assertEquals(0, clock.turn(60_000))
        clock.start(100_000)
        assertTrue(clock.running)
        assertEquals(3_000, clock.stop(103_000))
    }

    @Test fun startingTwiceKeepsTheFirstMoment() {
        val clock = ReadingClock()
        clock.start(0)
        clock.start(50_000)
        assertEquals(60_000, clock.stop(60_000))
    }

    @Test fun reportRoundTrips() {
        val report = ReadingReport("b", 10, 0, listOf(ReadingDay("2026-09-27", ReadingSource.WATCH, 60_000, 800), ReadingDay("2026-09-27", ReadingSource.LISTEN, 30_000, 0)))
        assertEquals(report, ReadingStatsSync.decode("/reading-time/b", report.toJson().toByteArray()))
        assertNull(ReadingStatsSync.decode("/reading-progress/b", report.toJson().toByteArray()))
        assertNull(ReadingStatsSync.decode("/reading-time/b", "bad".toByteArray()))
    }

    @Test fun speedNeedsEnoughReadingAndLeavesListeningOut() {
        val few = listOf(ReadingDay("d", ReadingSource.WATCH, 60_000, 500))
        assertNull(charsPerMinute(few))
        val enough = listOf(
            ReadingDay("d", ReadingSource.WATCH, 6 * 60_000, 3_000),
            ReadingDay("d", ReadingSource.PHONE, 4 * 60_000, 2_000),
            ReadingDay("d", ReadingSource.LISTEN, 60 * 60_000, 0),
        )
        assertEquals(500.0, charsPerMinute(enough)!!, 0.001)
    }

    @Test fun daysAndDurations() {
        assertEquals("2026-09-27", dayOf(LocalDate.of(2026, 9, 27).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + 1_000, ZoneOffset.UTC))
        assertEquals(listOf("2026-09-25", "2026-09-26", "2026-09-27"), lastDays(3, LocalDate.of(2026, 9, 27)))
        assertEquals("under a minute", formatDuration(59_000))
        assertEquals("34 min", formatDuration(34 * 60_000L))
        assertEquals("2 h", formatDuration(120 * 60_000L))
        assertEquals("2 h 5 min", formatDuration(125 * 60_000L))
    }
}
