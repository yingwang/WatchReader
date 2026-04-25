package com.watchreader.shared

import org.json.JSONObject

data class BookMetadata(
    val id: String,
    val title: String,
    val sizeBytes: Long,
    val addedEpochMs: Long,
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("sizeBytes", sizeBytes)
        put("addedEpochMs", addedEpochMs)
    }.toString()

    companion object {
        fun fromJson(json: String): BookMetadata {
            val obj = JSONObject(json)
            return BookMetadata(
                id = obj.getString("id"),
                title = obj.getString("title"),
                sizeBytes = obj.getLong("sizeBytes"),
                addedEpochMs = obj.getLong("addedEpochMs"),
            )
        }
    }
}
