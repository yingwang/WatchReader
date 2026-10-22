package com.watchreader.mobile.highlight

import com.watchreader.shared.Chapter

/** How far through the book an offset is, truncated as the reader's own percentage is. */
fun percentOf(offset: Int, length: Int): Int =
    if (length <= 0) 0 else (offset.toLong() * 100 / length).toInt().coerceIn(0, 100)

object HighlightChapters {
    /**
     * The chapter [offset] falls in: the last one starting at or before it, or null before the
     * first. [chapters] are in book order, as BookToc gives them; a web novel has thousands, so the
     * search halves rather than walks.
     */
    fun at(chapters: List<Chapter>, offset: Int): Chapter? {
        var lo = 0
        var hi = chapters.size - 1
        var found: Chapter? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (chapters[mid].start <= offset) {
                found = chapters[mid]
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}

/**
 * A book's highlights and notes as Markdown, for sharing to a notes app or mail. The title and
 * author head it; each chapter that holds highlights is a heading of its own, above its passages
 * in book order, each quoted, followed by its note and where it is in the book.
 */
object HighlightMarkdown {
    fun build(
        title: String,
        author: String?,
        highlights: List<Highlight>,
        chapters: List<Chapter>,
        totalChars: Int,
    ): String {
        val out = StringBuilder()
        out.append("# ").append(oneLine(title)).append('\n')
        author?.let(::oneLine)?.takeIf { it.isNotEmpty() }?.let { out.append('\n').append('*').append(it).append("*\n") }
        var heading: Chapter? = null
        for (h in highlights.sortedWith(Highlight.BOOK_ORDER)) {
            val chapter = HighlightChapters.at(chapters, h.start)
            if (chapter != null && chapter != heading) {
                out.append("\n## ").append(oneLine(chapter.title)).append('\n')
                heading = chapter
            }
            out.append('\n')
            for (line in h.quote.trim().lines()) {
                val text = line.trim()
                out.append(if (text.isEmpty()) ">" else "> $text").append('\n')
            }
            if (h.hasNote) out.append('\n').append(h.note.trim()).append('\n')
            out.append('\n').append('*').append(percentOf(h.start, totalChars)).append("%*\n")
        }
        return out.toString()
    }

    /** A heading or an author line ends at its line break, so a title with one in it is joined up. */
    private fun oneLine(text: String): String = text.lines().joinToString(" ") { it.trim() }.trim()
}
