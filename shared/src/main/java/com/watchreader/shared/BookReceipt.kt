package com.watchreader.shared

import org.json.JSONObject

/** The watch's answer after a book channel closes: stored, or failed with a reason. */
data class BookReceipt(
    val bookId: String,
    val ok: Boolean,
    val message: String = "",
    val totalChars: Int = 0,
) {
    fun toJson(): String = JSONObject().apply {
        put("bookId", bookId)
        put("ok", ok)
        put("message", message)
        put("totalChars", totalChars)
    }.toString()

    companion object {
        fun fromJson(json: String): BookReceipt {
            val obj = JSONObject(json)
            return BookReceipt(
                bookId = obj.getString("bookId"),
                ok = obj.optBoolean("ok", false),
                message = obj.optString("message", ""),
                totalChars = obj.optInt("totalChars", 0),
            )
        }
    }
}
