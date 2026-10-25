package com.watchreader.mobile

import com.watchreader.mobile.data.model.PHONE_LISTEN
import com.watchreader.mobile.data.model.ReadingTime
import com.watchreader.mobile.data.model.mergeWatchReport
import com.watchreader.shared.stats.ReadingDay
import com.watchreader.shared.stats.ReadingReport
import com.watchreader.shared.stats.ReadingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchReportMergeTest {
    private fun row(day: String, source: String, millis: Long, chars: Int = 0) = ReadingTime("book", day, source, millis, chars)
    private fun report(vararg days: ReadingDay) = ReadingReport("book", 0, 0, days.toList())

    /** What the phone holds after [changed] is written over [stored], as the DAO's REPLACE does. */
    private fun applied(stored: List<ReadingTime>, changed: List<ReadingTime>): Set<ReadingTime> {
        val byKey = stored.associateBy { it.day to it.source }.toMutableMap()
        for (r in changed) byKey[r.day to r.source] = r
        return byKey.values.toSet()
    }

    @Test fun aHistoryBegunAgainOnTheWatchKeepsTheEarlierDays() {
        // The book was deleted on the watch and sent back: its first report knows only today.
        val stored = listOf(row("2026-10-01", ReadingSource.WATCH, 600_000, 4_000), row("2026-10-02", ReadingSource.LISTEN, 900_000))
        val changed = mergeWatchReport(stored, report(ReadingDay("2026-10-20", ReadingSource.WATCH, 60_000, 300)))
        assertEquals(listOf(row("2026-10-20", ReadingSource.WATCH, 60_000, 300)), changed)
        assertEquals(stored.toSet() + row("2026-10-20", ReadingSource.WATCH, 60_000, 300), applied(stored, changed))
    }

    @Test fun eachDayAndSourceKeepsTheLargerFigure() {
        val stored = listOf(row("2026-10-20", ReadingSource.WATCH, 600_000, 4_000), row("2026-10-20", ReadingSource.LISTEN, 100_000))
        val changed = mergeWatchReport(
            stored,
            report(ReadingDay("2026-10-20", ReadingSource.WATCH, 120_000, 5_000), ReadingDay("2026-10-20", ReadingSource.LISTEN, 300_000, 0)),
        )
        assertEquals(
            setOf(row("2026-10-20", ReadingSource.WATCH, 600_000, 5_000), row("2026-10-20", ReadingSource.LISTEN, 300_000)),
            applied(stored, changed),
        )
    }

    @Test fun aReportThatArrivesTwiceChangesNothing() {
        val first = report(ReadingDay("2026-10-19", ReadingSource.WATCH, 60_000, 200), ReadingDay("2026-10-20", ReadingSource.LISTEN, 30_000, 0))
        val stored = applied(emptyList(), mergeWatchReport(emptyList(), first)).toList()
        assertTrue(mergeWatchReport(stored, first).isEmpty())
    }

    @Test fun onlyTheWatchsOwnSourcesAreTaken() {
        val changed = mergeWatchReport(
            emptyList(),
            report(ReadingDay("2026-10-20", ReadingSource.PHONE, 60_000, 200), ReadingDay("2026-10-20", PHONE_LISTEN, 60_000, 0)),
        )
        assertTrue(changed.isEmpty())
    }

    @Test fun anotherBooksRowsAreNotMistakenForThisOnes() {
        val other = ReadingTime("other", "2026-10-20", ReadingSource.WATCH, 900_000, 9_000)
        val changed = mergeWatchReport(listOf(other), report(ReadingDay("2026-10-20", ReadingSource.WATCH, 60_000, 300)))
        assertEquals(listOf(row("2026-10-20", ReadingSource.WATCH, 60_000, 300)), changed)
    }
}
