package com.watchreader.shared.bookmark

import org.json.JSONArray
import org.json.JSONObject

data class BookmarkSnapshot(val writerId: String, val records: List<Bookmark>)

object BookmarkCodec {
    private const val VERSION = 1

    fun encode(bookmark: Bookmark): String = json(bookmark).put("version", VERSION).toString()

    fun decode(json: String): Bookmark {
        val root = JSONObject(json)
        require(root.getInt("version") == VERSION)
        return record(root)
    }

    fun encodeSnapshot(snapshot: BookmarkSnapshot): String = JSONObject()
        .put("version", VERSION).put("writerId", snapshot.writerId)
        .put("records", JSONArray().apply { snapshot.records.sortedBy { it.key }.forEach { put(json(it)) } }).toString()

    /** Fail closed: corrupt/unknown formats are never treated as an empty store and overwritten. */
    fun decodeSnapshot(json: String): BookmarkSnapshot {
        val root = JSONObject(json)
        require(root.getInt("version") == VERSION)
        val writer = root.getString("writerId")
        require(writer.isNotBlank() && writer.length <= 128)
        val array = root.getJSONArray("records")
        val records = List(array.length()) { record(array.getJSONObject(it)) }
        require(records.map { it.key }.distinct().size == records.size)
        return BookmarkSnapshot(writer, records)
    }

    private fun json(b: Bookmark) = JSONObject().put("bookId", b.bookId).put("offset", b.offset)
        .put("excerpt", b.excerpt).put("revision", b.revision).put("writerId", b.writerId).put("deleted", b.deleted)

    private fun record(json: JSONObject) = Bookmark(
        json.getString("bookId"), json.getInt("offset"), json.getString("excerpt"),
        json.getLong("revision"), json.getString("writerId"), json.getBoolean("deleted"),
    )
}
