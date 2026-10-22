package com.watchreader.mobile.highlight

import com.watchreader.shared.reader.Paginator
import java.text.BreakIterator
import java.util.Locale

/** A stretch of the book, [start, end) in its character offsets. */
data class TextSpan(val start: Int, val end: Int) {
    init {
        require(start in 0..end)
    }

    val isEmpty: Boolean get() = start == end
}

/** Picking out the words a finger lands on. Offsets here are into the [text] given. */
object WordSelection {
    /**
     * A word found in CJK text longer than this is a whole run of characters the platform could
     * not split (the JVM has no dictionary for Chinese), and a single character is picked instead.
     */
    private const val MAX_CJK_WORD = 4

    /**
     * The word that holds the character at [index]: letters and digits as the platform's word
     * rules join them ("isn't", "3.14", "well" of "well-known"). In Chinese and Japanese a word is
     * what the platform's dictionary finds, or the one character. Punctuation is selected on its
     * own; a space or line break selects nothing.
     */
    fun wordAt(text: String, index: Int, locale: Locale = Locale.getDefault()): TextSpan? {
        if (index !in text.indices) return null
        val at = snapDown(text, index)
        val c = text.codePointAt(at)
        if (Character.isWhitespace(c) || Character.isSpaceChar(c)) return null
        val iterator = BreakIterator.getWordInstance(locale)
        iterator.setText(text)
        val end = iterator.following(at)
        val start = iterator.previous()
        val word = if (start == BreakIterator.DONE || end == BreakIterator.DONE || start > at || end <= at) {
            TextSpan(at, at + Character.charCount(c))
        } else {
            TextSpan(start, end)
        }
        if (isCjk(c) && word.end - word.start > MAX_CJK_WORD) return TextSpan(at, at + Character.charCount(c))
        return trim(text, word)
    }

    /** [span] without the spaces and line breaks at either end, or null when nothing else is left. */
    fun trim(text: String, span: TextSpan): TextSpan? {
        var start = span.start.coerceIn(0, text.length)
        var end = span.end.coerceIn(start, text.length)
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return if (start < end) TextSpan(start, end) else null
    }

    /** [index] moved back off the second half of a surrogate pair, so no selection splits an emoji or a rare ideograph. */
    fun snapDown(text: String, index: Int): Int {
        if (index <= 0 || index >= text.length) return index.coerceIn(0, text.length)
        return if (text[index].isLowSurrogate() && text[index - 1].isHighSurrogate()) index - 1 else index
    }

    internal fun isCjk(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO -> true
        else -> false
    }
}

/**
 * How a selection on the page changes as a handle is dragged. The two ends are kept in order and
 * at least one character apart: a handle pulled past the other stops beside it. In a word of
 * letters (English, French, Russian) an end let go in the middle of the word goes to the word's
 * edge, so a selection never cuts "mats" to "m"; Chinese and Japanese keep the one character.
 */
object SelectionDrag {
    fun moveStart(text: String, pageStart: Int, current: TextSpan, to: Int): TextSpan {
        val limit = current.end - 1
        var start = snapIn(text, pageStart, to.coerceIn(pageStart, limit)) - pageStart
        if (inWord(text, start - 1) && inWord(text, start)) {
            while (inWord(text, start - 1)) start--
        }
        return TextSpan((pageStart + start).coerceAtMost(limit), current.end)
    }

    fun moveEnd(text: String, pageStart: Int, current: TextSpan, to: Int): TextSpan {
        val limit = current.start + 1
        var end = snapIn(text, pageStart, to.coerceIn(limit, pageStart + text.length)) - pageStart
        if (inWord(text, end - 1) && inWord(text, end)) {
            while (inWord(text, end)) end++
        }
        return TextSpan(current.start, (pageStart + end).coerceAtLeast(limit))
    }

    /**
     * The character at [i] is part of a word of letters: a letter outside Chinese and Japanese, or
     * an apostrophe between two such letters ("isn't").
     */
    private fun inWord(text: String, i: Int): Boolean {
        if (i !in text.indices) return false
        if (text[i] == '\'' || text[i] == '\u2019') return isLetter(text, i - 1) && isLetter(text, i + 1)
        return isLetter(text, i)
    }

    private fun isLetter(text: String, i: Int): Boolean {
        if (i !in text.indices) return false
        val c = text[i]
        return Character.isLetter(c) && !c.isSurrogate() && !WordSelection.isCjk(c.code)
    }

    /** A book offset, given the page's text and where it starts, kept off the middle of a surrogate pair. */
    private fun snapIn(text: String, pageStart: Int, offset: Int): Int =
        pageStart + WordSelection.snapDown(text, offset - pageStart)
}

/** One line's share of a stretch of the book: characters [from, to) of that line's own text. */
data class LineSpan(val line: Int, val from: Int, val to: Int)

object PageSpans {
    /**
     * The parts of [start, end) drawn on a page, line by line. [lines] are the page's lines, whose
     * offsets are the book's; a line index in the result is an index into [lines]. A stretch that
     * began on an earlier page or runs on to a later one gives its part on this page, and one
     * wholly elsewhere gives nothing. The line break between two paragraphs belongs to no line.
     */
    fun onPage(lines: List<Paginator.Line>, start: Int, end: Int): List<LineSpan> {
        if (end <= start) return emptyList()
        val spans = ArrayList<LineSpan>()
        for ((index, line) in lines.withIndex()) {
            if (line.end <= line.start) continue
            val from = maxOf(start, line.start)
            val to = minOf(end, line.end)
            if (from < to) spans += LineSpan(index, from - line.start, to - line.start)
        }
        return spans
    }
}
