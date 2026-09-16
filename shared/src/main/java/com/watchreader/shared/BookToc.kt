package com.watchreader.shared

import org.json.JSONArray
import org.json.JSONObject

/** One entry in a book's contents: where a chapter starts in the plain text. */
data class Chapter(val title: String, val start: Int)

object BookToc {
    /**
     * The book's own contents when it has any, else headings found in the text. A contents list
     * that is present but empty is a book already known to have no chapters, and its text is left
     * alone; a list that is missing or unreadable is an old transfer, and the text is scanned.
     */
    fun resolve(json: String?, text: String): List<Chapter> {
        val declared = parse(json) ?: return detect(text)
        if (declared.isEmpty()) return emptyList()
        // Books imported before contents pages were recognised carry the whole list in their
        // stored contents, so the same sieve runs here and they come right without being re-added.
        val named = declared.filter { it.title.isNotBlank() && it.start in text.indices }
        val usable = withoutContentsPage(named.distinctBy { it.start }.sortedBy { it.start })
        if (usable.isEmpty()) return detect(text)
        if (places(named.size, usable, text.length)) return usable
        // The stored offsets never told the chapters apart. That is how a book kept in one
        // document looked before the anchors naming its chapters were read, and the offsets
        // cannot be recovered from here, so the text is scanned instead. Where a scan turns up
        // nothing, what was stored is still better than leaving the book without contents.
        return detect(text).ifEmpty { usable }
    }

    /**
     * Whether a contents has actually placed its chapters through the book. Entries that all
     * came to rest on one offset were never told apart, and a book whose last chapter falls
     * inside its opening quarter has not been divided so much as pointed at.
     */
    private fun places(declared: Int, usable: List<Chapter>, length: Int): Boolean {
        if (declared < MIN_PLACED_CHAPTERS || length <= 0) return true
        if (usable.size < MIN_PLACED_CHAPTERS) return false
        return usable.last().start.toLong() * PLACED_BEYOND >= length.toLong()
    }

    /** Below this many entries there is no telling a failure of placement from a short book. */
    private const val MIN_PLACED_CHAPTERS = 3

    /** A contents that reaches no further than this fraction of the book has placed nothing. */
    private const val PLACED_BEYOND = 4

    /**
     * The contents a book was given, with nothing scanned in its place. The phone reads the file
     * a book came from and sends the chapter list along with the text; the watch keeps only the
     * text, and what it used to work out from that alone was worse than going without. A book
     * that prints its own contents yielded the printed list and nothing else, so every chapter
     * in the reader led back to the opening pages and tapping one appeared to do nothing.
     */
    fun declared(json: String?, text: String): List<Chapter> {
        val declared = parse(json) ?: return emptyList()
        val named = declared.filter { it.title.isNotBlank() && it.start in text.indices }
        val usable = withoutContentsPage(named.distinctBy { it.start }.sortedBy { it.start })
        return if (places(named.size, usable, text.length)) usable else emptyList()
    }

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

    /** The chapters a contents string holds; null when there is no string or it cannot be read. */
    fun parse(json: String?): List<Chapter>? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                Chapter(item.getString("title"), item.getInt("start"))
            }
        }.getOrNull()
    }

    /**
     * Chapter headings in a book that arrived as plain text. A heading is a short line of its own
     * that names a division; anything longer is prose that happens to start with the word.
     */
    fun detect(text: String): List<Chapter> {
        val named = withoutContentsPage(scan(text) { line, _, _ -> headings.any { it.matches(line) } })
        if (named.size >= 2 && reachesThroughBook(named, text.length)) return named
        // A book that does not name its divisions still sets them apart: a short line of its own,
        // blank above and below, ending in no full stop, closing quote or bracket.
        val guessed = withoutContentsPage(scan(text) { line, before, after ->
            before.isEmpty() && after.isEmpty() && line.isNotEmpty() && line.last() !in SENTENCE_ENDS
        })
        if (guessed.size < 2) return emptyList()
        // Dialogue set one speech to a line looks just like a run of headings. No book has a
        // chapter every couple of paragraphs, so a list that dense is noise and the book goes without.
        val paragraphs = text.lineSequence().count { it.isNotBlank() }
        if (guessed.size * MIN_PARAGRAPHS_PER_CHAPTER > paragraphs) return emptyList()
        return if (reachesThroughBook(guessed, text.length)) guessed else emptyList()
    }

    /**
     * Whether headings found in a book are spread through it rather than gathered at its front.
     * A book that prints its contents and then names its chapters some way the scan cannot read
     * gives up only the printed list, and every entry of it points into the opening pages. Such
     * an answer is worse than none: each chapter leads back to the beginning of the book.
     */
    private fun reachesThroughBook(found: List<Chapter>, length: Int): Boolean =
        found.isEmpty() || length <= 0 || found.last().start.toLong() * FRONT_MATTER_SHARE >= length.toLong()

    /** Headings that end within this fraction of a book never reached its chapters. */
    private const val FRONT_MATTER_SHARE = 20

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
            if (found.size >= MAX_CHAPTERS * 2) break
            val line = lines[i]
            if (line.length !in 1..MAX_HEADING_CHARS) continue
            // A heading names something. Rules, ornaments and stray punctuation name nothing,
            // and a contents made of them gives the reader a list of blank rows to tap.
            if (line.none { it.isLetterOrDigit() }) continue
            val before = lines.getOrNull(i - 1).orEmpty()
            val after = lines.getOrNull(i + 1).orEmpty()
            if (isHeading(line, before, after)) found.add(Chapter(line, starts[i]))
        }
        return found
    }

    /**
     * The headings a book's own contents page contributes, removed. Such a page lists every
     * chapter title once at the front and the text names each of them again where the chapter
     * actually begins, so an unguarded scan finds each title twice and the earlier of the two
     * wins: every entry then points into the contents page rather than at the text it names.
     *
     * The list is recognised by its shape. Headings crowded together with no room for prose
     * between them are an index of chapters rather than the chapters themselves, and a crowd
     * whose titles the book uses again elsewhere is the contents page.
     */
    private fun withoutContentsPage(found: List<Chapter>): List<Chapter> {
        if (found.size < MIN_CONTENTS_ENTRIES * 2) return found.take(MAX_CHAPTERS)
        val keys = found.map { key(it.title) }
        val listed = BooleanArray(found.size)
        for (i in found.indices) {
            // A title the book sets again further on is one this line merely announces,
            if ((i + 1 until found.size).none { keys[it] == keys[i] }) continue
            // provided no chapter of its own follows it here, or the line above announced too.
            val bare = i + 1 < found.size && found[i + 1].start - found[i].start < MIN_CHAPTER_CHARS
            if (bare || (i > 0 && listed[i - 1])) listed[i] = true
        }
        // A repeated title or two is a coincidence; a contents page runs longer than that.
        var i = 0
        while (i < found.size) {
            if (!listed[i]) { i++; continue }
            var end = i
            while (end + 1 < found.size && listed[end + 1]) end++
            if (end - i + 1 < MIN_CONTENTS_ENTRIES) for (k in i..end) listed[k] = false
            i = end + 1
        }
        return found.filterIndexed { index, _ -> !listed[index] }.take(MAX_CHAPTERS)
    }

    /** A heading without the leader dots and page number a contents line trails behind it. */
    private fun key(title: String): String = title.replace(CONTENTS_LEADER, "").trim().lowercase()

    private val CONTENTS_LEADER = Regex("""[\s.\u00b7\u2026_\u2014-]{2,}\d{1,4}$""")

    /** Fewer entries than this crowded together is a run of short chapters, not a contents page. */
    private const val MIN_CONTENTS_ENTRIES = 3

    /** Headings closer together than this have no room for a chapter's worth of prose between them. */
    private const val MIN_CHAPTER_CHARS = 200

    /** What a sentence, a speech or an aside ends in; a heading ends in none of these. */
    private val SENTENCE_ENDS = charArrayOf(
        '.', ',', ';', ':', '!', '?', '"', '\'', ')', ']', '\u2026', '\u00BB',
        '\u3002', '\uFF0C', '\uFF1B', '\uFF1A', '\uFF01', '\uFF1F', '\uFF09', '\u3011', '\u300B',
        '\u201D', '\u2019', '\u300D', '\u300F',
    )

    /** A guessed contents list denser than one entry per this many paragraphs is dialogue, not chapters. */
    private const val MIN_PARAGRAPHS_PER_CHAPTER = 3

    /** Beyond this a contents list is noise, and the book is probably not chaptered at all. */
    private const val MAX_CHAPTERS = 500
}
