package com.watchreader.wear.tts

import java.text.Normalizer
import java.util.Locale

/**
 * Picks the voice each sentence is read aloud in.
 *
 * Scripts other than Latin say what language they are written in, so those are told apart one
 * sentence at a time: a sentence goes to the script that makes up more than 30% of its letters and
 * digits. Han characters with any kana among them are Japanese; Han on its own is Chinese, as it
 * always has been, unless the book as a whole is written in Japanese.
 *
 * Latin letters do not say which language they spell, and one sentence is too little to tell
 * French from Italian, so the book's Latin language is worked out once, from the small words that
 * make up much of any page ("the", "le", "der", "och"), and every Latin sentence in the book is read
 * in it. A book with too few of those words to be sure is read in English, as every book was before,
 * unless the watch itself is set to one of those languages: then that is the better guess, and it
 * also settles a close call between neighbours such as Danish and Norwegian.
 *
 * Cyrillic is Russian unless the book uses the letters only Ukrainian has (і, ї, є, ґ) more than
 * the ones only Russian has (ы, э, ъ, ё); that too is decided once for the whole book.
 */
object LanguageDetector {
    val ENGLISH: Locale = Locale.US
    val CHINESE: Locale = Locale.SIMPLIFIED_CHINESE
    val JAPANESE: Locale = Locale.JAPAN
    val KOREAN: Locale = Locale.KOREA
    val RUSSIAN: Locale = Locale.forLanguageTag("ru-RU")
    val GREEK: Locale = Locale.forLanguageTag("el-GR")
    val ARABIC: Locale = Locale.forLanguageTag("ar")
    val HEBREW: Locale = Locale.forLanguageTag("he-IL")
    val THAI: Locale = Locale.forLanguageTag("th-TH")
    val HINDI: Locale = Locale.forLanguageTag("hi-IN")
    val FRENCH: Locale = Locale.FRANCE
    val GERMAN: Locale = Locale.GERMANY
    val SPANISH: Locale = Locale.forLanguageTag("es-ES")
    val ITALIAN: Locale = Locale.ITALY
    val PORTUGUESE: Locale = Locale.forLanguageTag("pt-BR")
    val DUTCH: Locale = Locale.forLanguageTag("nl-NL")
    val SWEDISH: Locale = Locale.forLanguageTag("sv-SE")
    val DANISH: Locale = Locale.forLanguageTag("da-DK")
    val NORWEGIAN: Locale = Locale.forLanguageTag("nb-NO")
    val FINNISH: Locale = Locale.forLanguageTag("fi-FI")
    val POLISH: Locale = Locale.forLanguageTag("pl-PL")
    val TURKISH: Locale = Locale.forLanguageTag("tr-TR")
    val VIETNAMESE: Locale = Locale.forLanguageTag("vi-VN")
    val INDONESIAN: Locale = Locale.forLanguageTag("id-ID")
    val UKRAINIAN: Locale = Locale.forLanguageTag("uk-UA")

    /**
     * What is decided once for a whole book before any of it is read: the language its Latin
     * sentences are in, and whether its Han characters are Japanese kanji rather than Chinese.
     */
    data class Book(
        val latin: Locale = ENGLISH,
        val kanjiIsJapanese: Boolean = false,
        /** The language of the book's Cyrillic sentences. */
        val cyrillic: Locale = RUSSIAN,
    )

    private class Script(val locale: Locale, vararg val ranges: IntRange)

    /** Scripts that belong to one language, apart from Han and kana, which are weighed first. */
    private val SCRIPTS = listOf(
        Script(KOREAN, 0xAC00..0xD7AF, 0x1100..0x11FF, 0x3130..0x318F, 0xA960..0xA97F, 0xD7B0..0xD7FF),
        Script(RUSSIAN, 0x0400..0x052F, 0x1C80..0x1C8F, 0x2DE0..0x2DFF, 0xA640..0xA69F),
        Script(GREEK, 0x0370..0x03FF, 0x1F00..0x1FFF),
        Script(ARABIC, 0x0600..0x06FF, 0x0750..0x077F, 0x08A0..0x08FF, 0xFB50..0xFDFF, 0xFE70..0xFEFF),
        Script(HEBREW, 0x0590..0x05FF, 0xFB1D..0xFB4F),
        Script(THAI, 0x0E00..0x0E7F),
        Script(HINDI, 0x0900..0x097F, 0xA8E0..0xA8FF),
    )

    private class Latin(val locale: Locale, words: String) {
        val words: Set<String> = words.split(' ').toHashSet()
    }

    /**
     * The commonest short words of each language, lower case. English comes first, so it wins a
     * tie. Words two languages share count for both; what decides between neighbours such as
     * Danish and Norwegian is the words they spell differently (af/av, havde/hadde, mig/meg).
     */
    private val LATIN = listOf(
        Latin(ENGLISH, "the of and to a in is it that was he for on are with as i his they be at one have this from or had by not but what all were we when your can said there an which she do their if will would so been her him me my no you who did them then could into about up out some has more than its only other very also just like over"),
        Latin(FRENCH, "le la les de des du et est un une il elle que qui ne pas en dans pour sur au aux avec se sa son ses ce cette ces mais ou je nous vous ils elles lui leur plus par été était avait comme tout bien sans même aussi très y on qu j dit sont ont fait être me moi mon ma mes où quand alors si encore rien à"),
        Latin(GERMAN, "der die das und ist nicht ich sie es er zu den dem des ein eine einen einem einer mit sich auf für von im in wie auch als an aus bei nach noch wir ihr mir mich dir sein seine war hat hatte wird werden wurde aber oder wenn dass daß nur schon doch man was wer hier dann nun kein keine um über durch bis zum zur vom am"),
        Latin(SPANISH, "el la los las de del y que en un una es se no por con para su sus al lo como más pero le les ya o a fue este esta ha sí porque entre cuando muy sin sobre también me hasta hay donde quien desde todo nos yo él ella era había estaba dijo eso esto nada qué tiene mi te"),
        Latin(ITALIAN, "il lo la i gli le di del della dei delle e è che non un una in a al alla per con da dal si sono ma come più anche se mi ti ci ne suo sua questo questa quello era ha hanno aveva nel nella sul io lui lei noi voi loro perché quando molto tutto già poi ancora così cosa stato fatto solo sempre ed"),
        Latin(PORTUGUESE, "o a os as de do da dos das e que em no na nos nas um uma para com não se por mais como mas ao ele ela seu sua ou quando muito já eu também só pelo pela até isso depois sem mesmo aos seus quem me esse você essa nem meu minha lhe foi era tinha disse há então ainda aqui"),
        Latin(DUTCH, "de het een en van in is dat op te zijn met voor niet aan er maar om hij ze zij als ook bij of naar dan nog wat door uit over tot je ik we wij was had heeft hebben werd worden kan zou geen meer al toen nu hier daar die deze dit haar hem zich mijn wel zo want iets niets veel"),
        Latin(SWEDISH, "och att det i som en på är av för med till den har de inte om ett han men var jag sig från så kan hon vid när eller efter ut upp skulle hade blev något också bara där mycket honom henne vi ni hans hennes sin sitt dig mig vad nu här sedan än alla man under över mot utan kunde vara hur då detta alltid aldrig kanske fick lite igen några"),
        Latin(DANISH, "og i at det er en til på som de med han af for ikke der var jeg har sig men et hun om den så fra kan eller når efter ud op skulle havde blev noget også bare hvor meget ham hende vi jer hans hendes sin sit dig mig hvad nu her siden end alle man under over mod uden kunne være hvordan da dette altid aldrig måske fik lidt igen nogle"),
        Latin(NORWEGIAN, "og i at det er en til på som de med han av for ikke der var jeg har seg men et hun om den så fra kan eller når etter ut opp skulle hadde ble noe også bare hvor mye ham henne vi dere hans hennes sin sitt deg meg hva nå her siden enn alle man under over mot uten kunne være hvordan da dette alltid aldri kanskje fikk litt igjen noen å"),
        Latin(FINNISH, "ja on ei se että hän oli ole mutta kun niin kuin joka jo vain sen hänen minä sinä me he ne tämä tuo mitä nyt sitten myös jos vielä ovat olla kanssa mikä siitä sitä siellä täällä minun sinun olen olet koska kaikki pois sillä jotka jonka mukaan voi eikä tai vaan ennen jälkeen aina paljon hyvin nämä"),
        Latin(POLISH, "i w nie się na z że do to jest o jak a ale co po tak go jego jej od za już tylko czy przez dla tego był była było ze mnie ja ty on ona my oni ten ta gdy bo jeszcze tym który która które aby kiedy tu tam sobie mi mu bardzo teraz nic być może"),
        Latin(TURKISH, "ve bir bu da de için ile ne o ama gibi çok daha en ben sen biz siz onlar var yok değil mi mı mu mü ki kadar sonra şey her olan olarak ya diye bana beni sana seni onu ona şimdi hiç nasıl neden zaman kendi önce bile artık hem ise şu bunu buna böyle öyle oldu olduğu idi dedi değildi çünkü ancak eğer sadece tüm hep"),
        Latin(VIETNAMESE, "và của là có không được người một những trong cho này đã với các để khi thì cũng như đến ra nhưng tôi anh em ông bà nó họ chúng ta mình lại từ về làm đi nói biết còn sẽ vào rất nhiều nếu vì sao gì ai đây đó rồi mà năm đang chỉ đều nào lên hay sau bị thấy muốn phải"),
        Latin(INDONESIAN, "yang dan di itu dengan untuk tidak ini dari dalam akan pada juga saya ke karena tersebut bisa ada mereka lebih kami kita sudah atau hanya oleh jika seperti telah dia aku kamu apa ia tetapi masih sangat harus bahwa saat semua tak begitu lagi kalau belum sebuah setelah ketika sedang tapi hari orang"),
    )

    /** Every language a sentence can be read in, in the order the settings page lists them. */
    val LANGUAGES: List<Locale> =
        (listOf(ENGLISH, CHINESE, JAPANESE) + SCRIPTS.map { it.locale } + UKRAINIAN + LATIN.map { it.locale }).distinct()

    private const val SHARE = 0.3f
    /** The share of a book's letters that must be Latin before its Latin language is guessed. */
    private const val LATIN_BOOK = 0.8f
    /** Latin letters looked at to decide a book's Latin language. */
    private const val LATIN_SAMPLE = 20_000
    /** Places through the book the sample is taken from, in equal parts. */
    private const val WINDOWS = 8
    /** Below this many common words, or this share of the words sampled, the guess is English. */
    private const val MIN_HITS = 8
    private const val MIN_SHARE = 0.1f
    /** Ukrainian letters a book must have, beyond the Russian ones, before its Cyrillic is Ukrainian. */
    private const val UKRAINIAN_MIN = 3
    /**
     * The watch's own Latin language wins when its words come to at least this share of the best
     * language's: close enough that the lists cannot tell the two apart with any confidence.
     */
    private const val CLOSE = 0.8f
    /** A language the settings page asks a voice for must carry at least this share of a book. */
    private const val NEEDED_SHARE = 0.01f

    private val PLAIN = Book()

    fun detect(text: CharSequence, book: Book = PLAIN): Locale {
        var total = 0
        var han = 0
        var kana = 0
        val scripts = IntArray(SCRIPTS.size)
        for (c in text) {
            if (!c.isLetterOrDigit()) continue
            total++
            val code = c.code
            when {
                code < 0x0370 -> Unit
                isHan(code) -> han++
                isKana(code) -> kana++
                else -> {
                    val s = SCRIPTS.indexOfFirst { script -> script.ranges.any { code in it } }
                    if (s >= 0) scripts[s]++
                }
            }
        }
        if (total == 0) return book.latin
        if (kana > 0 && (han + kana).toFloat() / total > SHARE) return JAPANESE
        if (han.toFloat() / total > SHARE) return if (book.kanjiIsJapanese) JAPANESE else CHINESE
        var best = -1
        for (i in scripts.indices) if (scripts[i] > 0 && (best < 0 || scripts[i] > scripts[best])) best = i
        if (best >= 0 && scripts[best].toFloat() / total > SHARE) {
            return if (SCRIPTS[best].locale == RUSSIAN) book.cyrillic else SCRIPTS[best].locale
        }
        return book.latin
    }

    /**
     * Looks the whole book over once. Its Han characters are Japanese when kana make up at least a
     * third of its Han and kana together, as they do on any page of Japanese; a Chinese book that
     * quotes some Japanese comes nowhere near that.
     *
     * The Latin language is guessed only for a book written almost wholly in Latin letters. In a
     * book with a real share of another script the Latin words are mostly English (names of things,
     * quotations, the odd term) or words spelt out, such as pinyin, whose "de", "le" and "ma" look
     * like French to the word lists; they are read in English, as they always were.
     *
     * [system] is the language the watch is set to, which helps only with a Latin-script book.
     */
    fun survey(text: CharSequence, system: Locale? = null): Book {
        var letters = 0
        var latin = 0
        var han = 0
        var kana = 0
        var ukrainian = 0
        var russian = 0
        for (c in text) {
            if (!c.isLetter()) continue
            letters++
            val code = c.code
            when {
                isLatinLetter(c) -> latin++
                isHan(code) -> han++
                isKana(code) -> kana++
                c in UKRAINIAN_ONLY -> ukrainian++
                c in RUSSIAN_ONLY -> russian++
            }
        }
        return Book(
            latin = if (letters > 0 && latin >= letters * LATIN_BOOK) latinLanguage(text, system) else ENGLISH,
            kanjiIsJapanese = kana > 0 && kana * 3 >= han + kana,
            cyrillic = if (ukrainian >= UKRAINIAN_MIN && ukrainian > russian) UKRAINIAN else RUSSIAN,
        )
    }

    /**
     * The language of the book's Latin-script text. When there is too little to tell, it is the
     * watch's own language if that is one of the Latin ones listed here, and English otherwise;
     * the watch's language also wins when its words come close to the best.
     */
    fun latinLanguage(text: CharSequence, system: Locale? = null): Locale {
        val words = latinSample(text)
        val hits = IntArray(LATIN.size)
        for (word in words) for (i in LATIN.indices) if (word in LATIN[i].words) hits[i]++
        var best = 0
        for (i in hits.indices) if (hits[i] > hits[best]) best = i
        val sure = hits[best] >= MIN_HITS && hits[best] >= words.size * MIN_SHARE
        val own = if (system == null) -1 else LATIN.indexOfFirst { sameLanguage(it.locale, system) }
        return when {
            own < 0 -> if (sure) LATIN[best].locale else ENGLISH
            !sure || hits[own] >= hits[best] * CLOSE -> LATIN[own].locale
            else -> LATIN[best].locale
        }
    }

    /**
     * Whether two locales name the same language, whatever the region. Compared by the three-letter
     * code, because Android still reports some languages by their old two-letter ones ("in" for
     * Indonesian, "iw" for Hebrew) where a tag says "id" and "he".
     */
    fun sameLanguage(a: Locale, b: Locale): Boolean =
        runCatching { a.isO3Language == b.isO3Language }.getOrDefault(a.language == b.language)

    /**
     * About [LATIN_SAMPLE] letters' worth of Latin words, lower case, taken in equal parts from
     * [WINDOWS] places spread through the text, so that an English preface or licence at the front
     * of a French book cannot outvote the book itself.
     */
    internal fun latinSample(text: CharSequence): List<String> {
        val words = ArrayList<String>()
        val word = StringBuilder()
        val n = text.length
        for (k in 0 until WINDOWS) {
            var i = (n.toLong() * k / WINDOWS).toInt()
            val end = (n.toLong() * (k + 1) / WINDOWS).toInt()
            // A word cut in two by the start of the window was the window before's to take.
            if (i > 0 && isWordPart(text[i - 1])) while (i < n && isWordPart(text[i])) i++
            var letters = 0
            while (i < end && letters < LATIN_SAMPLE / WINDOWS) {
                if (!isLatinLetter(text[i])) {
                    i++
                    continue
                }
                word.setLength(0)
                var marked = false
                while (i < n && isWordPart(text[i])) {
                    val c = text[i++]
                    if (!isLatinLetter(c)) marked = true
                    word.append(c.lowercaseChar())
                }
                letters += word.length
                // Vietnamese in particular may come with its accents as separate marks; the word
                // lists hold them composed.
                words += if (marked) Normalizer.normalize(word, Normalizer.Form.NFC) else word.toString()
            }
        }
        return words
    }

    /**
     * The languages a book would be read aloud in, for the voices list in Settings: each language
     * at least [NEEDED_SHARE] of the book's letters would be spoken in, so that a stray foreign
     * word does not ask for a voice of its own.
     */
    fun languagesIn(text: String, system: Locale? = null): Set<Locale> {
        val book = survey(text, system)
        val letters = HashMap<Locale, Int>()
        var total = 0
        for (range in SentenceParser.ranges(text)) {
            val sentence = text.subSequence(range.first, range.last + 1)
            val count = sentence.count { it.isLetter() }
            if (count == 0) continue
            val locale = detect(sentence, book)
            letters[locale] = (letters[locale] ?: 0) + count
            total += count
        }
        return letters.filterValues { it >= total * NEEDED_SHARE }.keys
    }

    private fun isHan(code: Int) = code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF

    /** Hiragana and katakana, full and half width. The katakana middle dot is not a letter. */
    private fun isKana(code: Int) =
        code in 0x3040..0x309F || code in 0x30A0..0x30FF || code in 0x31F0..0x31FF || code in 0xFF66..0xFF9F

    private fun isLatinLetter(c: Char): Boolean {
        val code = c.code
        return (code < 0x0250 || code in 0x1E00..0x1EFF) && c.isLetter()
    }

    /** A Latin letter, or an accent written as a separate mark after one. */
    private fun isWordPart(c: Char): Boolean =
        isLatinLetter(c) || Character.getType(c) == Character.NON_SPACING_MARK.toInt()

    private const val UKRAINIAN_ONLY = "іїєґІЇЄҐ"
    private const val RUSSIAN_ONLY = "ыэъёЫЭЪЁ"
}
