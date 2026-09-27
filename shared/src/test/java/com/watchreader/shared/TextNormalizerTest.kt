package com.watchreader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {
    @Test
    fun utf8WithBomIsDecodedWithoutTheMark() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "春眠不觉晓".toByteArray(Charsets.UTF_8)
        val decoded = TextNormalizer.decode(bytes)
        assertEquals("春眠不觉晓", decoded.text)
        assertEquals("UTF-8", decoded.charset)
    }

    @Test
    fun gbkBytesFallBackToGb18030() {
        val bytes = "处处闻啼鸟。夜来风雨声".toByteArray(charset("GBK"))
        val decoded = TextNormalizer.decode(bytes)
        assertEquals("处处闻啼鸟。夜来风雨声", decoded.text)
        assertEquals("GB18030", decoded.charset)
    }

    @Test
    fun big5BytesAreReadAsTraditionalChineseNotAsGb18030() {
        val text = "處處聞啼鳥。夜來風雨聲，花落知多少。春眠不覺曉，這是一個很好的早晨，我們都在這裡。"
        val decoded = TextNormalizer.decode(text.toByteArray(charset("Big5")))
        assertEquals(text, decoded.text)
        assertEquals("Big5", decoded.charset)
    }

    @Test
    fun shiftJisBytesAreReadAsJapanese() {
        val text = "吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。"
        val decoded = TextNormalizer.decode(text.toByteArray(charset("Shift_JIS")))
        assertEquals(text, decoded.text)
        assertEquals("Shift_JIS", decoded.charset)
    }

    @Test
    fun declaredCharsetWinsWhenItDecodesCleanly() {
        val bytes = "flödet är ök".toByteArray(Charsets.ISO_8859_1)
        assertEquals("flödet är ök", TextNormalizer.decode(bytes, "ISO-8859-1").text)
    }

    @Test
    fun lineEndingsAndBlankRunsCollapse() {
        val text = "第一章\r\n\r\n\r\n\r\n正文一行   \r\n第二行\r\n\r\n\r\n"
        assertEquals("第一章\n\n正文一行\n第二行", TextNormalizer.normalize(text))
    }

    private val chinese = "春眠不觉晓，处处闻啼鸟。夜来风雨声，花落知多少。这是一个很好的早晨，我们都在这里读书。"

    @Test
    fun utf8WithAStrayBadByteIsStillUtf8() {
        val good = chinese.repeat(20).toByteArray(Charsets.UTF_8)
        val bytes = good.copyOfRange(0, 300) + byteArrayOf(0xFF.toByte()) + good.copyOfRange(300, good.size)
        val decoded = TextNormalizer.decode(bytes)
        assertEquals("UTF-8", decoded.charset)
        assertEquals(chinese.repeat(20), decoded.text)
    }

    @Test
    fun aLongerGbkBookIsStillGb18030() {
        val text = "第一章 风雨\n\n" + chinese.repeat(30) + "\n\nChapter 2, with some ASCII in it.\n\n" + chinese.repeat(30)
        val decoded = TextNormalizer.decode(text.toByteArray(charset("GBK")))
        assertEquals("GB18030", decoded.charset)
        assertEquals(text, decoded.text)
    }

    @Test
    fun westernBytesFallBackToWindows1252WithTheirQuotes() {
        val text = "\u201CHello,\u201D she said \u2014 it\u2019s late, and the caf\u00E9 is shut."
        val decoded = TextNormalizer.decode(text.toByteArray(charset("windows-1252")))
        assertEquals("windows-1252", decoded.charset)
        assertEquals(text, decoded.text)
    }

    @Test
    fun aDeclaredLatin1IsReadAsWindows1252() {
        val text = "It\u2019s the caf\u00E9 on the corner."
        assertEquals(text, TextNormalizer.decode(text.toByteArray(charset("windows-1252")), "ISO-8859-1").text)
    }

    @Test
    fun c1ControlCodesNeverSurviveNormalize() {
        assertEquals("its \u201Cgone\u201D", TextNormalizer.normalize("it\u0092s\u0085 \u201Cgone\u201D\u0097\u007F"))
    }

    @Test
    fun binaryFilesAreNotText() {
        // A Kindle book opens with a Palm database header: a name padded with zero bytes.
        val mobi = "Some Book".toByteArray() + ByteArray(23) + "BOOKMOBI".toByteArray() + ByteArray(200) { (it * 7).toByte() }
        assertTrue(TextNormalizer.looksBinary(mobi))
        // Compressed data without a single zero byte still has control codes all through it.
        val noisy = ByteArray(4096) { i -> if (i % 9 == 0) 0x05 else (0x41 + i % 26).toByte() }
        assertTrue(TextNormalizer.looksBinary(noisy))
    }

    @Test
    fun textInAnyEncodingIsNotBinary() {
        assertFalse(TextNormalizer.looksBinary(chinese.toByteArray(Charsets.UTF_8)))
        assertFalse(TextNormalizer.looksBinary(chinese.toByteArray(charset("GBK"))))
        assertFalse(TextNormalizer.looksBinary("Line one\r\n\tLine two\u000C\r\n".toByteArray()))
        val utf16WithBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + chinese.toByteArray(Charsets.UTF_16LE)
        assertFalse(TextNormalizer.looksBinary(utf16WithBom))
    }

    @Test
    fun utf16WithoutAMarkIsTextAndDecodes() {
        val text = "A plain English book, saved as UTF-16 by a Windows tool: caf\u00E9."
        val bytes = text.toByteArray(Charsets.UTF_16LE)
        assertFalse(TextNormalizer.looksBinary(bytes))
        assertEquals(text, TextNormalizer.decode(bytes).text)
    }

    /** Words set to lines no wider than [width], the way Project Gutenberg sets its books. */
    private fun wrap(paragraph: String, width: Int = 70): String {
        val lines = ArrayList<String>()
        var line = StringBuilder()
        for (word in paragraph.split(' ')) {
            if (line.isNotEmpty() && line.length + 1 + word.length > width) {
                lines.add(line.toString())
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        lines.add(line.toString())
        return lines.joinToString("\n")
    }

    private val paragraphs = listOf(
        "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife. However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well fixed in the minds of the surrounding families, that he is considered as the rightful property of some one or other of their daughters.",
        "My dear Mr. Bennet, said his lady to him one day, have you heard that Netherfield Park is let at last? Mr. Bennet replied that he had not, and he went back to his paper without another word.",
        "But it is, returned she; for Mrs. Long has just been here, and she told me all about it, and she would not stop talking until the tea had gone quite cold in the pot beside her chair.",
    )

    @Test
    fun hardWrappedParagraphsAreJoinedBackTogether() {
        val book = (1..6).flatMap { paragraphs }.joinToString("\n\n") { wrap(it) }
        val decoded = TextNormalizer.decode(book.toByteArray()).text
        assertEquals((1..6).flatMap { paragraphs }.joinToString("\n\n"), decoded)
    }

    @Test
    fun indentedVerseAndShortLinesKeepTheirBreaks() {
        val verse = "    Shall I compare thee to a summer's day, thou art more lovely and more temperate,\n" +
            "    Rough winds do shake the darling buds of May, and summer's lease hath all too short a date."
        val book = (1..6).flatMap { paragraphs }.joinToString("\n\n") { wrap(it) } + "\n\n" + verse + "\n\nTHE END\nFinis"
        val unwrapped = TextNormalizer.unwrap(TextNormalizer.normalize(book))
        assertTrue(unwrapped.endsWith(verse + "\n\nTHE END\nFinis"))
    }

    @Test
    fun aParagraphToALineIsNotWrapped() {
        // Short paragraphs one to a line, no blank lines between: long lines that run straight
        // on, but of every length rather than broken at one width.
        val lengths = (0 until 60).map { 50 + (it * 37) % 56 }
        val book = lengths.joinToString("\n") { n -> ("She said nothing for a while and then " + "went on ".repeat(20)).take(n).trimEnd() + "." }
        assertEquals(book, TextNormalizer.unwrap(book))
    }

    @Test
    fun chineseLinesAreNeverJoined() {
        val book = (1..60).joinToString("\n") { chinese.repeat(2) }
        assertEquals(book, TextNormalizer.unwrap(book))
    }

    @Test
    fun aShortNoteIsLeftAsItIs() {
        val note = wrap(paragraphs[0])
        assertEquals(note, TextNormalizer.unwrap(note))
    }
}
