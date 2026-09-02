package com.watchreader.shared

import org.junit.Assert.assertEquals
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
}
