package com.watchreader.mobile.tts

import com.watchreader.shared.Chapter

/**
 * A book as the voice reads it: every sentence in order, as ranges into the book's text. That is
 * the normalized text the reader pages through, so a sentence's range is also a place in the book,
 * and the place the voice has reached is saved exactly as a page turned by hand would be.
 *
 * Reading usually begins where the reader's page does, which is rarely the start of a sentence.
 * The voice then begins part-way into that sentence, at the page's first word, rather than going
 * back to the page before for the sentence's beginning, which would turn the page back the moment
 * listening started. The sentence stays whole in the list, so going back a sentence from there
 * reads it from its beginning.
 */
class ReadAloudScript(val text: String, val sentences: List<IntRange>) {
    /** Reading begins in sentence [index] at character [from]: its start, or a word inside it. */
    data class Position(val index: Int, val from: Int)

    val size: Int get() = sentences.size

    /**
     * Where reading from [offset] begins: inside the sentence holding it, from the first word at or
     * after it; in the space between two sentences, at the next one. A sentence whose remainder is
     * only punctuation (a page that begins with a closing quote) is passed for the next. Past the
     * last sentence the position is [size], with nothing left to read.
     */
    fun position(offset: Int): Position {
        val o = offset.coerceIn(0, text.length)
        // The first sentence that ends at or after the offset.
        var lo = 0
        var hi = sentences.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sentences[mid].last < o) lo = mid + 1 else hi = mid
        }
        if (lo >= sentences.size) return Position(sentences.size, text.length)
        val sentence = sentences[lo]
        if (o <= sentence.first) return Position(lo, sentence.first)
        var from = o
        while (from <= sentence.last && text[from].isWhitespace()) from++
        val rest = (from..sentence.last)
        if (rest.isEmpty() || rest.none { text[it].isLetterOrDigit() }) {
            return if (lo + 1 < sentences.size) Position(lo + 1, sentences[lo + 1].first) else Position(sentences.size, text.length)
        }
        return Position(lo, from)
    }

    /** The part of sentence [index] that is spoken when reading began at [start]. */
    fun spoken(index: Int, start: Position?): IntRange {
        val sentence = sentences[index]
        return if (start != null && start.index == index && start.from in sentence) start.from..sentence.last else sentence
    }

    fun words(range: IntRange): String = text.substring(range.first, range.last + 1)

    /**
     * Where "previous sentence" goes from sentence [current], begun at [start]: back to the
     * beginning of a sentence that was begun part-way, otherwise to the sentence before.
     */
    fun previous(current: Int, start: Position?): Position {
        if (sentences.isEmpty()) return Position(0, 0)
        val here = current.coerceIn(0, sentences.size - 1)
        if (start != null && start.index == here && start.from > sentences[here].first) return Position(here, sentences[here].first)
        val target = (here - 1).coerceAtLeast(0)
        return Position(target, sentences[target].first)
    }

    /** Where "next sentence" goes from sentence [current]; null on the last, which has no next. */
    fun next(current: Int): Position? {
        val target = current + 1
        return if (target in sentences.indices) Position(target, sentences[target].first) else null
    }

    /**
     * The first sentence of the chapter after the one sentence [index] is in: where reading pauses
     * when the sleep timer is set to the end of the chapter. In the last chapter, and in a book
     * without chapters, it is [size], and the end of the book ends the chapter.
     */
    fun chapterEnd(chapters: List<Chapter>, index: Int): Int {
        if (index !in sentences.indices) return sentences.size
        val at = sentences[index].first
        val next = chapters.filter { it.start > at }.minByOrNull { it.start } ?: return sentences.size
        var lo = index + 1
        var hi = sentences.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sentences[mid].first < next.start) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** Splits [text] whole; a novel's worth is too much for the main thread. */
        fun of(text: String): ReadAloudScript = ReadAloudScript(text, SentenceParser.ranges(text))
    }
}
