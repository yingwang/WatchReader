package com.watchreader.shared

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Turns the bytes of a plain-text book into clean UTF-8 text.
 *
 * Detection order: byte-order mark, then strict UTF-8, then the East Asian encodings with GB18030
 * (a superset of GBK, which is what most Chinese .txt files in the wild are saved as) preferred,
 * then ISO-8859-1 as a last resort so that nothing is ever rejected. Line endings become "\n" and
 * runs of blank lines collapse to one.
 */
object TextNormalizer {
    data class Decoded(val text: String, val charset: String)

    fun decode(bytes: ByteArray, declaredCharset: String? = null): Decoded {
        bom(bytes)?.let { (charset, skip) ->
            return Decoded(normalize(String(bytes, skip, bytes.size - skip, charset)), charset.name())
        }
        val declared = declaredCharset?.takeIf { it.isNotBlank() }?.let { runCatching { Charset.forName(it) }.getOrNull() }
        // A single-byte charset decodes any bytes without complaint, so a server that still stamps
        // its old Latin-1 default on a UTF-8 file cannot be believed until strict UTF-8 has failed.
        val singleByte = declared != null && declared.newEncoder().maxBytesPerChar() <= 1f
        if (declared != null && !singleByte) strict(bytes, declared)?.let { return Decoded(normalize(it), declared.name()) }
        strict(bytes, Charsets.UTF_8)?.let { return Decoded(normalize(it), "UTF-8") }
        if (declared != null && singleByte) strict(bytes, declared)?.let { return Decoded(normalize(it), declared.name()) }
        eastAsian(bytes)?.let { return it }
        return Decoded(normalize(String(bytes, Charsets.ISO_8859_1)), "ISO-8859-1")
    }

    /**
     * GB18030 accepts nearly any pair of high bytes, so a Big5 or Shift_JIS file decodes under it
     * without complaint into characters nobody uses. Every candidate that decodes cleanly is
     * scored by how much of it is made of everyday characters, and another encoding displaces
     * GB18030 only when it reads clearly better, as a traditional Chinese or Japanese file does.
     */
    private fun eastAsian(bytes: ByteArray): Decoded? {
        var best: Decoded? = null
        var bestScore = -1.0
        for ((index, name) in EAST_ASIAN.withIndex()) {
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            val text = strict(bytes, charset) ?: continue
            val score = everyday(text)
            val margin = if (index == 0) 0.0 else CHALLENGER_MARGIN
            if (score > bestScore + margin || best == null) {
                best = Decoded(normalize(text), charset.name())
                bestScore = score
            }
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

    /** Collapses line endings and blank runs; also strips control characters that TTS engines choke on. */
    fun normalize(text: String): String {
        val unified = text.replace("\r\n", "\n").replace('\r', '\n')
        val sb = StringBuilder(unified.length)
        var blankRun = 0
        for (line in unified.split('\n')) {
            val cleaned = line.trimEnd().filterNot { it.code < 0x20 && it != '\t' }
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
