package com.watchreader.shared

import org.json.JSONObject

/**
 * A book's contents on its own, sent when the watch has a book whose chapters it cannot place.
 * The text is already on the watch and never changes, so only the chapter list travels.
 */
data class BookContents(val bookId: String, val tocJson: String?) {
    fun toJson(): String = JSONObject()
        .put("bookId", bookId)
        .put("tocJson", tocJson ?: JSONObject.NULL)
        .toString()

    companion object {
        fun fromJson(json: String): BookContents {
            val o = JSONObject(json)
            return BookContents(
                bookId = o.getString("bookId"),
                tocJson = if (o.isNull("tocJson")) null else o.getString("tocJson"),
            )
        }
    }
}
