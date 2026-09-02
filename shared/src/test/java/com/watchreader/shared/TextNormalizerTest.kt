package com.watchreader.shared

import org.junit.Assert.assertEquals
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
    fun declaredCharsetWinsWhenItDecodesCleanly() {
        val bytes = "flödet är ök".toByteArray(Charsets.ISO_8859_1)
        assertEquals("flödet är ök", TextNormalizer.decode(bytes, "ISO-8859-1").text)
    }

    @Test
    fun lineEndingsAndBlankRunsCollapse() {
        val text = "第一章\r\n\r\n\r\n\r\n正文一行   \r\n第二行\r\n\r\n\r\n"
        assertEquals("第一章\n\n正文一行\n第二行", TextNormalizer.normalize(text))
    }
}
