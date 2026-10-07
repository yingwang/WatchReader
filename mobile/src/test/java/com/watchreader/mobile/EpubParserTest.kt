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
    @Test fun containerAcceptsSingleQuotesAndAttributeWhitespace() {
        val original = EpubParser.parse(epub(navBook, blob = null).inputStream())
        for (attribute in listOf("full-path='OEBPS/content.opf'", "full-path = \"OEBPS/content.opf\"", "full-path\n = 'OEBPS/content.opf'")) {
            val files = navBook.map { (name, text) ->
                name to if (name == "META-INF/container.xml")
                    text.replace("full-path=\"OEBPS/content.opf\"", attribute) else text
            }
            val parsed = EpubParser.parse(epub(files, blob = null).inputStream())
            assertEquals(original.text, parsed.text)
            assertEquals(original.title, parsed.title)
            assertEquals(original.chapters, parsed.chapters)
        }
    }

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

    /** A book that prints its contents as a page of its own, the way converted epubs often do. */
    private val bookWithContentsPage = listOf(
        "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
        "OEBPS/content.opf" to """
            <package><metadata><dc:title>Probe</dc:title></metadata><manifest>
            <item id="toc" href="contents.xhtml" media-type="application/xhtml+xml"/>
            <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
            <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            </manifest><spine toc="ncx"><itemref idref="toc"/><itemref idref="a"/><itemref idref="b"/></spine></package>
        """.trimIndent(),
        "OEBPS/contents.xhtml" to "<html><body><p>Chapter One</p><p>Chapter Two</p><p>Chapter Three</p></body></html>",
        "OEBPS/a.xhtml" to "<html><body><h1>Chapter One</h1><p>Body A.</p></body></html>",
        "OEBPS/b.xhtml" to "<html><body><h1>Chapter Two</h1><p>Body B.</p></body></html>",
        "OEBPS/toc.ncx" to """
            <ncx><navMap>
            <navPoint id="a"><navLabel><text>Chapter One</text></navLabel><content src="a.xhtml"/></navPoint>
            <navPoint id="b"><navLabel><text>Chapter Two</text></navLabel><content src="b.xhtml"/></navPoint>
            <navPoint id="c"><navLabel><text>Chapter Three</text></navLabel><content src="c.xhtml"/></navPoint>
            </navMap></ncx>
        """.trimIndent(),
    )

    @Test
    fun aContentsPageIsNotReadAsText() {
        val parsed = EpubParser.parse(epub(bookWithContentsPage, blob = null).inputStream())
        assertEquals(listOf("Chapter One", "Chapter Two"), parsed.chapters.map { it.title })
        // The page that merely lists the chapters is gone, so the text opens on the first of them
        // and each entry lands on the chapter rather than on the line that announced it.
        assertTrue(parsed.text.startsWith("Chapter One"))
        assertEquals(1, Regex("Chapter Two").findAll(parsed.text).count())
        assertEquals(0, parsed.chapters[0].start)
        assertEquals(parsed.text.indexOf("Chapter Two"), parsed.chapters[1].start)
    }

    @Test
    fun anEpub3NavDocumentInTheSpineIsNotReadAsText() {
        val spined = navBook.map { (name, content) ->
            if (name.endsWith("content.opf")) {
                name to content.replace("""<itemref idref="a"/>""", """<itemref idref="toc"/><itemref idref="a"/>""")
            } else name to content
        }
        val parsed = EpubParser.parse(epub(spined, blob = null).inputStream())
        assertEquals(listOf("Declared A", "Declared B"), parsed.chapters.map { it.title })
        assertTrue(parsed.text.startsWith("Heading A"))
        assertEquals(0, parsed.chapters[0].start)
    }

    /** A book kept in one document, its chapters marked by anchors the way converters emit them. */
    private val singleDocumentBook = listOf(
        "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
        "OEBPS/content.opf" to """
            <package><metadata><dc:title>Probe</dc:title></metadata><manifest>
            <item id="all" href="book.xhtml" media-type="application/xhtml+xml"/>
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            </manifest><spine toc="ncx"><itemref idref="all"/></spine></package>
        """.trimIndent(),
        "OEBPS/book.xhtml" to "<html><body><h1 id=\"c1\">Chapter One</h1><p>Body one.</p>" +
            "<h1 id=\"c2\">Chapter Two</h1><p>Body two.</p>" +
            "<h1 id=\"c3\">Chapter Three</h1><p>Body three.</p></body></html>",
        "OEBPS/toc.ncx" to """
            <ncx><navMap>
            <navPoint id="a"><navLabel><text>Chapter One</text></navLabel><content src="book.xhtml#c1"/></navPoint>
            <navPoint id="b"><navLabel><text>Chapter Two</text></navLabel><content src="book.xhtml#c2"/></navPoint>
            <navPoint id="c"><navLabel><text>Chapter Three</text></navLabel><content src="book.xhtml#c3"/></navPoint>
            </navMap></ncx>
        """.trimIndent(),
    )

    @Test
    fun chaptersMarkedByAnchorsInOneDocumentKeepTheirOwnPlaces() {
        val parsed = EpubParser.parse(epub(singleDocumentBook, blob = null).inputStream())
        assertEquals(listOf("Chapter One", "Chapter Two", "Chapter Three"), parsed.chapters.map { it.title })
        // Without the anchors all three would share the offset the one document starts at.
        assertEquals(listOf(0, parsed.text.indexOf("Chapter Two"), parsed.text.indexOf("Chapter Three")),
            parsed.chapters.map { it.start })
        assertTrue(parsed.chapters.all { parsed.text.startsWith(it.title, it.start) })
    }

    @Test
    fun anchorsLeaveNoMarkBehindInTheText() {
        val parsed = EpubParser.parse(epub(singleDocumentBook, blob = null).inputStream())
        assertEquals("Chapter One\n\nBody one.\n\nChapter Two\n\nBody two.\n\nChapter Three\n\nBody three.", parsed.text)
    }

    /** A book whose contents group its chapters into parts, as an NCX nests them. */
    private val nestedNcxBook = listOf(
        "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
        "OEBPS/content.opf" to """
            <package><metadata><dc:title>Probe</dc:title></metadata><manifest>
            <item id="p1" href="part1.xhtml" media-type="application/xhtml+xml"/>
            <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
            <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
            <item id="c3" href="c3.xhtml" media-type="application/xhtml+xml"/>
            <item id="c4" href="c4.xhtml" media-type="application/xhtml+xml"/>
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            </manifest><spine toc="ncx"><itemref idref="p1"/><itemref idref="c1"/><itemref idref="c2"/>
            <itemref idref="c3"/><itemref idref="c4"/></spine></package>
        """.trimIndent(),
        "OEBPS/part1.xhtml" to "<html><body><h1>Part I</h1><p>Where it begins.</p></body></html>",
        "OEBPS/c1.xhtml" to "<html><body><h2>One</h2><p>Body one.</p></body></html>",
        "OEBPS/c2.xhtml" to "<html><body><h2>Two</h2><p>Body two.</p></body></html>",
        "OEBPS/c3.xhtml" to "<html><body><h2>Three</h2><p>Body three.</p></body></html>",
        "OEBPS/c4.xhtml" to "<html><body><h2>Four</h2><p>Body four.</p></body></html>",
        "OEBPS/toc.ncx" to """
            <ncx><navMap>
            <navPoint id="p1" playOrder="1"><navLabel><text>Part I</text></navLabel><content src="part1.xhtml"/>
              <navPoint id="c1" playOrder="2"><navLabel><text>Chapter 1</text></navLabel><content src="c1.xhtml"/></navPoint>
              <navPoint id="c2" playOrder="3"><navLabel><text>Chapter 2</text></navLabel><content src="c2.xhtml"/></navPoint>
              <navPoint id="c3" playOrder="4"><navLabel><text>Chapter 3</text></navLabel><content src="c3.xhtml"/></navPoint>
            </navPoint>
            <navPoint id="c4" playOrder="5"><navLabel><text>Chapter 4</text></navLabel><content src="c4.xhtml"/></navPoint>
            </navMap></ncx>
        """.trimIndent(),
    )

    @Test
    fun nestedNcxEntriesEachKeepTheirOwnLabelAndPlace() {
        val parsed = EpubParser.parse(epub(nestedNcxBook, blob = null).inputStream())
        assertEquals(listOf("Part I", "Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4"), parsed.chapters.map { it.title })
        assertEquals(
            listOf("Part I", "One", "Two", "Three", "Four").map { parsed.text.indexOf(it) },
            parsed.chapters.map { it.start },
        )
    }

    @Test
    fun aDocumentOpeningWithAnchoredWrappersStartsOnItsText() {
        val (text, anchors) = EpubParser.textWithAnchors(
            "<html><body id=\"top\">\n  <div id=\"t\"><h1>My Book</h1>\n  <p>First.</p> <span id=\"s\"> </span> <h2 id=\"c\">Next</h2></div></body></html>",
        )
        assertEquals("My Book\n\nFirst.\n\nNext", text)
        assertEquals(0, anchors["top"])
        assertEquals(0, anchors["t"])
        assertEquals(text.indexOf("Next"), anchors["c"])
        assertEquals(text.indexOf("Next"), anchors["s"])
    }

    @Test
    fun chapterOffsetsSurviveAFirstDocumentThatStartsWithWhitespace() {
        val book = singleDocumentBook.map { (name, content) ->
            if (name == "OEBPS/book.xhtml") {
                name to content.replace("<body>", "<body id=\"top\">\n   <div id=\"wrap\">  ").replace("</body>", "</div></body>")
            } else name to content
        }
        val parsed = EpubParser.parse(epub(book, blob = null).inputStream())
        assertTrue(parsed.text.startsWith("Chapter One"))
        assertTrue(parsed.chapters.all { parsed.text.startsWith(it.title, it.start) })
    }

    @Test
    fun onlyTheRealIdAttributeNamesAnAnchor() {
        assertEquals("real", EpubParser.attr("""<h1 data-id="decoy" id="real">""", "id"))
        assertEquals(null, EpubParser.attr("""<h1 data-id="decoy" xml:id="other">""", "id"))
        assertEquals("x", EpubParser.attr("""<a id='x' href="y">""", "id"))
        val (text, anchors) = EpubParser.textWithAnchors("""<p>Intro.</p><h1 data-id="decoy" id="c2">Two</h1>""")
        assertEquals(text.indexOf("Two"), anchors["c2"])
        assertNull(anchors["decoy"])
    }

    @Test
    fun rubyReadingsAreLeftOutOfTheText() {
        assertEquals(
            "\u543e\u8f29\u306f\u732b\u3067\u3042\u308b\u3002",
            EpubParser.htmlToText(
                "<p><ruby>\u543e\u8f29<rp>(</rp><rt>\u308f\u304c\u306f\u3044</rt><rp>)</rp></ruby>\u306f" +
                    "<ruby>\u732b<RT>\u306d\u3053</RT></ruby>\u3067\u3042\u308b\u3002</p>",
            ),
        )
    }

    private fun withEncryption(algorithm: String, uri: String): List<Pair<String, String>> = navBook + (
        "META-INF/encryption.xml" to """
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
              <enc:EncryptedData>
                <enc:EncryptionMethod Algorithm="$algorithm"/>
                <enc:CipherData><enc:CipherReference URI="$uri"/></enc:CipherData>
              </enc:EncryptedData>
            </encryption>
        """.trimIndent()
    )

    @Test
    fun aCopyProtectedBookIsRefused() {
        val bytes = epub(withEncryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc", "OEBPS/b.xhtml"), blob = null)
        val refusal = assertThrows(IllegalArgumentException::class.java) { EpubParser.parse(bytes.inputStream()) }
        assertTrue(refusal.message!!.contains("copy-protected"))
    }

    @Test
    fun obfuscatedFontsDoNotMakeABookProtected() {
        for (algorithm in listOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")) {
            val bytes = epub(withEncryption(algorithm, "OEBPS/fonts/serif.otf"), blob = null)
            assertTrue(EpubParser.parse(bytes.inputStream()).text.contains("Body B."))
        }
        // Protection that covers only a picture leaves the text readable too.
        val picture = epub(withEncryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc", "OEBPS/cover.jpg"), blob = null)
        assertTrue(EpubParser.parse(picture.inputStream()).text.contains("Body A."))
    }

    // ---- older and sloppier books ----

    /** An archive of [files] exactly as given, bytes and names alike. */
    private fun zip(files: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun utf8(vararg files: Pair<String, String>): List<Pair<String, ByteArray>> =
        files.map { (name, text) -> name to text.toByteArray(Charsets.UTF_8) }

    private fun containerFor(path: String) = "META-INF/container.xml" to
        """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
        """<rootfiles><rootfile full-path="$path" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    /** An EPUB 2 book the way older tools wrote it: prefixed tags and pages labelled every which way. */
    private val oldStyleBook = utf8(
        "mimetype" to "application/epub+zip",
        containerFor("OEBPS/content.opf"),
        "OEBPS/content.opf" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <opf:package xmlns:opf="http://www.idpf.org/2007/opf" version="2.0">
              <opf:metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:Title>An Old Book</dc:Title>
                <opf:meta name='cover' content='cover-pic'/>
              </opf:metadata>
              <opf:manifest>
                <opf:item id="ncx" href="toc.ncx" media-type="text/xml"/>
                <opf:item id="c1" href="one.html" media-type="text/html"/>
                <opf:item id="c2" href="two.htm" media-type="application/octet-stream"/>
                <opf:item id="c3" href="three.html" media-type="text/x-oeb1-document"/>
                <opf:item id="cover-pic" href="cover.jpg" media-type="image/jpeg"/>
              </opf:manifest>
              <opf:spine toc="ncx"><opf:itemref idref="c1"/><opf:itemref idref="c2"/><opf:itemref idref="c3"/></opf:spine>
            </opf:package>
        """.trimIndent(),
        "OEBPS/toc.ncx" to """
            <ncx:ncx xmlns:ncx="http://www.daisy.org/z3986/2005/ncx/"><ncx:navMap>
            <ncx:navPoint id="p1"><ncx:navLabel><ncx:text>
                The First
                Chapter
            </ncx:text></ncx:navLabel><ncx:content src='one.html'/></ncx:navPoint>
            <ncx:navPoint id="p2"><ncx:navLabel><ncx:text><![CDATA[Second & last but one]]></ncx:text></ncx:navLabel><ncx:content src="two.htm"/></ncx:navPoint>
            <ncx:navPoint id="p3"><ncx:navLabel><ncx:text>Third</ncx:text></ncx:navLabel><ncx:content src="three.html"/></ncx:navPoint>
            </ncx:navMap></ncx:ncx>
        """.trimIndent(),
        "OEBPS/one.html" to "<HTML><HEAD><TITLE>x</TITLE></HEAD><BODY><H1>One</H1><P>Body one.</P></BODY></HTML>",
        "OEBPS/two.htm" to "<html><body><h1>Two</h1><p>Body two.</p></body></html>",
        "OEBPS/three.html" to "<html><body><h1>Three</h1><p>Body three.</p></body></html>",
        "OEBPS/cover.jpg" to "not really a picture",
    )

    @Test
    fun anOldStyleEpub2BookIsReadWithItsNcxContents() {
        val parsed = EpubParser.parse(zip(oldStyleBook))
        assertEquals("An Old Book", parsed.title)
        assertEquals("One\n\nBody one.\n\nTwo\n\nBody two.\n\nThree\n\nBody three.", parsed.text)
        assertEquals(listOf("The First Chapter", "Second & last but one", "Third"), parsed.chapters.map { it.title })
        assertEquals(listOf("One", "Two", "Three").map { parsed.text.indexOf(it) }, parsed.chapters.map { it.start })
        assertEquals("not really a picture", parsed.cover?.toString(Charsets.UTF_8))
    }

    @Test
    fun anNcxLeftOutOfTheManifestIsStillFound() {
        val book = oldStyleBook.map { (name, bytes) ->
            if (name.endsWith(".opf")) {
                name to bytes.toString(Charsets.UTF_8).replace("""<opf:item id="ncx" href="toc.ncx" media-type="text/xml"/>""", "")
                    .replace("""toc="ncx"""", "").toByteArray()
            } else name to bytes
        }
        assertEquals(listOf("The First Chapter", "Second & last but one", "Third"), EpubParser.parse(zip(book)).chapters.map { it.title })
    }

    @Test
    fun thePackageIsFoundWhereverTheContainerPointsOrIfThereIsNone() {
        val moved = oldStyleBook.map { (name, bytes) -> name.replace("content.opf", "Content.OPF") to bytes }
        for (fullPath in listOf("/OEBPS/content.opf", "OEBPS\\Content.OPF", "./OEBPS/./Content.opf", "OEBPS/missing.opf", null)) {
            val files = moved.mapNotNull { (name, bytes) ->
                when {
                    name != "META-INF/container.xml" -> name to bytes
                    fullPath == null -> null
                    else -> containerFor(fullPath).let { it.first to it.second.toByteArray() }
                }
            }
            val parsed = EpubParser.parse(zip(files))
            assertEquals(fullPath.toString(), "An Old Book", parsed.title)
            assertEquals(3, parsed.chapters.size)
        }
    }

    @Test
    fun theMimetypeEntryIsNeitherNeededNorChecked() {
        val missing = oldStyleBook.filterNot { it.first == "mimetype" }
        assertEquals(3, EpubParser.parse(zip(missing)).chapters.size)
        val wrong = oldStyleBook.map { (name, bytes) -> if (name == "mimetype") name to "application/zip\n".toByteArray() else name to bytes }
        assertEquals(3, EpubParser.parse(zip(wrong)).chapters.size)
    }

    /**
     * An archive whose entries all carry their sizes after the data, as some EPUB tools write
     * them; the mimetype is stored uncompressed that way, which a stream reader refuses outright.
     */
    private fun zipWithTrailingSizes(files: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        val directory = ByteArrayOutputStream()
        fun le(stream: ByteArrayOutputStream, value: Long, bytes: Int) {
            for (i in 0 until bytes) stream.write(((value shr (8 * i)) and 0xFF).toInt())
        }
        for ((name, content) in files) {
            val stored = name == "mimetype"
            val data = if (stored) content else {
                val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
                deflater.setInput(content)
                deflater.finish()
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (!deflater.finished()) buffer.write(chunk, 0, deflater.deflate(chunk))
                buffer.toByteArray()
            }
            val crc = java.util.zip.CRC32().apply { update(content) }.value
            val method = if (stored) 0L else 8L
            val nameBytes = name.toByteArray()
            val offset = out.size().toLong()
            le(out, 0x04034b50, 4); le(out, 20, 2); le(out, 0x0808, 2); le(out, method, 2); le(out, 0, 4)
            le(out, 0, 4); le(out, 0, 4); le(out, 0, 4); le(out, nameBytes.size.toLong(), 2); le(out, 0, 2)
            out.write(nameBytes)
            out.write(data)
            le(out, 0x08074b50, 4); le(out, crc, 4); le(out, data.size.toLong(), 4); le(out, content.size.toLong(), 4)
            le(directory, 0x02014b50, 4); le(directory, 20, 2); le(directory, 20, 2); le(directory, 0x0808, 2)
            le(directory, method, 2); le(directory, 0, 4); le(directory, crc, 4); le(directory, data.size.toLong(), 4)
            le(directory, content.size.toLong(), 4); le(directory, nameBytes.size.toLong(), 2); le(directory, 0, 2)
            le(directory, 0, 2); le(directory, 0, 2); le(directory, 0, 2); le(directory, 0, 4); le(directory, offset, 4)
            directory.write(nameBytes)
        }
        val start = out.size().toLong()
        out.write(directory.toByteArray())
        le(out, 0x06054b50, 4); le(out, 0, 2); le(out, 0, 2); le(out, files.size.toLong(), 2); le(out, files.size.toLong(), 2)
        le(out, directory.size().toLong(), 4); le(out, start, 4); le(out, 0, 2)
        return out.toByteArray()
    }

    @Test
    fun anArchiveAStreamReaderCannotStepThroughIsReadByItsDirectory() {
        val bytes = zipWithTrailingSizes(oldStyleBook)
        assertThrows(java.util.zip.ZipException::class.java) {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { while (it.nextEntry != null) it.readBytes() }
        }
        val parsed = EpubParser.parse(bytes)
        assertEquals("An Old Book", parsed.title)
        assertEquals("One\n\nBody one.\n\nTwo\n\nBody two.\n\nThree\n\nBody three.", parsed.text)
    }

    @Test
    fun aDamagedArchiveIsRefusedInWords() {
        val bytes = zipWithTrailingSizes(oldStyleBook)
        val refusal = assertThrows(IllegalArgumentException::class.java) { EpubParser.parse(bytes.copyOf(bytes.size - 30)) }
        assertTrue(refusal.message!!.contains("damaged"))
    }

    @Test
    fun anArchiveThatNamesItsFilesInGbkIsRead() {
        val files = utf8(
            containerFor("content.opf"),
            "content.opf" to """
                <package><metadata><dc:title>红楼梦</dc:title></metadata><manifest>
                <item id="a" href="第一回.xhtml" media-type="application/xhtml+xml"/>
                </manifest><spine><itemref idref="a"/></spine></package>
            """.trimIndent(),
            "第一回.xhtml" to "<html><body><h1>第一回</h1><p>甄士隐梦幻识通灵</p></body></html>",
        )
        // Written the way a Chinese Windows zip tool writes it: names in GBK, and no UTF-8 flag.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, charset("GBK")).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        assertEquals("第一回\n\n甄士隐梦幻识通灵", EpubParser.parse(out.toByteArray()).text)
    }

    @Test
    fun linksFindTheirFilesWhateverTheirCaseOrEncoding() {
        val bytes = zip(utf8(
            containerFor("OEBPS/content.opf"),
            "OEBPS/content.opf" to """
                <package><metadata><dc:title>Probe</dc:title></metadata><manifest>
                <item id="a" href="Text/Chapter%20One.XHTML" media-type="application/xhtml+xml"/>
                <item id="b" href="Text/second part.xhtml" media-type="application/xhtml+xml"/>
                <item id="c" href="Text\third.xhtml" media-type="application/xhtml+xml"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                </manifest><spine toc="ncx"><itemref idref="a"/><itemref idref="b"/><itemref idref="c"/></spine></package>
            """.trimIndent(),
            "OEBPS/toc.ncx" to """
                <ncx><navMap>
                <navPoint><navLabel><text>One</text></navLabel><content src="TEXT/chapter%20one.xhtml#Start"/></navPoint>
                <navPoint><navLabel><text>Two</text></navLabel><content src="Text/second%20part.xhtml"/></navPoint>
                <navPoint><navLabel><text>Three</text></navLabel><content src="text/Third.xhtml#c%203"/></navPoint>
                </navMap></ncx>
            """.trimIndent(),
            // The archive keeps one name in lower case, one percent-encoded and one with a space.
            "OEBPS/text/chapter one.xhtml" to "<html><body><p>Front matter.</p><h1 id=\"Start\">Chapter one</h1><p>Body one.</p></body></html>",
            "OEBPS/Text/second%20part.xhtml" to "<html><body><h1>Chapter two</h1><p>Body two.</p></body></html>",
            "OEBPS/Text/third.xhtml" to "<html><body><p>Lead-in.</p><h1 id=\"c 3\">Chapter three</h1><p>Body three.</p></body></html>",
        ))
        val parsed = EpubParser.parse(bytes)
        assertTrue(parsed.text.contains("Body one.") && parsed.text.contains("Body two.") && parsed.text.contains("Body three."))
        assertEquals(listOf("One", "Two", "Three"), parsed.chapters.map { it.title })
        assertEquals(
            listOf("Chapter one", "Chapter two", "Chapter three").map { parsed.text.indexOf(it) },
            parsed.chapters.map { it.start },
        )
    }

    @Test
    fun htmlEntitiesWithoutADtdAreDecoded() {
        assertEquals(
            "a b — c… été © ½ x² “q” αβ – €",
            EpubParser.htmlToText(
                "<p>a&nbsp;b &mdash; c&hellip; &eacute;t&eacute; &copy; &frac12; x&sup2; &ldquo;q&rdquo; &alpha;&beta; &#150; &#128;</p>",
            ),
        )
        // An unknown name is left as it was rather than lost.
        assertEquals("&bogus; stays", EpubParser.decodeEntities("&bogus; stays"))
    }

    private fun bookWithPage(page: ByteArray): ByteArray = zip(
        utf8(
            containerFor("content.opf"),
            "content.opf" to """
                <package><metadata><dc:title>Probe</dc:title></metadata><manifest>
                <item id="p" href="page.html" media-type="application/xhtml+xml"/>
                </manifest><spine><itemref idref="p"/></spine></package>
            """.trimIndent(),
        ) + ("page.html" to page),
    )

    @Test
    fun pagesInTheEncodingTheyDeclareAreRead() {
        val samples = listOf(
            "windows-1251" to "Глава первая. Вечером над рекой поднялся туман, и старый лодочник долго смотрел на огни.",
            "windows-1250" to "Kapitola první. Ráno bylo chladné a nad řekou ležela mlha, převozník se díval na břeh.",
            "ISO-8859-2" to "Rozdział pierwszy. Wieczorem nad jeziorem zapadła cisza, a księżyc wyłonił się zza drzew.",
            "KOI8-R" to "Глава вторая. Утром туман рассеялся, и над рекой взошло солнце.",
            "GBK" to "第一回 甄士隐梦幻识通灵，贾雨村风尘怀闺秀。",
            "Big5" to "第一回 甄士隱夢幻識通靈，賈雨村風塵懷閨秀。",
            "Shift_JIS" to "吾輩は猫である。名前はまだ無い。",
        )
        for ((charset, text) in samples) {
            val xml = """<?xml version="1.0" encoding="$charset"?><html><body><p>$text</p></body></html>"""
            assertEquals(charset, text, EpubParser.parse(bookWithPage(xml.toByteArray(charset(charset)))).text)
            val html = """<html><head><meta http-equiv="Content-Type" content="text/html; charset=$charset"></head><body><p>$text</p></body></html>"""
            assertEquals(charset, text, EpubParser.parse(bookWithPage(html.toByteArray(charset(charset)))).text)
        }
    }

    @Test
    fun aPageThatClaimsUtf8WithoutBeingItIsStillRead() {
        val text = "第一回 甄士隐梦幻识通灵，贾雨村风尘怀闺秀。此开卷第一回也。"
        val page = """<?xml version="1.0" encoding="utf-8"?><html><body><p>$text</p></body></html>"""
        assertEquals(text, EpubParser.parse(bookWithPage(page.toByteArray(charset("GBK")))).text)
        val cyrillic = "Вечером над рекой поднялся туман, и старый лодочник долго смотрел на огни деревни."
        val untagged = "<html><body><p>$cyrillic</p></body></html>"
        assertEquals(cyrillic, EpubParser.parse(bookWithPage(untagged.toByteArray(charset("windows-1251")))).text)
    }

    @Test
    fun spinePagesTheContentsLeaveOutAreStillRead() {
        // The contents skip the interlude and name a page that is not in the spine at all.
        val bytes = zip(utf8(
            containerFor("OEBPS/content.opf"),
            "OEBPS/content.opf" to """
                <package><metadata><dc:title>Probe</dc:title></metadata><manifest>
                <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
                <item id="i" href="interlude.xhtml" media-type="application/xhtml+xml"/>
                <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                </manifest><spine toc="ncx"><itemref idref="a"/><itemref idref="i"/><itemref idref="a"/><itemref idref="b"/></spine></package>
            """.trimIndent(),
            "OEBPS/toc.ncx" to """
                <ncx><navMap>
                <navPoint><navLabel><text>A</text></navLabel><content src="a.xhtml"/></navPoint>
                <navPoint><navLabel><text>Notes</text></navLabel><content src="notes.xhtml"/></navPoint>
                <navPoint><navLabel><text>B</text></navLabel><content src="b.xhtml"/></navPoint>
                </navMap></ncx>
            """.trimIndent(),
            "OEBPS/a.xhtml" to "<html><body><h1>Heading A</h1><p>Body A.</p></body></html>",
            "OEBPS/interlude.xhtml" to "<html><body><p>An interlude no contents mention.</p></body></html>",
            "OEBPS/b.xhtml" to "<html><body><h1>Heading B</h1><p>Body B.</p></body></html>",
        ))
        val parsed = EpubParser.parse(bytes)
        // Each page once, the one the spine named twice included.
        assertEquals("Heading A\n\nBody A.\n\nAn interlude no contents mention.\n\nHeading B\n\nBody B.", parsed.text)
        assertEquals(listOf("A", "B"), parsed.chapters.map { it.title })
        assertEquals(listOf(0, parsed.text.indexOf("Heading B")), parsed.chapters.map { it.start })
    }

    /** A long chapter Calibre split in two, with contents made before the split. */
    private fun splitBook(toc: String) = zip(utf8(
        containerFor("content.opf"),
        "content.opf" to """
            <package><metadata><dc:title>Probe</dc:title></metadata><manifest>
            <item id="s0" href="index_split_000.html" media-type="application/xhtml+xml"/>
            <item id="s1" href="index_split_001.html" media-type="application/xhtml+xml"/>
            <item id="s2" href="index_split_002.html" media-type="application/xhtml+xml"/>
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            </manifest><spine toc="ncx"><itemref idref="s0"/><itemref idref="s1"/><itemref idref="s2"/></spine></package>
        """.trimIndent(),
        "toc.ncx" to "<ncx><navMap>$toc</navMap></ncx>",
        "index_split_000.html" to "<html><body><h2 id=\"c1\">Chapter One</h2><p>It began on a Tuesday.</p></body></html>",
        "index_split_001.html" to "<html><body><p>and went on well into Wednesday.</p></body></html>",
        "index_split_002.html" to "<html><body><h2 id=\"c2\">Chapter Two</h2><p>Thursday was quieter.</p></body></html>",
    ))

    @Test
    fun aSplitFileIsNoChapterOfItsOwn() {
        val parsed = EpubParser.parse(splitBook(toc = ""))
        assertEquals(listOf("Chapter One", "Chapter Two"), parsed.chapters.map { it.title })
    }

    @Test
    fun contentsMadeBeforeASplitStillFindTheirChapters() {
        val toc = """
            <navPoint><navLabel><text>One</text></navLabel><content src="index.html#c1"/></navPoint>
            <navPoint><navLabel><text>Two</text></navLabel><content src="index_split_000.html#c2"/></navPoint>
        """
        val parsed = EpubParser.parse(splitBook(toc))
        assertEquals(listOf("One", "Two"), parsed.chapters.map { it.title })
        assertEquals(listOf(0, parsed.text.indexOf("Chapter Two")), parsed.chapters.map { it.start })
    }

    @Test
    fun namedAnchorsPlaceChaptersLikeIds() {
        val book = singleDocumentBook.map { (name, content) ->
            if (name == "OEBPS/book.xhtml") {
                name to content.replace("<h1 id=\"c1\">", "<a name=\"c1\"></a><h1>").replace("<h1 id=\"c2\">", "<a name=\"c2\"></a><h1>")
                    .replace("<h1 id=\"c3\">", "<A NAME='c3'></A><h1>")
            } else name to content
        }
        val parsed = EpubParser.parse(epub(book, blob = null).inputStream())
        assertEquals(listOf(0, parsed.text.indexOf("Chapter Two"), parsed.text.indexOf("Chapter Three")), parsed.chapters.map { it.start })
    }

    @Test
    fun theAuthorComesFromThePackagesCreator() {
        val files = navBook.map { (name, text) ->
            name to if (name == "OEBPS/content.opf") text.replace(
                "<dc:title>Probe</dc:title>",
                """<dc:title>Probe</dc:title><dc:creator opf:role="aut" opf:file-as="Austen, Jane">Jane Austen</dc:creator>""",
            ) else text
        }
        assertEquals("Jane Austen", EpubParser.parse(epub(files, blob = null).inputStream()).author)
        // A book that names nobody has no author.
        assertEquals("", EpubParser.parse(epub(navBook, blob = null).inputStream()).author)
    }

    @Test
    fun severalAuthorsAreJoinedAndOtherRolesLeftOut() {
        // EPUB 2: the role is the element's own opf:role, under whatever prefix the tool chose.
        val epub2 = """
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
              <dc:creator opf:role="aut">Jane Austen</dc:creator>
              <dc:creator opf:role="ill">Hugh Thomson</dc:creator>
              <dc:creator ns0:role='AUT'>Anna Austen</dc:creator>
              <dc:contributor opf:role="edt">An Editor</dc:contributor>
            </metadata>
        """
        assertEquals("Jane Austen, Anna Austen", EpubParser.authors(epub2))
        // EPUB 3: a meta refines the creator's id; a creator with no role is the author.
        val epub3 = """
            <metadata>
              <dc:creator id="a1">Лев Толстой</dc:creator>
              <meta refines="#a1" property="role" scheme="marc:relators">aut</meta>
              <dc:creator id="t1">Constance Garnett</dc:creator>
              <meta property="role" refines="#t1" scheme="marc:relators">trl</meta>
              <meta refines="#t1" property="file-as">Garnett, Constance</meta>
              <dc:creator id="a2">Second
                 Author</dc:creator>
              <meta name="cover" content="cover-image"/>
            </metadata>
        """
        assertEquals("Лев Толстой, Second Author", EpubParser.authors(epub3))
    }

    @Test
    fun authorsAreReadAsText() {
        assertEquals("Smith & Sons", EpubParser.authors("<metadata><creator>Smith &amp; Sons</creator></metadata>"))
        // The same name twice, and an empty creator, are not repeated or kept.
        assertEquals(
            "Multatuli",
            EpubParser.authors("<metadata><dc:creator>Multatuli</dc:creator><dc:creator/><dc:creator> </dc:creator><dc:creator>Multatuli</dc:creator></metadata>"),
        )
        assertEquals("", EpubParser.authors("<metadata><dc:title>No one</dc:title></metadata>"))
    }
}
