package com.watchreader.shared

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Turns the bytes of a plain-text book into clean UTF-8 text.
 *
 * Detection order: byte-order mark, or the zero bytes of UTF-16 saved without one, then strict
 * UTF-8, then UTF-8 with a stray bad byte or two, then the East Asian encodings with GB18030 (a
 * superset of GBK, which is what most Chinese .txt files in the wild are saved as) preferred,
 * then the single-byte code pages of Europe, Windows-1252 unless another reads clearly better,
 * so that nothing is ever rejected. Line endings become "\n", runs of blank lines collapse to
 * one, and lines a Latin-script book was broken at for a fixed width are joined back into
 * paragraphs.
 */
object TextNormalizer {
    data class Decoded(val text: String, val charset: String)

    fun decode(bytes: ByteArray, declaredCharset: String? = null): Decoded {
        val (text, charset) = read(bytes, declaredCharset)
        return Decoded(unwrap(normalize(text)), charset)
    }

    private fun read(bytes: ByteArray, declaredCharset: String?): Pair<String, String> {
        bom(bytes)?.let { (charset, skip) ->
            return String(bytes, skip, bytes.size - skip, charset) to charset.name()
        }
        utf16WithoutBom(bytes)?.let { return String(bytes, it) to it.name() }
        val declared = declaredCharset?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?.let { if (it == Charsets.ISO_8859_1 || it == Charsets.US_ASCII) WINDOWS_1252 else it }
        // A single-byte charset decodes any bytes without complaint, so a server that still stamps
        // its old Latin-1 default on a UTF-8 file cannot be believed until strict UTF-8 has failed.
        val singleByte = declared != null && declared.newEncoder().maxBytesPerChar() <= 1f
        if (declared != null && !singleByte) strict(bytes, declared)?.let { return it to declared.name() }
        strict(bytes, Charsets.UTF_8)?.let { return it to "UTF-8" }
        mostlyUtf8(bytes)?.let { return it to "UTF-8" }
        if (declared != null && singleByte) {
            // Windows-1252 leaves five bytes undefined, and a stray one does not make the label wrong.
            val text = if (declared == WINDOWS_1252) String(bytes, declared) else strict(bytes, declared)
            text?.let { return it to declared.name() }
        }
        // A book in one of Europe's code pages fails every East Asian decoder on its first
        // accented letter followed by a space, but a short one can slip through GB18030 or
        // Shift_JIS, which then make a few odd characters of its accented letters. Such a reading
        // has hardly any everyday characters in it, and the single-byte reading wins when it
        // makes sense or when the East Asian one makes none.
        val eastAsian = eastAsian(bytes)
        if (eastAsian != null && eastAsian.score >= EVERYDAY_FLOOR) return eastAsian.text to eastAsian.charset
        val codePage = singleByte(bytes)
        if (eastAsian == null || codePage.convincing || eastAsian.score < EVERYDAY_NOISE) {
            return String(bytes, codePage.charset) to codePage.charset.name()
        }
        return eastAsian.text to eastAsian.charset
    }

    /** A reading by one of the East Asian encodings, and the share of everyday characters in it. */
    private class EastAsianReading(val text: String, val charset: String, val score: Double)

    /** An East Asian reading this full of everyday characters is taken without trying the code pages. */
    private const val EVERYDAY_FLOOR = 0.1

    /** Below this share an East Asian reading is noise: real Chinese or Japanese never gets this low. */
    private const val EVERYDAY_NOISE = 0.01

    /**
     * Web servers and old editors label Windows-1252 as ISO-8859-1, and browsers have long read
     * the one as the other. The two differ only in 0x80 to 0x9F, where Windows-1252 keeps its
     * curly quotes and dashes and ISO-8859-1 has invisible control codes, so reading every
     * single-byte Western file this way costs nothing and keeps a book's apostrophes.
     */
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    /**
     * A UTF-8 book with a byte or two gone bad, as a file cut short or a careless edit leaves it.
     * Strict decoding refuses the whole book over them, and every encoding tried after UTF-8
     * turns the rest of it into nonsense, so the stray bytes are dropped instead. What passes
     * for a stray byte is kept tight: a book in another encoding fails UTF-8 all the way
     * through, and a single-byte Western file that fails only a few times has no well-formed
     * UTF-8 characters in it to vouch for the reading.
     */
    private fun mostlyUtf8(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes)
        // UTF-8 never spends fewer bytes on a character than UTF-16 spends units.
        val output = CharBuffer.allocate(bytes.size)
        var malformed = 0
        while (true) {
            val result = decoder.decode(input, output, true)
            if (result.isUnderflow) break
            if (!result.isError) return null
            malformed++
            if (malformed > MAX_STRAY_BYTES && malformed * STRAY_PER_CHARS > bytes.size) return null
            input.position(input.position() + result.length())
        }
        decoder.flush(output)
        output.flip()
        var wellFormed = 0
        for (i in 0 until output.limit()) if (output[i].code >= 0x80) wellFormed++
        if (malformed > maxOf(MAX_STRAY_BYTES, output.limit() / STRAY_PER_CHARS)) return null
        return if (wellFormed > malformed) output.toString() else null
    }

    /** Bad sequences a UTF-8 book may carry and still be read as UTF-8, however short it is. */
    private const val MAX_STRAY_BYTES = 3

    /** Past the handful above, one bad sequence per this many characters at most. */
    private const val STRAY_PER_CHARS = 1000

    /**
     * GB18030 accepts nearly any pair of high bytes, so a Big5 or Shift_JIS file decodes under it
     * without complaint into characters nobody uses. Every candidate that decodes cleanly is
     * scored by how much of it is made of everyday characters, and another encoding displaces
     * GB18030 only when it reads clearly better, as a traditional Chinese or Japanese file does.
     */
    private fun eastAsian(bytes: ByteArray): EastAsianReading? {
        var best: EastAsianReading? = null
        for ((index, name) in EAST_ASIAN.withIndex()) {
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            val text = strict(bytes, charset) ?: continue
            val score = everyday(text)
            val margin = if (index == 0) 0.0 else CHALLENGER_MARGIN
            if (best == null || score > best.score + margin) best = EastAsianReading(text, charset.name(), score)
        }
        return best
    }

    private val EAST_ASIAN = listOf("GB18030", "Big5", "Shift_JIS")

    /** How much better than GB18030 another encoding has to read before it is believed. */
    private const val CHALLENGER_MARGIN = 0.15

    /** Only the front of a book is scored; a wrong encoding is wrong from the first line. */
    private const val SAMPLE_CHARS = 20_000

    /** The share of the letters that are everyday Chinese characters or Japanese kana. */
    private fun everyday(text: String): Double {
        var letters = 0
        var common = 0
        for (i in 0 until minOf(text.length, SAMPLE_CHARS)) {
            val c = text[i]
            if (!c.isLetter()) continue
            letters++
            if (c in COMMON || c.code in 0x3040..0x30FF) common++
        }
        return if (letters == 0) 0.0 else common.toDouble() / letters
    }

    /**
     * The most frequent Chinese characters, simplified and traditional. Genuine text is made of
     * them to a large degree; the same bytes read in the wrong encoding almost never are.
     */
    private val COMMON: Set<Char> = (
        "的一是不了在人有我他这个们中来上大为和国地到以说时要就出会可也你对生能而子那得于着下" +
        "自之年过发后作里用道行所然家种事成方多经么去法学如都同现当没动面起看定天分还进好小部" +
        "其些主样理心她本前开但因只从想实日军者意无力它与长把机十民第公此已工使情明性知全三又" +
        "关点正业外将两高间由问很最重并物手应战向头文体政美相见被利什二等产或新己制身果加西斯" +
        "月话合回特代内信表化老给世位次度门任常先海通教儿原东声提立及比员解水名真论处走义各入" +
        "几口认条平系气题活尔更别打女变四神总何电数安少报才结反受目太量再感建务做接必场件计管" +
        "期市直德资命山金指克许统区保至队形社便空决治展马科司五基眼书非则听白却界达光放强即像" +
        "难且权思王象完风雨花夜鸟闻" +
        "這個們來國時說會對於後種經麼學現當沒動還進點無與長機關開實軍將兩間問業體見產從發樣頭" +
        "裡義處變聲數氣認電條區隊書華際達員讓馬爾勢調風運車門東線遠總結決識標題務論給話統應戰" +
        "場計劃資許則聽權難稱為爲聞鳥"
    ).toHashSet()

    /** The code page a single-byte book was found to be in, and whether its reading makes sense. */
    private class SingleByteReading(val charset: Charset, val convincing: Boolean)

    /**
     * The single-byte code page a book reads best in. Every one of them decodes any bytes, so
     * each reading is judged instead, on its letters outside ASCII: in a book of the right code
     * page they are letters its language uses, set in words the way that language sets them; in
     * a wrong one they are symbols, letters from a neighbouring alphabet, or capitals in the
     * middle of words. Windows-1252 was the only fallback before the others were looked at, and
     * it stays first: another code page takes over only where it reads clearly better.
     *
     * Two code pages are compared only where they disagree: on the characters they read
     * differently, and on those they read alike but judge differently. Czech in Windows-1252
     * keeps its á, é and í, which every Western language has too, and loses ř, ě and č to ø, ì
     * and è, which no Western language writes together; judged on the whole book, the letters
     * both readings share would make the two look much alike. Slovene loses only its č, to the
     * è of French, and what gives it away is that French has no use for the š and ž around it.
     */
    private fun singleByte(bytes: ByteArray): SingleByteReading {
        // One byte is one character in all of these, so the readings line up position by position.
        val sample = if (bytes.size > SAMPLE_CHARS) bytes.copyOf(SAMPLE_CHARS) else bytes
        val readings = SINGLE_BYTE.mapNotNull { (name, alphabets) ->
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: return@mapNotNull null
            val text = String(sample, charset)
            CodePageReading(charset, text, if (alphabets == null) cyrillicVerdicts(text) else latinVerdicts(text, alphabets))
        }
        var best = readings.first()
        for (challenger in readings.drop(1)) {
            var bestFor = 0
            var bestAgainst = 0
            var challengerFor = 0
            var challengerAgainst = 0
            for (i in sample.indices) {
                if (best.text[i] == challenger.text[i] && best.verdicts[i] == challenger.verdicts[i]) continue
                when (best.verdicts[i]) { FOR -> bestFor++; AGAINST -> bestAgainst++ }
                when (challenger.verdicts[i]) { FOR -> challengerFor++; AGAINST -> challengerAgainst++ }
            }
            // A few stray symbols in an English book are no reason to read it as Polish: the
            // letters a code page is chosen for turn up all through a book written in it.
            val letters = challenger.text.count { it.isLetter() }
            if (challengerFor * MIN_EVIDENCE_SHARE < letters) continue
            if (share(challengerFor, challengerAgainst) > share(bestFor, bestAgainst) + CODE_PAGE_MARGIN) best = challenger
        }
        val inFavour = best.verdicts.count { it == FOR }
        val against = best.verdicts.count { it == AGAINST }
        val letters = best.text.count { it.isLetter() }
        val convincing = share(inFavour, against) >= CONVINCING && inFavour * MIN_EVIDENCE_SHARE >= letters
        return SingleByteReading(best.charset, convincing)
    }

    private class CodePageReading(val charset: Charset, val text: String, val verdicts: ByteArray)

    private fun share(inFavour: Int, against: Int): Double =
        if (inFavour + against == 0) 0.0 else inFavour.toDouble() / (inFavour + against)

    /** What a character says about the reading it came from. */
    private const val NEUTRAL: Byte = 0
    private const val FOR: Byte = 1
    private const val AGAINST: Byte = 2

    /** How much better another code page has to read before it is believed. */
    private const val CODE_PAGE_MARGIN = 0.1

    /** At most one letter in this many need be a code page's own for it to count as one in use. */
    private const val MIN_EVIDENCE_SHARE = 500

    /** A single-byte reading this sound is preferred to an East Asian one with few everyday characters. */
    private const val CONVINCING = 0.8

    /**
     * The code pages tried, each with the alphabets of the languages written in it; null stands
     * for Cyrillic, whose code pages differ in where they put the same letters rather than in
     * which letters they have. KOI8-U is KOI8-R with the letters Ukrainian adds, and a Russian
     * book reads the same in either, so the earlier keeps it.
     */
    private val SINGLE_BYTE: List<Pair<String, List<String>?>> = listOf(
        "windows-1252" to listOf(
            // French, German, Spanish, Portuguese, Italian, Dutch, Swedish, Danish and Norwegian,
            // Finnish, Icelandic, Catalan, Estonian
            "àâæçéèêëîïôœùûüÿ", "äöüß", "áéíñóúü", "áâãàçéêíóôõúü", "àèéìíîòóù", "éèëïöü",
            "åäöé", "æøåé", "äöåšž", "áðéíóúýþæö", "àçèéíïòóúü", "õäöüšž",
        ),
        "windows-1250" to listOf(
            // Czech, Slovak, Polish, Hungarian, Slovene and Croatian, Romanian
            "áčďéěíňóřšťúůýž", "áäčďéíĺľňóôŕšťúýž", "ąćęłńóśźż", "áéíóöőúüű", "čćđšž", "ăâîşţ",
        ),
        "windows-1251" to null,
        "KOI8-R" to null,
        "KOI8-U" to null,
    )

    /**
     * A Latin-script reading judged letter by letter against the one language among [alphabets]
     * that accounts for most of it. A letter outside ASCII counts for the reading when that
     * language has it and it stands in a word with plain letters beside it, as accented letters
     * do; the few words made of one or two such letters alone, French à and Hungarian ő among
     * them, count too when they are lower case. A Cyrillic book read this way is words made of
     * nothing but accented letters, and every one of them counts against it.
     */
    private fun latinVerdicts(text: String, alphabets: List<String>): ByteArray {
        val eligible = BooleanArray(text.length)
        forEachWord(text) { start, end ->
            var plain = false
            for (k in start until end) if (text[k].code < 0x80) plain = true
            val alone = !plain && end - start <= 2 &&
                (start == 0 || !text[start - 1].isDigit()) && (end == text.length || !text[end].isDigit())
            for (k in start until end) {
                if (text[k].code >= 0x80 && (plain || (alone && text[k].isLowerCase()))) eligible[k] = true
            }
        }
        val counts = IntArray(alphabets.size)
        for (i in text.indices) {
            if (!eligible[i]) continue
            val lower = text[i].lowercaseChar()
            for (n in alphabets.indices) if (lower in alphabets[n]) counts[n]++
        }
        val alphabet = alphabets[counts.indices.maxByOrNull { counts[it] } ?: 0]
        return ByteArray(text.length) { i ->
            val c = text[i]
            when {
                c.code < 0x80 -> NEUTRAL
                !isWordLetter(c) -> if (neutral(c)) NEUTRAL else AGAINST
                eligible[i] && c.lowercaseChar() in alphabet -> FOR
                else -> AGAINST
            }
        }
    }

    /**
     * A Cyrillic reading judged by its case. The Cyrillic code pages hold the same letters in
     * different places, Windows-1251 and KOI8 even swapping capitals for small letters, so a book
     * read in the wrong one is still all Cyrillic but runs in capitals from end to end. A letter
     * counts for the reading when it is small or opens its word, and when the word has no Latin
     * letters in it; a Czech or Polish book read this way has its accented letters turned
     * Cyrillic in the middle of Latin words.
     */
    private fun cyrillicVerdicts(text: String): ByteArray {
        val verdicts = ByteArray(text.length)
        for (i in text.indices) {
            val c = text[i]
            if (c.code >= 0x80 && !isWordLetter(c) && !neutral(c)) verdicts[i] = AGAINST
        }
        forEachWord(text) { start, end ->
            var latin = false
            for (k in start until end) if (Character.UnicodeScript.of(text[k].code) == Character.UnicodeScript.LATIN) latin = true
            for (k in start until end) {
                val c = text[k]
                if (c.code < 0x80) continue
                val cyrillic = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.CYRILLIC
                verdicts[k] = if (cyrillic && !latin && (c.isLowerCase() || k == start)) FOR else AGAINST
            }
        }
        return verdicts
    }

    /** Calls [block] with the bounds of every run of letters in [text]. */
    private inline fun forEachWord(text: String, block: (start: Int, end: Int) -> Unit) {
        var i = 0
        while (i < text.length) {
            if (!isWordLetter(text[i])) {
                i++
                continue
            }
            var end = i + 1
            while (end < text.length && isWordLetter(text[end])) end++
            block(i, end)
            i = end
        }
    }

    /** The ordinal signs of 1º and 2ª are letters to Unicode, but they belong with the numbers. */
    private fun isWordLetter(c: Char): Boolean = c.isLetter() && c != 'ª' && c != 'º'

    /**
     * Spaces, punctuation and the few signs prose is set with, which every code page has in much
     * the same places and which say nothing for or against a reading. Other symbols do: box
     * drawing, mathematical signs, superscript figures and loose accents are what a wrong code
     * page makes of letters.
     */
    private fun neutral(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.SPACE_SEPARATOR, Character.FORMAT, Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION, Character.END_PUNCTUATION, Character.CONNECTOR_PUNCTUATION,
        Character.OTHER_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION, Character.CURRENCY_SYMBOL -> true
        else -> c in "°©®™№ªº"
    }

    /**
     * Collapses line endings and blank runs; also strips control characters that TTS engines
     * choke on, the C1 range included: text that came through ISO-8859-1 somewhere upstream
     * carries its curly quotes and dashes as those codes, and they show as nothing or as boxes.
     */
    fun normalize(text: String): String {
        val unified = text.replace("\r\n", "\n").replace('\r', '\n')
        val sb = StringBuilder(unified.length)
        var blankRun = 0
        for (line in unified.split('\n')) {
            val cleaned = line.trimEnd().filterNot { (it.code < 0x20 && it != '\t') || it.code in 0x7F..0x9F }
            if (cleaned.isBlank()) {
                blankRun++
                if (blankRun == 1) sb.append('\n')
            } else {
                blankRun = 0
                sb.append(cleaned).append('\n')
            }
        }
        return sb.toString().trim()
    }

    /**
     * A book's lines joined back into paragraphs where the file was typeset for a fixed width,
     * the way Project Gutenberg and most plain-text archives keep their books: every line broken
     * at sixty to eighty characters, a blank line between paragraphs. Kept as it is, such a book
     * reads as ragged lines on the phone, and on the watch each of them folds into three or four
     * short ones.
     *
     * The layout is recognised for the book as a whole, never line by line: almost every line
     * that runs straight on into another must be long and within a few characters of the width
     * the book was set to. A book that keeps a paragraph to a line has lines of every length,
     * and verse has short ones, so neither passes. Only Latin-script books are joined, with a
     * space; Chinese and Japanese put no space between words and keep their lines as they are.
     * Within a wrapped book a line runs on only when it is long enough to have been broken and
     * the next line starts flush, so a closing short line, an indented verse or an indented
     * paragraph opening keeps its break.
     */
    fun unwrap(text: String): String {
        if (!latinScript(text)) return text
        val lines = text.split('\n')
        val runOn = ArrayList<Int>()
        for (i in 0 until lines.size - 1) {
            if (lines[i].isNotBlank() && lines[i + 1].isNotBlank()) runOn.add(lines[i].length)
        }
        if (runOn.size < MIN_WRAPPED_SAMPLES) return text
        runOn.sort()
        val width = runOn[runOn.size * 19 / 20]
        if (width > MAX_WRAP_WIDTH) return text
        val shortest = maxOf(MIN_WRAPPED_LINE, width - WRAP_RAGGEDNESS)
        val wrapped = runOn.count { it in shortest..width }
        if (wrapped * 5 < runOn.size * 4) return text
        val sb = StringBuilder(text.length)
        for (i in lines.indices) {
            sb.append(lines[i])
            if (i == lines.size - 1) break
            val next = lines[i + 1]
            val joins = lines[i].length >= shortest && next.isNotEmpty() && !next[0].isWhitespace()
            sb.append(if (joins) ' ' else '\n')
        }
        return sb.toString()
    }

    /** Fewer lines running on than this is too little to tell a wrapped book from any other. */
    private const val MIN_WRAPPED_SAMPLES = 40

    /** A line shorter than this was ended on purpose; no one wraps text this narrow. */
    private const val MIN_WRAPPED_LINE = 45

    /** Wider than this and the lines are paragraphs, not a page width. */
    private const val MAX_WRAP_WIDTH = 110

    /** How far short of the page width a line broken before a long word can fall. */
    private const val WRAP_RAGGEDNESS = 25

    /** Whether nearly all of a book's letters are Latin, the only script whose lines are joined. */
    private fun latinScript(text: String): Boolean {
        var letters = 0
        var latin = 0
        for (c in text) {
            if (!c.isLetter()) continue
            letters++
            if (c.code < 0x80 || Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN) {
                latin++
            } else if ((letters - latin) * 20 > text.length) {
                // Already too many for any count of letters the rest could bring; a Chinese
                // book stops here within its first pages instead of being read to the end.
                return false
            }
        }
        return letters > 0 && latin * 20 >= letters * 19
    }

    /**
     * Whether [bytes] are something other than text: a Kindle book, a PDF, a picture or an
     * archive picked by mistake. Every one of these holds zero bytes, which no text encoding
     * but UTF-16 ever writes, and a good share of the other control codes, which text uses only
     * for its line ends, tabs and the odd page break. Decoding such a file anyway succeeds, in
     * a single-byte encoding at least, and gives a book of nothing but noise.
     */
    fun looksBinary(bytes: ByteArray): Boolean {
        val wide = bom(bytes)?.takeIf { it.first != Charsets.UTF_8 }
            ?: utf16WithoutBom(bytes)?.let { it to 0 }
        var controls = 0
        if (wide != null) {
            val (charset, skip) = wide
            val sample = String(bytes, skip, minOf(bytes.size - skip, SAMPLE_BYTES) and 1.inv(), charset)
            for (c in sample) {
                if (c.code == 0) return true
                if (c.code < 0x20 && c.code !in TEXT_CONTROLS) controls++
            }
            return controls * CONTROL_SHARE > sample.length
        }
        val end = minOf(bytes.size, SAMPLE_BYTES)
        for (i in 0 until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 0x20 && b !in TEXT_CONTROLS) controls++
        }
        return controls * CONTROL_SHARE > end
    }

    /** How much of a file is looked at to tell text from anything else. */
    private const val SAMPLE_BYTES = 64 * 1024

    /** Tab, line feed, vertical tab, form feed, carriage return, the DOS end-of-file mark and escape. */
    private val TEXT_CONTROLS = setOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1A, 0x1B)

    /** More than one control code in this many characters is not text. */
    private const val CONTROL_SHARE = 20

    /**
     * UTF-16 saved without a byte-order mark. Where much of the text is ASCII, as in any Western
     * book, every other byte is the zero high half of a character, all on the same side of each
     * pair; the zero bytes of anything else fall on both sides alike.
     */
    private fun utf16WithoutBom(bytes: ByteArray): Charset? {
        val end = minOf(bytes.size, SAMPLE_BYTES) and 1.inv()
        if (end < 2) return null
        var even = 0
        var odd = 0
        for (i in 0 until end) {
            if (bytes[i].toInt() != 0) continue
            if (i % 2 == 0) even++ else odd++
        }
        val pairs = end / 2
        return when {
            odd * 4 >= pairs && even * 10 <= odd -> Charsets.UTF_16LE
            even * 4 >= pairs && odd * 10 <= even -> Charsets.UTF_16BE
            else -> null
        }
    }

    private fun bom(bytes: ByteArray): Pair<Charset, Int>? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8 to 3
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE to 2
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE to 2
        }
        return null
    }

    private fun strict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
