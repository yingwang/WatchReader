package com.watchreader.wear.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.watchreader.wear.data.model.WearBook
import kotlinx.coroutines.flow.Flow

@Dao
interface WearBookDao {
    @Query("SELECT * FROM wear_book ORDER BY lastReadEpochMs DESC, addedEpochMs DESC")
    fun observeAll(): Flow<List<WearBook>>

    @Query("SELECT * FROM wear_book WHERE id = :id")
    suspend fun getById(id: String): WearBook?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: WearBook)

    @Query("DELETE FROM wear_book WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE wear_book SET readOffsetChars = :offset, lastReadEpochMs = :epochMs WHERE id = :id")
    suspend fun updateProgress(id: String, offset: Int, epochMs: Long)
}
