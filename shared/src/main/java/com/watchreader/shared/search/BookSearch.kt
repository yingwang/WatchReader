package com.watchreader.shared.search

/** All offsets are UTF-16 positions in the original book, just like reading progress. */
data class SearchHit(val start: Int, val end: Int, val excerpt: String, val matchStart: Int, val matchEnd: Int)

data class SearchResults(val hits: List<SearchHit> = emptyList(), val hasMore: Boolean = false)

object BookSearch {
    const val RESULT_LIMIT = 200

    /** Literal, case-insensitive KMP search. No whole-book lowercase copy or regular expression. */
    fun find(text: String, query: String, limit: Int = RESULT_LIMIT, checkpoint: () -> Unit = {}): SearchResults {
        require(limit > 0)
        val needle = query.trim()
        if (needle.isEmpty() || text.isEmpty()) return SearchResults()
        fun fold(c: Char) = c.uppercaseChar().lowercaseChar()
        val pattern = CharArray(needle.length) { fold(needle[it]) }
        val prefix = IntArray(pattern.size)
        var matched = 0
        for (i in 1 until pattern.size) {
            if (i % 1024 == 0) checkpoint()
            while (matched > 0 && pattern[i] != pattern[matched]) matched = prefix[matched - 1]
            if (pattern[i] == pattern[matched]) matched++
            prefix[i] = matched
        }
        val hits = ArrayList<SearchHit>()
        matched = 0
        for (i in text.indices) {
            if (i % 1024 == 0) checkpoint()
            val c = fold(text[i])
            while (matched > 0 && c != pattern[matched]) matched = prefix[matched - 1]
            if (c == pattern[matched]) matched++
            if (matched == pattern.size) {
                if (hits.size == limit) return SearchResults(hits, hasMore = true)
                val start = i + 1 - pattern.size
                // Do not offer an offset in the middle of a supplementary character.
                if (TextExcerpt.isBoundary(text, start) && TextExcerpt.isBoundary(text, i + 1)) {
                    hits += TextExcerpt.hit(text, start, i + 1)
                }
                matched = prefix[matched - 1]
            }
        }
        checkpoint()
        return SearchResults(hits)
    }
}

object TextExcerpt {
    fun isBoundary(text: String, offset: Int): Boolean = offset <= 0 || offset >= text.length ||
        !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())

    fun bookmark(text: String, offset: Int): String {
        require(offset in text.indices && isBoundary(text, offset))
        var end = (offset + 160).coerceAtMost(text.length)
        if (!isBoundary(text, end)) end--
        return singleLine(text.substring(offset, end)).trim() + if (end < text.length) "…" else ""
    }

    internal fun hit(text: String, start: Int, end: Int): SearchHit {
        var from = (start - 56).coerceAtLeast(0)
        var to = (end + 72).coerceAtMost(text.length)
        if (!isBoundary(text, from)) from--
        if (!isBoundary(text, to)) to++
        val prefix = if (from > 0) "…" else ""
        val excerpt = prefix + singleLine(text.substring(from, to)) + if (to < text.length) "…" else ""
        return SearchHit(start, end, excerpt, prefix.length + start - from, prefix.length + end - from)
    }

    private fun singleLine(text: String) = text.map { if (it.isWhitespace()) ' ' else it }.joinToString("")
}
