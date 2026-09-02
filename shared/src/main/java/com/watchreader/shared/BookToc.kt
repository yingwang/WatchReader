package com.watchreader.shared

import org.json.JSONArray
import org.json.JSONObject

/** One entry in a book's contents: where a chapter starts in the plain text. */
data class Chapter(val title: String, val start: Int)

object BookToc {
    /** Longest a line can be and still read as a heading rather than a sentence. */
    private const val MAX_HEADING_CHARS = 48

    private val headings = listOf(
        Regex("""^(chapter|part|book|volume|act|scene)\b.{0,40}$""", RegexOption.IGNORE_CASE),
        Regex("""^第[0-9零一二三四五六七八九十百千两]+[章回节節卷部篇].{0,20}$"""),
        Regex("""^[IVXLC]{1,7}\.?$"""),
    )

    fun toJson(chapters: List<Chapter>): String {
        val array = JSONArray()
        for (chapter in chapters) {
            array.put(JSONObject().put("title", chapter.title).put("start", chapter.start))
        }
        return array.toString()
    }

    fun fromJson(json: String?): List<Chapter> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                Chapter(item.getString("title"), item.getInt("start"))
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Chapter headings in a book that arrived as plain text. A heading is a short line of its own
     * that names a division; anything longer is prose that happens to start with the word.
     */
    fun detect(text: String): List<Chapter> {
        val named = scan(text) { line, _, _ -> headings.any { it.matches(line) } }
        if (named.size >= 2) return named
        // A book that does not name its divisions still sets them apart: a short line of its own,
        // blank above and below, ending in no full stop.
        return scan(text) { line, before, after ->
            before.isEmpty() && after.isEmpty() && line.isNotEmpty() && line.last() !in SENTENCE_ENDS
        }
    }

    private inline fun scan(text: String, isHeading: (line: String, before: String, after: String) -> Boolean): List<Chapter> {
        val found = ArrayList<Chapter>()
        val starts = ArrayList<Int>()
        val lines = ArrayList<String>()
        var pos = 0
        while (pos <= text.length) {
            var end = text.indexOf('\n', pos)
            if (end < 0) end = text.length
            starts.add(pos)
            lines.add(text.substring(pos, end).trim())
            if (end == text.length) break
            pos = end + 1
        }
        for (i in lines.indices) {
            if (found.size >= MAX_CHAPTERS) break
            val line = lines[i]
            if (line.length !in 1..MAX_HEADING_CHARS) continue
            val before = lines.getOrNull(i - 1).orEmpty()
            val after = lines.getOrNull(i + 1).orEmpty()
            if (isHeading(line, before, after)) found.add(Chapter(line, starts[i]))
        }
        return found
    }

    private val SENTENCE_ENDS = charArrayOf('.', ',', ';', ':', '!', '?', '\u3002', '\uFF0C', '\uFF1B', '\uFF1A', '\uFF01', '\uFF1F', '\u201D', '\u2019')

    /** Beyond this a contents list is noise, and the book is probably not chaptered at all. */
    private const val MAX_CHAPTERS = 500
}
