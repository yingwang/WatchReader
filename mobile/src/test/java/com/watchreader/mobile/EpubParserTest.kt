package com.watchreader.mobile

import com.watchreader.mobile.util.EpubParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {
    private fun epub(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun chaptersWithEncodedHrefsAreFoundInSpineOrder() {
        val bytes = epub(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to """
                <package><metadata><dc:title>红楼梦 &amp; 脂评</dc:title></metadata>
                <manifest>
                  <item id="c2" href="text/second%20chapter.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c1" href="../OEBPS/text/first.xhtml" media-type="application/xhtml+xml"/>
                  <item id="css" href="style.css" media-type="text/css"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>
            """.trimIndent(),
            "OEBPS/text/first.xhtml" to "<html><head><title>x</title></head><body><h1>第一回</h1><p>甄士隐梦幻识通灵&#x2014;&#8220;贾雨村&#8221;</p></body></html>",
            "OEBPS/text/second chapter.xhtml" to "<html><body><p>第二回</p><p>贾夫人仙逝扬州城</p></body></html>",
        )
        val parsed = EpubParser.parse(bytes.inputStream())
        assertEquals("红楼梦 & 脂评", parsed.title)
        assertEquals("第一回\n甄士隐梦幻识通灵—“贾雨村”\n\n第二回\n贾夫人仙逝扬州城", parsed.text)
    }

    @Test
    fun entitiesOutsideTheBasicPlaneDecode() {
        assertEquals("𝄞 ok", EpubParser.decodeEntities("&#x1D11E; ok"))
        assertEquals("a b", EpubParser.htmlToText("<p>a&nbsp;b</p>"))
    }

    @Test
    fun zipMagicIsRecognised() {
        assertTrue(EpubParser.looksLikeEpub(epub("a" to "b")))
    }
}
