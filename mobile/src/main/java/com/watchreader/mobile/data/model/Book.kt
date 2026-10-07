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
    val totalChars: Int = 0,
    val readOffsetChars: Int = 0,
    val lastReadEpochMs: Long = 0,
    val coverPath: String? = null,
    val tocJson: String? = null,
    /** Why the last transfer failed, in the watch's words; null otherwise. */
    val syncMessage: String? = null,
    /**
     * Who wrote it, several joined with ", ", as the book or its catalogue names them. Null for
     * plain text, for a book that names nobody, and for every book added before authors were kept.
     * The phone keeps it for itself; the watch is sent the book without it.
     */
    val author: String? = null,
)
