package com.watchreader.mobile.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Listening to a book read aloud on the phone. It is a source of its own because each report from
 * the watch replaces every row of the watch's listening whole, and would wipe the phone's with it.
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
