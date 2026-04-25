package com.watchreader.mobile.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "book")
data class Book(
    @PrimaryKey val id: String,
    val title: String,
    val filePath: String,
    val sizeBytes: Long,
    val addedEpochMs: Long,
    val syncStatus: SyncStatus = SyncStatus.NOT_SENT,
    val lastSyncEpochMs: Long = 0,
    val readProgress: Float = 0f,
)
