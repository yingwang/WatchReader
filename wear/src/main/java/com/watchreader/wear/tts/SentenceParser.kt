package com.watchreader.wear.tts

/** Splits text into sentences with their character ranges in the source. */
object SentenceParser {
    private const val MAX_SENTENCE = 400
    private const val ENDS = "。！？!?\n"
    private const val SOFT = "；;，,、：:"
    private const val CLOSERS = "”’」』）】》\")]}"

    /** Words a full stop follows without ending the sentence. */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "st", "mt", "vs", "etc", "no", "jr", "sr",
        "inc", "ltd", "co", "fig", "vol", "ch", "pp", "al", "ed", "op",
    )

    fun split(text: String): List<String> = ranges(text).map { text.substring(it.first, it.last + 1) }

    fun splitWithRanges(text: String, from: Int = 0): List<Pair<String, IntRange>> =
        ranges(text, from).map { text.substring(it.first, it.last + 1) to it }

    /**
     * Where each sentence lies in [text] from [from] on, as ranges only, so a whole novel can be
     * split without being copied. Sentences end at a full stop or a line break, keeping closing
     * quotes attached. An English full stop counts only when it is followed by a space and ends a
     * word that is neither an abbreviation nor an initial, so "Mr. Darcy", "J. K. Rowling",
     * "e.g." and "3.14" stay whole. Very long stretches without a full stop are cut at a comma so
     * the engine never gets a paragraph-sized utterance, which some voices refuse.
     */
    fun ranges(text: String, from: Int = 0): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = from.coerceIn(0, text.length)
        var i = start
        var lastSoft = -1
        while (i < text.length) {
            val c = text[i]
            if (c in ENDS || (c == '.' && fullStopAt(text, i))) {
                var cut = i + 1
                while (cut < text.length && text[cut] in CLOSERS) cut++
                emit(text, start, cut, out)
                start = cut
                i = cut
                lastSoft = -1
                continue
            }
            if (c in SOFT) lastSoft = i + 1
            if (i - start >= MAX_SENTENCE) {
                val cut = if (lastSoft > start) lastSoft else i + 1
                emit(text, start, cut, out)
                start = cut
                i = cut
                lastSoft = -1
                continue
            }
            i++
        }
        emit(text, start, text.length, out)
        return out
    }

    private fun fullStopAt(text: String, i: Int): Boolean {
        var after = i + 1
        while (after < text.length && text[after] in CLOSERS) after++
        if (after < text.length && !text[after].isWhitespace()) return false
        var wordStart = i
        while (wordStart > 0 && text[wordStart - 1].isLetter()) wordStart--
        // a dotted abbreviation: e.g., U.S., p.m.
        if (wordStart > 0 && text[wordStart - 1] == '.') return false
        val word = text.substring(wordStart, i)
        // an initial: J. K. Rowling
        if (word.length == 1 && word[0].isUpperCase()) return false
        return word.lowercase() !in ABBREVIATIONS
    }

    private fun emit(text: String, start: Int, end: Int, out: MutableList<IntRange>) {
        if (end <= start) return
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (e > s) out.add(s until e)
    }
}
