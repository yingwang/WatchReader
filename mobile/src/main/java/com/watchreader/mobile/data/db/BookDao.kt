package com.watchreader.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM book ORDER BY addedEpochMs DESC")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM book WHERE id = :id")
    suspend fun getById(id: String): Book?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: Book)

    @Query("DELETE FROM book WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE book SET syncStatus = :status, lastSyncEpochMs = :epochMs WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus, epochMs: Long)

    @Query("UPDATE book SET readProgress = :progress WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Float)
}
