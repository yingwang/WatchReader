package com.watchreader.mobile.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Time spent on one book on one day from one source: read on the phone, read on the watch, or
 * listened to on the watch. The phone writes its own rows; the watch's arrive in its reports.
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
