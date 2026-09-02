package com.watchreader.wear.tts

/** Splits text into sentences with their character ranges in the source. */
object SentenceParser {
    private const val MAX_SENTENCE = 400
    private const val ENDS = "。！？!?\n"
    private const val SOFT = "；;，,、：:"
    private const val CLOSERS = "”’」』）】》\")]}"

    fun split(text: String): List<String> = splitWithRanges(text).map { it.first }

    /**
     * Sentences end at a full stop or a line break, keeping closing quotes attached. Very long
     * stretches without a full stop are cut at a comma so the engine never gets a paragraph-sized
     * utterance, which some voices refuse.
     */
    fun splitWithRanges(text: String, from: Int = 0): List<Pair<String, IntRange>> {
        val out = ArrayList<Pair<String, IntRange>>()
        var start = from.coerceIn(0, text.length)
        var i = start
        var lastSoft = -1
        while (i < text.length) {
            val c = text[i]
            if (c in ENDS) {
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

    private fun emit(text: String, start: Int, end: Int, out: MutableList<Pair<String, IntRange>>) {
        if (end <= start) return
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (e > s) out.add(text.substring(s, e) to (s until e))
    }
}
