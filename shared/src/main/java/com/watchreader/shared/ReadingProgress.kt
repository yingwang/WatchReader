package com.watchreader.shared

import org.json.JSONObject

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
