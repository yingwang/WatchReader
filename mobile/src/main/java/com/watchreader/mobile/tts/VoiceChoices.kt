package com.watchreader.mobile.tts

import java.util.Locale

/**
 * The voices of one language as a reader chooses between them, named so that they can be told
 * apart: "Female voice 1", "Male voice 2", with the region added when the language is spoken in
 * voices from more than one.
 *
 * Engines list their voices under codes. Google's lists every voice twice, once to be spoken on
 * the phone and once from its servers ("en-us-x-iob-local" and "en-us-x-iob-network"), and adds
 * one more per region that only stands for the region's default ("en-US-language"). Here the two
 * copies of a voice become one choice, which uses the copy on the phone when there is one, and the
 * stand-ins are left out, since "Default" is already the first choice. A voice the engine has only
 * on its servers is still offered, marked as needing the internet.
 *
 * The [Voice][android.speech.tts.Voice] API has no field for a voice's gender. Some engines name
 * it among a voice's features, or in its name ("en-us-x-sfg#male_1-local" in older versions of
 * Google's engine), and those are believed first. Otherwise Google's codes are looked up in
 * [GENDERS], and a voice that is in neither is simply "Voice 1", "Voice 2".
 *
 * Nothing here touches the engine, so all of it runs in plain JVM tests.
 */
object VoiceChoices {
    enum class Gender { FEMALE, MALE }

    /** One voice as an engine lists it, as far as choosing between voices goes. */
    data class Listed(
        val name: String,
        val locale: Locale,
        /** Spoken from the engine's servers, so it needs a connection. */
        val network: Boolean,
        /** False while the engine still has to fetch the voice's data before it can speak. */
        val installed: Boolean = true,
        val features: Set<String> = emptySet(),
    )

    /** One voice to choose: the copies the engine lists of it, taken together. */
    data class Choice(
        /** The voice without its copy: "en-us-x-iob" for both "en-us-x-iob-local" and "-network". */
        val key: String,
        /** The copy that is used and saved: the one on the phone when there is one. */
        val name: String,
        val locale: Locale,
        val gender: Gender?,
        /** No copy is on the phone: the voice reads only with a connection. */
        val needsInternet: Boolean,
    )

    /** How a choice is shown: [gender] and [number] make "Female voice 2"; [region] is added when needed. */
    data class Label(val gender: Gender?, val number: Int, val region: String?)

    /**
     * The voices among [voices] a reader can choose, one per voice, in a steady order: by region,
     * [first]'s region ahead of the others, then women's voices, men's, and the ones whose gender
     * is not known, each by code.
     */
    fun group(voices: List<Listed>, first: Locale? = null): List<Choice> {
        val firstRegion = first?.country?.uppercase(Locale.ROOT)?.ifEmpty { null }
        return voices.filter { it.installed && !isStandIn(it.name) }
            .groupBy { key(it.name) }
            .map { (key, copies) ->
                val used = copies.firstOrNull { !it.network } ?: copies.first()
                Choice(key, used.name, used.locale, genderOf(key, copies), needsInternet = used.network)
            }
            .sortedWith(
                compareBy(
                    { it.locale.country.uppercase(Locale.ROOT) != firstRegion },
                    { it.locale.toLanguageTag() },
                    { it.gender?.ordinal ?: Gender.entries.size },
                    { it.key },
                ),
            )
    }

    /**
     * How each of [choices] is labelled, in the same order. Women's and men's voices, and the rest,
     * are numbered apart, and again within each region when the region is shown.
     */
    fun labels(choices: List<Choice>): List<Label> {
        val severalRegions = choices.map { it.locale.country.uppercase(Locale.ROOT) }.distinct().size > 1
        val counted = HashMap<Pair<String, Gender?>, Int>()
        return choices.map { choice ->
            val region = choice.locale.country.uppercase(Locale.ROOT)
            val bucket = (if (severalRegions) region else "") to choice.gender
            Label(choice.gender, counted.merge(bucket, 1, Int::plus)!!, if (severalRegions) regionName(choice.locale) else null)
        }
    }

    /**
     * The choice a saved voice [stored] stands for: the one listing it by name, or else the one of
     * the same voice, so that a saved "-network" copy, or a copy an engine update dropped, still
     * ticks its voice. Null, for "Default", when the voice is gone altogether.
     */
    fun selected(stored: String?, choices: List<Choice>): Choice? {
        if (stored == null) return null
        return choices.firstOrNull { it.name == stored } ?: key(stored).let { k -> choices.firstOrNull { it.key == k } }
    }

    /**
     * The name of the voice to read in for the saved choice [stored], given the engine's [voices]:
     * the copy of that voice the reader would be offered now, which is the one on the phone once it
     * has come, or the very voice saved when it is not one of those offered. Null when it is gone.
     */
    fun resolve(stored: String, voices: List<Listed>): String? =
        selected(stored, group(voices))?.name ?: voices.firstOrNull { it.name == stored }?.name

    /** The voice a name is a copy of: its name without "-local" or "-network", in lower case. */
    fun key(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        for (suffix in COPY_SUFFIXES) if (lower.endsWith(suffix) && lower.length > suffix.length) return lower.dropLast(suffix.length)
        return lower
    }

    /** The region's short English name, the way a reader would say it: "UK", "US", "Australia". */
    fun regionName(locale: Locale): String? {
        val country = locale.country.uppercase(Locale.ROOT)
        if (country.isEmpty()) return null
        return SHORT_REGIONS[country] ?: locale.getDisplayCountry(Locale.ENGLISH).ifEmpty { country }
    }

    /**
     * A short sentence to try a voice of [locale] with. Each language the reader can detect has one;
     * any other is given the language's own name for itself, which it can always say.
     */
    fun sample(locale: Locale): String {
        val language = runCatching { locale.isO3Language }.getOrNull()?.ifEmpty { null } ?: locale.language
        if (language == "zho" || language == "cmn") {
            val traditional = locale.script.equals("Hant", ignoreCase = true) || locale.country.uppercase(Locale.ROOT) in TRADITIONAL_REGIONS
            return if (traditional) "這個聲音聽起來是這樣的。" else "这个声音听起来是这样的。"
        }
        return SAMPLES[language] ?: locale.getDisplayLanguage(locale).ifEmpty { SAMPLES.getValue("eng") }
    }

    /** "en-US-language" and the like: an engine's name for a region's default voice, not a voice of its own. */
    private fun isStandIn(name: String): Boolean = STAND_IN.matches(name)

    /** A gender the engine gives in a feature or in the name of any copy, or else the one [GENDERS] knows. */
    private fun genderOf(key: String, copies: List<Listed>): Gender? {
        val stated = copies.flatMap { it.features + it.name }.mapNotNull(::statedGender).distinct()
        if (stated.size == 1) return stated.single()
        return GENDERS[key]
    }

    /** "female", "gender=male", "en-us-x-sfg#female_2-local": a word for a gender, standing on its own. */
    private fun statedGender(text: String): Gender? {
        val words = text.lowercase(Locale.ROOT).split(NOT_LETTERS)
        val female = "female" in words
        val male = "male" in words
        return when {
            female && !male -> Gender.FEMALE
            male && !female -> Gender.MALE
            else -> null
        }
    }

    private val COPY_SUFFIXES = listOf("-local", "-network")
    private val STAND_IN = Regex("^[a-z]{2,3}(-[a-z0-9]{2,4})?-(language|default)$", RegexOption.IGNORE_CASE)
    private val NOT_LETTERS = Regex("[^a-z]+")
    private val SHORT_REGIONS = mapOf("GB" to "UK", "US" to "US", "HK" to "Hong Kong")
    private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")

    /**
     * Google's voice codes for the languages most books here are in, and whether each voice is a
     * woman's or a man's. Best effort: the engine does not say, and Google publishes no such list.
     *
     * Taken from Readium Speech's voice lists (github.com/readium/speech, json/<language>.json,
     * commit 70a58ea of 2026-10-22), which give each of Google's Android voices by its code with a
     * gender heard and recorded by hand. For English, crosstales RT-Voice's list of the male
     * voices on Android 11 (en-us iol, iom, tpd; en-gb rjs, gbb, gbd; en-au aub, aud; en-in end,
     * ene) agrees code for code. Only codes found there are in the table; a voice Google adds later
     * is shown as "Voice N" until it is added here.
     */
    internal val GENDERS: Map<String, Gender> = buildMap {
        fun voices(prefix: String, female: String, male: String) {
            female.split(' ').forEach { put("$prefix-x-$it", Gender.FEMALE) }
            male.split(' ').forEach { put("$prefix-x-$it", Gender.MALE) }
        }
        voices("en-us", female = "iob iog sfg tpc tpf", male = "iol iom tpd")
        voices("en-gb", female = "gba gbc gbg", male = "gbb gbd rjs")
        voices("en-au", female = "aua auc", male = "aub aud")
        voices("en-in", female = "ena enc", male = "end ene")
        voices("cmn-cn", female = "ccc ssa", male = "ccd cce")
        voices("cmn-tw", female = "ctc", male = "ctd cte")
        voices("yue-hk", female = "jar yuc", male = "yud yue yuf")
        voices("ja-jp", female = "htm jab", male = "jac jad")
        voices("ko-kr", female = "ism kob", male = "koc kod")
        voices("de-de", female = "dea nfh", male = "deb deg")
        voices("fr-fr", female = "fra frc vlf", male = "frb frd")
        voices("fr-ca", female = "caa cac", male = "cab cad")
        voices("es-es", female = "eea eec eee", male = "eed eef")
        voices("es-us", female = "esc sfb", male = "esd esf")
        voices("it-it", female = "itb kda", male = "itc itd")
        voices("pt-br", female = "afs pte", male = "ptd")
        voices("pt-pt", female = "jfb sfs", male = "jmn pmj")
        voices("nl-nl", female = "lfc tfb yfr", male = "bmh dma")
        voices("nl-be", female = "bec", male = "bed")
        voices("sv-se", female = "afp cfg lfs", male = "cmh dmc")
        voices("ru-ru", female = "dfc ruc rue", male = "rud ruf")
    }

    /** "This is how this voice sounds." in the languages LanguageDetector can find, by ISO 639-2 code. */
    private val SAMPLES: Map<String, String> = mapOf(
        "eng" to "This is how this voice sounds.",
        "yue" to "呢把聲聽落係咁樣嘅。",
        "jpn" to "この声はこのように聞こえます。",
        "kor" to "이 목소리는 이렇게 들립니다.",
        "deu" to "So klingt diese Stimme.",
        "fra" to "Voici comment sonne cette voix.",
        "spa" to "Así suena esta voz.",
        "ita" to "Ecco come suona questa voce.",
        "por" to "É assim que esta voz soa.",
        "nld" to "Zo klinkt deze stem.",
        "swe" to "Så här låter den här rösten.",
        "dan" to "Sådan lyder denne stemme.",
        "nob" to "Slik høres denne stemmen ut.",
        "nor" to "Slik høres denne stemmen ut.",
        "fin" to "Tältä tämä ääni kuulostaa.",
        "pol" to "Tak brzmi ten głos.",
        "ces" to "Takto zní tento hlas.",
        "tur" to "Bu ses böyle duyuluyor.",
        "vie" to "Giọng nói này nghe như thế này.",
        "ind" to "Beginilah suara ini terdengar.",
        "rus" to "Вот как звучит этот голос.",
        "ukr" to "Ось так звучить цей голос.",
        "ell" to "Έτσι ακούγεται αυτή η φωνή.",
        "ara" to "هكذا يبدو هذا الصوت.",
        "heb" to "כך נשמע הקול הזה.",
        "tha" to "เสียงนี้ฟังดูเป็นแบบนี้",
        "hin" to "यह आवाज़ ऐसी सुनाई देती है।",
    )
}
