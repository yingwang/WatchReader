package com.watchreader.shared

import org.json.JSONObject

/**
 * Where a book was last read on one device. [charOffset] is a UTF-16 offset into the book's text,
 * the end of the text for a book read to its end; [percentage] is the same place as a share of the
 * book, 0 to 1, for a library that has not loaded the text; [lastReadEpochMs] is when the reading
 * happened, not when it was sent, and is what decides which of two readings wins.
 */
data class ReadingProgress(
    val bookId: String,
    val charOffset: Int,
    val percentage: Float,
    val lastReadEpochMs: Long,
) {
    fun toJson(): String = JSONObject().apply {
        put("bookId", bookId)
        put("charOffset", charOffset)
        put("percentage", percentage.toDouble())
        put("lastReadEpochMs", lastReadEpochMs)
    }.toString()

    companion object {
        fun fromJson(json: String): ReadingProgress {
            val obj = JSONObject(json)
            return ReadingProgress(
                bookId = obj.getString("bookId"),
                charOffset = obj.getInt("charOffset"),
                percentage = obj.getDouble("percentage").toFloat(),
                lastReadEpochMs = obj.getLong("lastReadEpochMs"),
            )
        }
    }
}
