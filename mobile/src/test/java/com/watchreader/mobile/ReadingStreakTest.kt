package com.watchreader.mobile

import com.watchreader.mobile.data.model.ReadingTime
import com.watchreader.mobile.ui.screen.streak
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ReadingStreakTest {
    private val today = LocalDate.of(2026, 10, 19)
    private fun read(day: String, millis: Long = 60_000) = ReadingTime("book", day, "phone", millis, 0)

    @Test fun countsBackFromToday() {
        val rows = listOf(read("2026-10-19"), read("2026-10-18"), read("2026-10-17"), read("2026-10-15"))
        assertEquals(3, streak(rows, today))
    }

    @Test fun aDayNotYetReadDoesNotBreakTheRun() {
        assertEquals(2, streak(listOf(read("2026-10-18"), read("2026-10-17")), today))
    }

    @Test fun aMissedDayEndsIt() {
        assertEquals(0, streak(listOf(read("2026-10-17"), read("2026-10-16")), today))
    }

    @Test fun emptyRowsDoNotCount() {
        assertEquals(1, streak(listOf(read("2026-10-19"), read("2026-10-18", millis = 0)), today))
    }

    @Test fun severalSourcesOnOneDayCountOnce() {
        val rows = listOf(read("2026-10-19"), read("2026-10-19").copy(source = "watch"), read("2026-10-18"))
        assertEquals(2, streak(rows, today))
    }
}
