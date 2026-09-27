package com.watchreader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BookTransferTest {
    @Test
    fun headerRoundTripsAndLeavesTheBodyUntouched() {
        val meta = BookMetadata("abc", "红楼梦", 1234, 5678, totalChars = 42)
        val out = ByteArrayOutputStream()
        BookTransfer.writeHeader(out, meta)
        out.write("满纸荒唐言\n一把辛酸泪".toByteArray(Charsets.UTF_8))
        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(meta, BookTransfer.readHeader(input))
        assertEquals("满纸荒唐言\n一把辛酸泪", input.readBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun channelPathsCarryTheBookId() {
        assertEquals("/book/x1", DataLayerPaths.bookChannelPath("x1"))
        assertEquals("x1", DataLayerPaths.bookIdFromChannelPath("/book/x1"))
        assertNull(DataLayerPaths.bookIdFromChannelPath("/book/"))
        assertNull(DataLayerPaths.bookIdFromChannelPath("/progress"))
    }

    /** A contents list of [count] chapters with titles the length a web novel gives them. */
    private fun toc(count: Int): String =
        BookToc.toJson((1..count).map { Chapter("\u7b2c${it}\u7ae0 \u591c\u96e8\u5bc4\u5317\u7684\u6545\u4eba", it * 5000) })

    @Test
    fun contentsTooLargeForTheHeaderFollowInAMessage() {
        val meta = BookMetadata("abc", "\u957f\u7bc7", 1234, 5678, totalChars = 9_000_000, tocJson = toc(1200))
        val out = ByteArrayOutputStream()
        BookTransfer.writeHeader(out, meta)
        assertNull(BookTransfer.readHeader(ByteArrayInputStream(out.toByteArray())).tocJson)
        val payload = BookTransfer.contentsLeftOut(meta)
        assertNotNull(payload)
        assertEquals(BookContents("abc", meta.tocJson), BookContents.fromJson(payload!!.toString(Charsets.UTF_8)))
    }

    @Test
    fun contentsTheHeaderCarriedAreNotSentAgain() {
        val meta = BookMetadata("abc", "\u77ed\u7bc7", 1234, 5678, totalChars = 42, tocJson = toc(20))
        val out = ByteArrayOutputStream()
        BookTransfer.writeHeader(out, meta)
        assertEquals(meta.tocJson, BookTransfer.readHeader(ByteArrayInputStream(out.toByteArray())).tocJson)
        assertNull(BookTransfer.contentsLeftOut(meta))
        assertNull(BookTransfer.contentsLeftOut(meta.copy(tocJson = null)))
    }

    @Test
    fun contentsTooLargeForAMessageAreNotSent() {
        val meta = BookMetadata("abc", "\u957f\u7bc7", 1234, 5678, totalChars = 9_000_000, tocJson = toc(3000))
        assertNull(BookTransfer.contentsLeftOut(meta))
    }
}
