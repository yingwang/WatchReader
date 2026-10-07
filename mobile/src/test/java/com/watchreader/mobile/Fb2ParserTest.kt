package com.watchreader.mobile

import com.watchreader.mobile.util.Fb2Parser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Fb2ParserTest {
    private val coverBytes = ByteArray(300) { (it * 7).toByte() }

    /** A small book with the parts an FB2 is made of, in the encoding [prolog] names. */
    private fun book(prolog: String = """<?xml version="1.0" encoding="UTF-8"?>""") = """
        $prolog
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
        <stylesheet type="text/css">p { text-indent: 1em }</stylesheet>
        <description>
         <title-info>
          <genre>prose_classic</genre>
          <author><first-name>Лев</first-name><middle-name>Николаевич</middle-name><last-name>Толстой</last-name></author>
          <author><nickname>Аноним</nickname></author>
          <book-title>Война и мир</book-title>
          <annotation><p>Роман-эпопея о войне.</p></annotation>
          <coverpage><image l:href="#cover.jpg"/></coverpage>
          <lang>ru</lang>
         </title-info>
         <document-info><author><nickname>Составитель</nickname></author><program-used>FB Editor</program-used></document-info>
        </description>
        <body>
         <title><p>Лев Толстой</p><p>Война и мир</p></title>
         <epigraph><p>Эпиграф ко всей книге.</p><text-author>Неизвестный</text-author></epigraph>
         <section>
          <title><p>Том первый</p></title>
          <section>
           <title><p>Часть первая</p><p>Глава I</p></title>
           <p>— Ну, князь, Генуя и Лукка стали не больше, как поместьями
              фамилии Бонапарте.</p>
           <p>Так говорила <emphasis>в июле 1805 года</emphasis> известная Анна Павловна Шерер<a l:href="#n1" type="note">[1]</a>.</p>
           <empty-line/>
           <subtitle>* * *</subtitle>
           <p>Князь Василий говорил всегда лениво, как актёр говорит роль старой пиесы.</p>
          </section>
          <section>
           <title><p>Глава II</p></title>
           <poem>
            <title><p>Песня</p></title>
            <stanza><v>Первая строка,</v><v>вторая строка.</v></stanza>
            <stanza><v>Третья строка.</v></stanza>
            <text-author>Народное</text-author>
           </poem>
           <cite><p>Цитата из письма.</p><text-author>Пьер</text-author></cite>
          </section>
         </section>
         <section>
          <p>Раздел без заглавия.</p>
         </section>
        </body>
        <body name="notes">
         <title><p>Примечания</p></title>
         <section id="n1"><title><p>1</p></title><p>Ну что, князь.</p></section>
        </body>
        <binary id="cover.jpg" content-type="image/jpeg">${Base64.getMimeEncoder().encodeToString(coverBytes)}</binary>
        </FictionBook>
    """.trimIndent()

    private val expectedText = """
        Лев Толстой
        Война и мир

        Эпиграф ко всей книге.

        Неизвестный

        Том первый

        Часть первая
        Глава I

        — Ну, князь, Генуя и Лукка стали не больше, как поместьями фамилии Бонапарте.

        Так говорила в июле 1805 года известная Анна Павловна Шерер[1].

        * * *

        Князь Василий говорил всегда лениво, как актёр говорит роль старой пиесы.

        Глава II

        Песня

        Первая строка,
        вторая строка.

        Третья строка.

        Народное

        Цитата из письма.

        Пьер

        Раздел без заглавия.

        Примечания

        1

        Ну что, князь.
    """.trimIndent()

    private fun assertWholeBook(parsed: Fb2Parser.Fb2) {
        assertEquals("Война и мир", parsed.title)
        assertEquals("Лев Николаевич Толстой, Аноним", parsed.author)
        assertEquals(expectedText, parsed.text)
        // Nested sections give the part and then its chapters, each where its title stands; the
        // notes come under one chapter, and the book's own heading and the untitled section none.
        assertEquals(listOf("Том первый", "Часть первая. Глава I", "Глава II", "Примечания"), parsed.chapters.map { it.title })
        assertEquals(
            listOf("Том первый", "Часть первая", "Глава II", "Примечания").map { parsed.text.indexOf(it) },
            parsed.chapters.map { it.start },
        )
        assertArrayEquals(coverBytes, parsed.cover)
    }

    @Test
    fun aBookReadsWithItsSectionsAsChapters() {
        val bytes = book().toByteArray(Charsets.UTF_8)
        assertTrue(Fb2Parser.looksLikeFb2(bytes))
        assertWholeBook(Fb2Parser.parse(bytes))
    }

    @Test
    fun windows1251NamedInThePrologIsRead() {
        val bytes = book("""<?xml version="1.0" encoding="windows-1251"?>""").toByteArray(charset("windows-1251"))
        assertTrue(Fb2Parser.looksLikeFb2(bytes))
        assertWholeBook(Fb2Parser.parse(bytes))
    }

    @Test
    fun windows1251WithNoEncodingNamedIsFoundAnyway() {
        val bytes = book("""<?xml version="1.0"?>""").toByteArray(charset("windows-1251"))
        assertWholeBook(Fb2Parser.parse(bytes))
        // A prolog that says UTF-8 about a Windows-1251 file is caught the same way.
        val mislabelled = book().toByteArray(charset("windows-1251"))
        assertWholeBook(Fb2Parser.parse(mislabelled))
    }

    @Test
    fun aBookInUtf16IsRecognised() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            book("""<?xml version="1.0" encoding="UTF-16"?>""").toByteArray(Charsets.UTF_16LE)
        assertTrue(Fb2Parser.looksLikeFb2(bytes))
        assertWholeBook(Fb2Parser.parse(bytes))
    }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
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

    @Test
    fun aZippedBookIsReadLikeAPlainOne() {
        val zipped = zip("Tolstoy - War and Peace.fb2" to book("""<?xml version="1.0" encoding="windows-1251"?>""").toByteArray(charset("windows-1251")))
        assertTrue(Fb2Parser.looksLikeFb2(zipped))
        assertWholeBook(Fb2Parser.parse(zipped))
    }

    @Test
    fun otherFilesAreNotTakenForFb2() {
        val epub = zip("mimetype" to "application/epub+zip".toByteArray(), "META-INF/container.xml" to "<container/>".toByteArray())
        assertFalse(Fb2Parser.looksLikeFb2(epub))
        // An EPUB is told apart by its META-INF folder even with no mimetype file in front.
        assertFalse(Fb2Parser.looksLikeFb2(zip("META-INF/container.xml" to "<container/>".toByteArray(), "x.fb2" to ByteArray(1))))
        assertFalse(Fb2Parser.looksLikeFb2("A plain book about <FictionBook> files.".toByteArray()))
        assertFalse(Fb2Parser.looksLikeFb2("""<?xml version="1.0"?><html><body>Not a book</body></html>""".toByteArray()))
        assertFalse(Fb2Parser.looksLikeFb2("Глава первая\n\nТекст.".toByteArray()))
    }

    @Test
    fun looseMarkupAndHtmlEntitiesAreForgiven() {
        val bytes = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook><description><title-info><book-title>Probe &amp; Co</book-title></title-info></description>
            <body><section><title><p>One</p></title>
            <p>Words&nbsp;and&mdash;more &laquo;quoted&raquo; &#x2116;5, a < b, <![CDATA[x <y> z]]></p>
            <p>An <emphasis>open emphasis</p>
            <p>After it.</p>
            </section></body></FictionBook>
        """.trimIndent().toByteArray()
        val parsed = Fb2Parser.parse(bytes)
        assertEquals("Probe & Co", parsed.title)
        assertEquals("One\n\nWords and—more «quoted» №5, a < b, x <y> z\n\nAn open emphasis\n\nAfter it.", parsed.text)
        assertEquals(listOf("One"), parsed.chapters.map { it.title })
        assertNull(parsed.cover)
    }

    @Test
    fun aBookWithoutTitledSectionsHasNoChaptersOfItsOwn() {
        val bytes = """
            <FictionBook><description><title-info><book-title>Untitled parts</book-title></title-info></description>
            <body><title><p>The Book</p></title><section><p>First part.</p></section><section><p>Second part.</p></section></body>
            </FictionBook>
        """.trimIndent().toByteArray()
        val parsed = Fb2Parser.parse(bytes)
        assertEquals("The Book\n\nFirst part.\n\nSecond part.", parsed.text)
        assertTrue(parsed.chapters.isEmpty())
    }
}
