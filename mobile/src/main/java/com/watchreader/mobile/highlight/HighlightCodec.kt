package com.watchreader.mobile.highlight

import org.json.JSONArray
import org.json.JSONObject

/**
 * One book's highlights as JSON. A later version of the app may add fields, and an earlier one
 * may have left some out, so a record is read for what it has: unknown fields are ignored and a
 * missing colour, note or time takes a plain default. A record without a usable place in the book
 * cannot be shown anywhere and is skipped. A file that is not JSON at all, or that a newer version
 * wrote in a format this one does not know, is refused as a whole rather than read as empty: the
 * next save would otherwise overwrite every note in it.
 */
object HighlightCodec {
    const val VERSION = 1

    fun encode(bookId: String, highlights: List<Highlight>): String {
        val records = JSONArray()
        for (h in highlights.sortedWith(Highlight.BOOK_ORDER)) {
            val record = JSONObject()
                .put("id", h.id)
                .put("start", h.start)
                .put("end", h.end)
                .put("quote", h.quote)
                .put("color", h.color.key)
                .put("created", h.createdAt)
                .put("updated", h.updatedAt)
            if (h.note.isNotEmpty()) record.put("note", h.note)
            records.put(record)
        }
        return JSONObject().put("version", VERSION).put("bookId", bookId).put("highlights", records).toString()
    }

    /** The highlights in [json], in book order. Throws when the file as a whole cannot be trusted. */
    fun decode(json: String): List<Highlight> {
        val root = JSONObject(json)
        // A file from before versions were written is the first format.
        val version = root.optInt("version", VERSION)
        require(version in 1..VERSION) { "Highlights were saved by a newer version (format $version)" }
        val records = root.optJSONArray("highlights") ?: return emptyList()
        val read = ArrayList<Highlight>(records.length())
        for (i in 0 until records.length()) {
            record(records.optJSONObject(i) ?: continue)?.let(read::add)
        }
        // Two records with one id would make edits land on whichever came first; the later one is kept.
        return read.associateBy { it.id }.values.sortedWith(Highlight.BOOK_ORDER)
    }

    private fun record(json: JSONObject): Highlight? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val start = json.optInt("start", -1)
        val end = json.optInt("end", -1)
        if (start < 0 || end <= start) return null
        val created = json.optLong("created", 0L)
        return Highlight(
            id = id,
            start = start,
            end = end,
            quote = json.optString("quote"),
            color = HighlightColor.fromKey(json.optString("color").takeIf { it.isNotEmpty() }),
            note = json.optString("note"),
            createdAt = created,
            updatedAt = json.optLong("updated", created),
        )
    }
}
