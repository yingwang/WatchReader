package com.watchreader.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.watchreader.mobile.data.model.ReadingMilestone
import com.watchreader.mobile.data.model.ReadingTime
import com.watchreader.shared.stats.ReadingReport
import com.watchreader.shared.stats.ReadingSource
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ReadingStatsDao {
    @Query("UPDATE reading_time SET millis = millis + :millis, chars = chars + :chars WHERE bookId = :bookId AND day = :day AND source = :source")
    protected abstract suspend fun addToRow(bookId: String, day: String, source: String, millis: Long, chars: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putRows(rows: List<ReadingTime>)

    /** Adds to the day's row, making it on the first reading of the day. */
    @Transaction
    open suspend fun add(bookId: String, day: String, source: String, millis: Long, chars: Int) {
        if (addToRow(bookId, day, source, millis, chars) == 0) putRows(listOf(ReadingTime(bookId, day, source, millis, chars)))
    }

    @Query("SELECT * FROM reading_time")
    abstract fun observeAll(): Flow<List<ReadingTime>>

    @Query("SELECT * FROM reading_time WHERE bookId = :bookId")
    abstract fun observeFor(bookId: String): Flow<List<ReadingTime>>

    @Query("SELECT * FROM reading_milestone WHERE bookId = :bookId")
    abstract fun observeMilestone(bookId: String): Flow<ReadingMilestone?>

    @Query("INSERT OR IGNORE INTO reading_milestone (bookId, firstOpenedEpochMs, finishedEpochMs) VALUES (:bookId, 0, 0)")
    protected abstract suspend fun ensureMilestone(bookId: String)

    /** Keeps the earliest moment seen on either device. */
    @Query("UPDATE reading_milestone SET firstOpenedEpochMs = :at WHERE bookId = :bookId AND :at > 0 AND (firstOpenedEpochMs = 0 OR firstOpenedEpochMs > :at)")
    protected abstract suspend fun setOpened(bookId: String, at: Long)

    @Query("UPDATE reading_milestone SET finishedEpochMs = :at WHERE bookId = :bookId AND :at > 0 AND (finishedEpochMs = 0 OR finishedEpochMs > :at)")
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

    @Query("DELETE FROM reading_time WHERE bookId = :bookId AND source IN (:sources)")
    protected abstract suspend fun deleteSources(bookId: String, sources: List<String>)

    /** The watch sends its whole history of a book each time; it replaces what came before. */
    @Transaction
    open suspend fun applyWatchReport(report: ReadingReport) {
        deleteSources(report.bookId, listOf(ReadingSource.WATCH, ReadingSource.LISTEN))
        putRows(
            report.days.filter { it.source == ReadingSource.WATCH || it.source == ReadingSource.LISTEN }
                .map { ReadingTime(report.bookId, it.day, it.source, it.millis, it.chars) },
        )
        if (report.firstOpenedEpochMs > 0 || report.finishedEpochMs > 0) {
            ensureMilestone(report.bookId)
            setOpened(report.bookId, report.firstOpenedEpochMs)
            setFinished(report.bookId, report.finishedEpochMs)
        }
    }

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
