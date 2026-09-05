package com.watchreader.mobile

import com.watchreader.mobile.util.EpubParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {
    private fun epub(vararg files: Pair<String, String>): ByteArray = epub(files.toList(), blob = null)

    /** [blob] adds one more entry of that name filled with [blob].second zero bytes. */
    private fun epub(files: List<Pair<String, String>>, blob: Pair<String, Int>?): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            if (blob != null) {
                zip.putNextEntry(ZipEntry(blob.first))
                val block = ByteArray(64 * 1024)
                var remaining = blob.second
                while (remaining > 0) {
                    val n = minOf(remaining, block.size)
                    zip.write(block, 0, n)
                    remaining -= n
                }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Two chapters whose headings differ from the names the book's own contents give them. */
    private fun twoChapterBook(toc: Pair<String, String>, tocItem: String): List<Pair<String, String>> = listOf(
        "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
        "OEBPS/content.opf" to """
            <package><metadata><dc:title>Probe</dc:title></metadata><manifest>
            <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
            <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
            $tocItem</manifest><spine toc="toc"><itemref idref="a"/><itemref idref="b"/></spine></package>
        """.trimIndent(),
        "OEBPS/a.xhtml" to "<html><body><h1>Heading A</h1><p>Body A.</p></body></html>",
        "OEBPS/b.xhtml" to "<html><body><h1>Heading B</h1><p>Body B.</p></body></html>",
        toc,
    )

    private val navBook = twoChapterBook(
        "OEBPS/nav.xhtml" to """
            <html><body><nav epub:type="toc"><ol>
            <li><a href="a.xhtml">Declared A</a></li>
            <li><a href="b.xhtml">Declared B</a></li>
            </ol></nav></body></html>
        """.trimIndent(),
        tocItem = """<item id="toc" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""",
    )

    private val ncxBook = twoChapterBook(
        "OEBPS/toc.ncx" to """
            <ncx><navMap>
            <navPoint id="a"><navLabel><text>Declared A</text></navLabel><content src="a.xhtml"/></navPoint>
            <navPoint id="b"><navLabel><text>Declared B</text></navLabel><content src="b.xhtml"/></navPoint>
            </navMap></ncx>
        """.trimIndent(),
        tocItem = """<item id="toc" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""",
    )

    @Test
    fun epub3NavContentsBeatTheHeadings() {
        val parsed = EpubParser.parse(epub(navBook, blob = null).inputStream())
        assertEquals(listOf("Declared A", "Declared B"), parsed.chapters.map { it.title })
    }

    @Test
    fun epub2NcxContentsBeatTheHeadings() {
        val parsed = EpubParser.parse(epub(ncxBook, blob = null).inputStream())
        assertEquals(listOf("Declared A", "Declared B"), parsed.chapters.map { it.title })
    }

    @Test
    fun anArchiveThatUnpacksPastTheCapIsRefused() {
        val tooBig = (EpubParser.MAX_UNPACKED_BYTES + 1).toInt()
        val bytes = epub(navBook, blob = "OEBPS/unused.bin" to tooBig)
        assertTrue("the compressed file itself is small", bytes.size < 256 * 1024)
        assertThrows(IllegalArgumentException::class.java) { EpubParser.parse(bytes.inputStream()) }
    }

    @Test
    fun embeddedFontsAreSkippedRatherThanCounted() {
        val bytes = epub(navBook, blob = "OEBPS/fonts/serif.ttf" to (EpubParser.MAX_UNPACKED_BYTES + 1).toInt())
        assertEquals("Body A.", EpubParser.parse(bytes.inputStream()).text.lines().first { it.startsWith("Body") })
    }

    @Test
    fun picturesPastTheirBudgetAreDroppedNotFatal() {
        val bytes = epub(navBook, blob = "OEBPS/images/plate.jpg" to (EpubParser.MAX_IMAGE_BYTES + 1).toInt())
        val parsed = EpubParser.parse(bytes.inputStream())
        assertNull(parsed.cover)
        assertTrue(parsed.text.contains("Body B."))
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
        assertEquals("第一回\n\n甄士隐梦幻识通灵—“贾雨村”\n\n第二回\n\n贾夫人仙逝扬州城", parsed.text)
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
