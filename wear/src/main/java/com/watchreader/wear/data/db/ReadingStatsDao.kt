package com.watchreader.wear.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.watchreader.wear.data.model.ReadingMilestone
import com.watchreader.wear.data.model.ReadingTime

@Dao
abstract class ReadingStatsDao {
    @Query("UPDATE reading_time SET millis = millis + :millis, chars = chars + :chars WHERE bookId = :bookId AND day = :day AND source = :source")
    protected abstract suspend fun addToRow(bookId: String, day: String, source: String, millis: Long, chars: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertRow(row: ReadingTime)

    /** Adds to the day's row, making it on the first reading of the day. */
    @Transaction
    open suspend fun add(bookId: String, day: String, source: String, millis: Long, chars: Int) {
        if (addToRow(bookId, day, source, millis, chars) == 0) insertRow(ReadingTime(bookId, day, source, millis, chars))
    }

    @Query("SELECT * FROM reading_time WHERE bookId = :bookId")
    abstract suspend fun timeFor(bookId: String): List<ReadingTime>

    @Query("INSERT OR IGNORE INTO reading_milestone (bookId, firstOpenedEpochMs, finishedEpochMs) VALUES (:bookId, 0, 0)")
    protected abstract suspend fun ensureMilestone(bookId: String)

    @Query("UPDATE reading_milestone SET firstOpenedEpochMs = :at WHERE bookId = :bookId AND firstOpenedEpochMs = 0")
    protected abstract suspend fun setOpened(bookId: String, at: Long)

    @Query("UPDATE reading_milestone SET finishedEpochMs = :at WHERE bookId = :bookId AND finishedEpochMs = 0")
    protected abstract suspend fun setFinished(bookId: String, at: Long)

    @Transaction
    open suspend fun markOpened(bookId: String, at: Long) {
        ensureMilestone(bookId)
        setOpened(bookId, at)
    }

    @Transaction
    open suspend fun markFinished(bookId: String, at: Long) {
        ensureMilestone(bookId)
        setFinished(bookId, at)
    }

    @Query("SELECT * FROM reading_milestone WHERE bookId = :bookId")
    abstract suspend fun milestoneFor(bookId: String): ReadingMilestone?

    @Query("DELETE FROM reading_time WHERE bookId = :bookId")
    protected abstract suspend fun deleteTime(bookId: String)

    @Query("DELETE FROM reading_milestone WHERE bookId = :bookId")
    protected abstract suspend fun deleteMilestone(bookId: String)

    @Transaction
    open suspend fun forget(bookId: String) {
        deleteTime(bookId)
        deleteMilestone(bookId)
    }
}
