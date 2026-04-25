package com.watchreader.wear.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wear_book")
data class WearBook(
    @PrimaryKey val id: String,
    val title: String,
    val filePath: String,
    val sizeBytes: Long,
    val addedEpochMs: Long,
    val lastReadEpochMs: Long = 0,
    val readOffsetChars: Int = 0,
    val totalChars: Int = 0,
)
