package com.watchreader.mobile.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.watchreader.shared.stats.ReadingReport
import com.watchreader.shared.stats.ReadingSource

/**
 * Listening to a book read aloud on the phone. It is a source of its own because the watch's
 * reports carry the watch's listening as LISTEN rows, which [mergeWatchReport] keeps in step with
 * the watch; the phone's own listening must stay apart from them.
 */
const val PHONE_LISTEN = "phone_listen"

/**
 * Time spent on one book on one day from one source: read on the phone, read on the watch, or
 * listened to on either. The phone writes its own rows; the watch's arrive in its reports.
 */
@Entity(tableName = "reading_time", primaryKeys = ["bookId", "day", "source"])
data class ReadingTime(
    val bookId: String,
    val day: String,
    val source: String,
    val millis: Long,
    val chars: Int,
)

/** When a book was first opened and first read to its end, on whichever device came first; 0 when not yet. */
@Entity(tableName = "reading_milestone")
data class ReadingMilestone(
    @PrimaryKey val bookId: String,
    val firstOpenedEpochMs: Long,
    val finishedEpochMs: Long,
)

/**
 * The rows a report from the watch writes for one book, given the watch's rows the phone already
 * holds for it ([stored], its WATCH and LISTEN rows). Each day and source keeps the larger of the
 * two figures, and days the report does not mention stay as they are. A watch whose history of a
 * book began again (the book deleted there and sent back, the app reinstalled, a new watch) then
 * adds to what the phone has instead of wiping it, and a report that arrives twice changes
 * nothing. Only the rows that change are returned.
 */
fun mergeWatchReport(stored: List<ReadingTime>, report: ReadingReport): List<ReadingTime> {
    val kept = stored.filter { it.bookId == report.bookId }.associateBy { it.day to it.source }
    return report.days
        .filter { it.source == ReadingSource.WATCH || it.source == ReadingSource.LISTEN }
        .groupBy { it.day to it.source }
        .mapNotNull { (key, days) ->
            val old = kept[key]
            val merged = ReadingTime(
                bookId = report.bookId,
                day = key.first,
                source = key.second,
                millis = maxOf(old?.millis ?: 0, days.maxOf { it.millis }, 0),
                chars = maxOf(old?.chars ?: 0, days.maxOf { it.chars }, 0),
            )
            merged.takeIf { it != old }
        }
}
