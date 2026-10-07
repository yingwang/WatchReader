package com.watchreader.mobile.data.repository

import com.watchreader.mobile.data.model.FreeBook
import com.watchreader.mobile.data.model.FreeBookFile
import com.watchreader.mobile.data.model.FreeBookPage
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Project Gutenberg's catalogue as Gutendex (gutendex.com) serves it: the addresses asked for and
 * the JSON that comes back. Nothing here touches the network, so all of it is tested on the JVM.
 */
internal object Gutendex {
    const val BOOKS = "https://gutendex.com/books/"

    /**
     * Languages offered as a filter: those with more than a handful of books on Gutenberg, and a
     * few more that many phones are set to. A phone set to another language starts on all of them.
     */
    val LANGUAGES = listOf(
        "ca", "cs", "cy", "da", "de", "el", "en", "eo", "es", "fi", "fr", "hu",
        "it", "ja", "la", "nl", "no", "pl", "pt", "ru", "sv", "tl", "zh",
    )

    /** The filter a phone in [locale] starts on: its own language when Gutenberg has it, else null for all. */
    fun languageFor(locale: Locale): String? {
        val code = when (val language = locale.language) {
            // Gutenberg tags Norwegian without telling Bokmål from Nynorsk, and Filipino as Tagalog.
            "nb", "nn" -> "no"
            "fil" -> "tl"
            else -> language
        }
        return code.takeIf { it in LANGUAGES }
    }

    /**
     * The first page of the most downloaded books, or of those matching [search], in [language]
     * or in every language when it is null. Gutendex sorts by downloads unless told otherwise,
     * so the address says no more than it must, its parameters come in the order of Gutendex's
     * own next-page links, and the search is in lower case, as Gutendex pays case no attention.
     * A query it has not answered lately takes it a minute or more, while one anybody asked in
     * the last few hours comes back at once from a cache keyed on the address exactly as written.
     */
    fun pageUrl(search: String, language: String?): String {
        val params = buildList {
            if (language != null) add("languages=" + URLEncoder.encode(language, "UTF-8"))
            val words = search.trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT)
            if (words.isNotEmpty()) add("search=" + URLEncoder.encode(words, "UTF-8"))
        }
        return if (params.isEmpty()) BOOKS else BOOKS + "?" + params.joinToString("&")
    }

    /** A page of results; books that cannot be added here, or are not public domain, are left out. */
    fun parsePage(json: String): FreeBookPage {
        val root = JSONObject(json)
        val results = root.optJSONArray("results").objects()
        // Only Gutendex's own pages are followed, and only over https.
        val next = root.stringOrNull("next")?.let(BookRepository::secureAddress)?.takeIf { it.startsWith(BOOKS) }
        return FreeBookPage(results.mapNotNull { parseBook(it) }, next, root.optInt("count", results.size))
    }

    fun parseBook(o: JSONObject): FreeBook? {
        val id = o.optInt("id", -1)
        if (id <= 0) return null
        // Audio books and pictures are in the same catalogue, and none of them can be read here.
        if (o.optString("media_type", "Text") != "Text") return null
        // Gutenberg also carries some books still in copyright, with their holders' leave; the
        // screen says its books are public domain in the US, so those are not offered.
        if (o.opt("copyright") == true) return null
        val title = cleanTitle(o.optString("title"))
        if (title.isEmpty()) return null
        val formats = o.optJSONObject("formats")?.let { f ->
            f.keys().asSequence().mapNotNull { type -> f.stringOrNull(type)?.let { type to it } }.toMap()
        }.orEmpty()
        val files = filesFor(formats)
        if (files.isEmpty()) return null
        return FreeBook(
            id = id,
            title = title,
            authors = o.optJSONArray("authors").objects()
                .mapNotNull { it.stringOrNull("name")?.let { name -> displayName(name) } }
                .filter { it.isNotEmpty() },
            languages = o.optJSONArray("languages").strings(),
            subjects = o.optJSONArray("subjects").strings(),
            downloadCount = o.optInt("download_count", 0),
            coverUrl = formats.entries.firstOrNull { it.key.startsWith("image/") }?.value?.let(BookRepository::secureAddress),
            files = files,
        )
    }

    /**
     * Where to fetch a book, best first. The epub Gutendex lists is the one with every
     * illustration, and an illustrated novel runs past the 20 MB an import takes: Pride and
     * Prejudice is 25 MB that way and half a megabyte without. Gutenberg's epub without images
     * keeps the cover, the text and the chapters, so it goes first, with the listed epub behind
     * it in case it is missing. Plain text comes last: Gutenberg sends its own .txt.utf-8 link
     * on to a plain http:// address, which Android refuses, so the file it ends at is asked for
     * over https instead.
     */
    fun filesFor(formats: Map<String, String>): List<FreeBookFile> {
        val files = mutableListOf<FreeBookFile>()
        formats.entries.firstOrNull { it.key.startsWith("application/epub+zip") }?.let { (_, link) ->
            val epub = BookRepository.secureAddress(link)
            GUTENBERG_EPUB.matchEntire(epub)?.let { files += FreeBookFile(gutenberg("ebooks/${it.groupValues[1]}.epub.noimages"), isEpub = true) }
            files += FreeBookFile(epub, isEpub = true)
        }
        formats.entries
            .filter { (type, link) -> type.startsWith("text/plain") && !link.endsWith(".zip") }
            .sortedBy { (type, _) -> if (type.contains("utf-8", ignoreCase = true)) 0 else 1 }
            .forEach { (_, link) ->
                val text = BookRepository.secureAddress(link)
                val direct = GUTENBERG_TEXT.matchEntire(text)?.groupValues?.get(1)?.let { gutenberg("cache/epub/$it/pg$it.txt") }
                files += FreeBookFile(direct ?: text, isEpub = false)
            }
        return files.distinctBy { it.url }
    }

    /**
     * The catalogue's "Austen, Jane" as "Jane Austen". A part in brackets spells out initials
     * and is dropped. After a lowercase word, or a title such as "Emperor of Rome", the rest is
     * a description rather than a given name, and the name is kept as it stands before it.
     */
    fun displayName(name: String): String {
        val parts = name.replace(BRACKETED, "").split(',')
            .map { it.trim().replace(WHITESPACE, " ") }
            .filter { it.isNotEmpty() }
        if (parts.size < 2) return parts.firstOrNull().orEmpty()
        val given = parts[1]
        if (!given.first().isUpperCase() || given.contains(" of ")) return parts[0]
        val suffixes = parts.drop(2).filter { SUFFIX.matches(it) }
        return (listOf(given, parts[0]) + suffixes).joinToString(" ")
    }

    /**
     * A catalogue title as a reader would write it. Gutenberg puts a subtitle on a line of its
     * own, and some records keep the "$b" that library catalogues mark a subtitle with.
     */
    fun cleanTitle(title: String): String =
        title.replace(SUBTITLE_MARK, ": ")
            .replace(LINE_BREAK) { m -> m.groupValues[1].ifEmpty { ":" } + " " }
            .replace(WHITESPACE, " ")
            .trim()
            .removeSuffix(":")
            .trim()

    private fun gutenberg(path: String) = "https://www.gutenberg.org/$path"

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList()
        else (0 until length()).filterNot { isNull(it) }.map { optString(it).trim() }.filter { it.isNotEmpty() }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private val GUTENBERG_EPUB = Regex("""https://www\.gutenberg\.org/ebooks/(\d+)\.epub3?\.(?:no)?images""")
    private val GUTENBERG_TEXT = Regex("""https://www\.gutenberg\.org/ebooks/(\d+)\.txt\.utf-8""")
    private val WHITESPACE = Regex("""\s+""")
    private val BRACKETED = Regex("""\s*\([^)]*\)""")
    private val SUFFIX = Regex("""(?:Jr|Sr)\.?|[IVX]+""")
    private val SUBTITLE_MARK = Regex("""\s*:?\s*\${'$'}b\s*""")
    /** A line break, the spaces around it and any punctuation the line before it ends with. */
    private val LINE_BREAK = Regex("""[ \t]*([:;,.]?)[ \t]*[\r\n]+\s*""")
}
