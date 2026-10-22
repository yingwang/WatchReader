package com.watchreader.mobile

import com.watchreader.mobile.tts.ReadAloudPrefs
import com.watchreader.mobile.tts.VoiceChoices
import com.watchreader.mobile.tts.VoiceChoices.Gender.FEMALE
import com.watchreader.mobile.tts.VoiceChoices.Gender.MALE
import com.watchreader.mobile.tts.VoiceChoices.Label
import com.watchreader.mobile.tts.VoiceChoices.Listed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoiceChoicesTest {
    private val us = Locale.forLanguageTag("en-US")
    private val uk = Locale.forLanguageTag("en-GB")
    private val australia = Locale.forLanguageTag("en-AU")

    /** Google's two copies of [code], as its engine lists them. */
    private fun google(locale: Locale, code: String, local: Boolean = true, network: Boolean = true): List<Listed> {
        val prefix = locale.toLanguageTag().lowercase(Locale.ROOT) + "-x-" + code
        return listOfNotNull(
            Listed("$prefix-local", locale, network = false).takeIf { local },
            Listed("$prefix-network", locale, network = true).takeIf { network },
        )
    }

    @Test fun theLocalAndNetworkCopiesOfAVoiceAreOneChoiceThatReadsOffline() {
        val voices = google(us, "iob") + google(us, "iol", local = false) +
            Listed("en-US-language", us, network = false) +
            // Google lists a voice still to be fetched as not installed; its server copy can speak meanwhile.
            Listed("en-us-x-tpd-local", us, network = false, installed = false) + Listed("en-us-x-tpd-network", us, network = true) +
            // Neither copy can speak yet: nothing to offer.
            Listed("en-us-x-tpf-local", us, network = false, installed = false) + Listed("en-us-x-tpf-network", us, network = true, installed = false)
        val choices = VoiceChoices.group(voices)

        assertEquals(listOf("en-us-x-iob", "en-us-x-iol", "en-us-x-tpd"), choices.map { it.key })
        assertEquals(listOf("en-us-x-iob-local", "en-us-x-iol-network", "en-us-x-tpd-network"), choices.map { it.name })
        assertEquals(listOf(false, true, true), choices.map { it.needsInternet })
        // The region's stand-in for its default voice is not a choice of its own: "Default" is.
        assertTrue(choices.none { it.name == "en-US-language" })
        // The order does not depend on the engine's.
        assertEquals(choices, VoiceChoices.group(voices.reversed()))
    }

    @Test fun voicesAreNamedByGenderAndNumberedWomenFirstThenMen() {
        val codes = listOf("tpf", "iom", "iob", "tpd", "sfg", "iog", "iol", "tpc")
        val choices = VoiceChoices.group(codes.flatMap { google(us, it) })
        assertEquals(
            listOf("iob", "iog", "sfg", "tpc", "tpf", "iol", "iom", "tpd"),
            choices.map { it.key.substringAfterLast("-x-") },
        )
        assertEquals(
            listOf(
                Label(FEMALE, 1, null), Label(FEMALE, 2, null), Label(FEMALE, 3, null), Label(FEMALE, 4, null), Label(FEMALE, 5, null),
                Label(MALE, 1, null), Label(MALE, 2, null), Label(MALE, 3, null),
            ),
            VoiceChoices.labels(choices),
        )
    }

    @Test fun aVoiceWhoseGenderIsNotKnownIsNumberedOnItsOwnAfterTheRest() {
        val voices = google(us, "iob") + google(us, "zzz") +
            Listed("en-US-SMTl03", us, network = false) + google(us, "iom")
        val choices = VoiceChoices.group(voices)
        assertEquals(listOf("en-us-x-iob", "en-us-x-iom", "en-us-smtl03", "en-us-x-zzz"), choices.map { it.key })
        assertEquals(
            listOf(Label(FEMALE, 1, null), Label(MALE, 1, null), Label(null, 1, null), Label(null, 2, null)),
            VoiceChoices.labels(choices),
        )
    }

    @Test fun aGenderTheEngineStatesIsBelievedBeforeTheTable() {
        val choices = VoiceChoices.group(
            listOf(
                // An engine that names the gender among a voice's features.
                Listed("acme-one", us, network = false, features = setOf("networkTimeoutMs", "gender=male")),
                Listed("acme-two", us, network = false, features = setOf("female")),
                // Older versions of Google's engine put it in the name.
                Listed("en-us-x-sfg#male_1-local", us, network = false),
                Listed("en-us-x-sfg#female_2-local", us, network = false),
                // A feature saying both, or neither, says nothing; the table still knows iol.
                Listed("en-us-x-iol-local", us, network = false, features = setOf("male", "female")),
                Listed("acme-three", us, network = false, features = setOf("embeddedTts", "notInstalled_maybe")),
            ),
        )
        val genders = choices.associate { it.name to it.gender }
        assertEquals(MALE, genders["acme-one"])
        assertEquals(FEMALE, genders["acme-two"])
        assertEquals(MALE, genders["en-us-x-sfg#male_1-local"])
        assertEquals(FEMALE, genders["en-us-x-sfg#female_2-local"])
        assertEquals(MALE, genders["en-us-x-iol-local"])
        assertNull(genders["acme-three"])
    }

    @Test fun theRegionIsAddedOnlyWhenTheLanguageHasVoicesFromSeveral() {
        val oneRegion = VoiceChoices.group(google(uk, "gba") + google(uk, "rjs"))
        assertEquals(listOf(Label(FEMALE, 1, null), Label(MALE, 1, null)), VoiceChoices.labels(oneRegion))

        val several = VoiceChoices.group(google(us, "iob") + google(us, "iog") + google(uk, "gba") + google(uk, "rjs") + google(australia, "aub"))
        assertEquals(
            listOf(
                // Numbered within each region, and the regions in their short English names.
                Label(MALE, 1, "Australia"),
                Label(FEMALE, 1, "UK"), Label(MALE, 1, "UK"),
                Label(FEMALE, 1, "US"), Label(FEMALE, 2, "US"),
            ),
            VoiceChoices.labels(several),
        )
        // The region of the language as the book is read in it comes first.
        val usFirst = VoiceChoices.group(google(uk, "gba") + google(us, "iob") + google(australia, "aub") + google(us, "iom"), first = us)
        assertEquals(listOf("US", "US", "Australia", "UK"), VoiceChoices.labels(usFirst).map { it.region })
        assertEquals(listOf(Label(FEMALE, 1, "US"), Label(MALE, 1, "US")), VoiceChoices.labels(usFirst).take(2))
        assertNull(VoiceChoices.regionName(Locale.ENGLISH))
        assertEquals("Taiwan", VoiceChoices.regionName(Locale.TRADITIONAL_CHINESE))
    }

    @Test fun aSavedVoiceStillTicksItsChoiceWhicheverCopyWasSaved() {
        val choices = VoiceChoices.group(google(us, "iob") + google(us, "iol", local = false) + Listed("en-US-language", us, network = false))
        val iob = choices.first { it.key == "en-us-x-iob" }
        val iol = choices.first { it.key == "en-us-x-iol" }

        assertNull(VoiceChoices.selected(null, choices))
        assertEquals(iob, VoiceChoices.selected("en-us-x-iob-local", choices))
        // A network copy saved before the voice came onto the phone, or by another version.
        assertEquals(iob, VoiceChoices.selected("en-us-x-iob-network", choices))
        assertEquals(iol, VoiceChoices.selected("en-us-x-iol-network", choices))
        // A local copy saved when it was there, now offered from the server only.
        assertEquals(iol, VoiceChoices.selected("en-us-x-iol-local", choices))
        // The region's stand-in, and a voice gone with an engine update, leave "Default" ticked.
        assertNull(VoiceChoices.selected("en-US-language", choices))
        assertNull(VoiceChoices.selected("en-us-x-gone-local", choices))
    }

    @Test fun aSavedVoiceIsReadInTheCopyItWouldBeOfferedIn() {
        val voices = google(us, "iob") + google(us, "iol", local = false) + Listed("en-US-language", us, network = false)
        assertEquals("en-us-x-iob-local", VoiceChoices.resolve("en-us-x-iob-network", voices))
        assertEquals("en-us-x-iob-local", VoiceChoices.resolve("en-us-x-iob-local", voices))
        assertEquals("en-us-x-iol-network", VoiceChoices.resolve("en-us-x-iol-local", voices))
        // A saved voice that is not among the choices is still used while the engine lists it.
        assertEquals("en-US-language", VoiceChoices.resolve("en-US-language", voices))
        assertNull(VoiceChoices.resolve("en-us-x-gone-local", voices))
    }

    @Test fun theChoiceIsSavedAsTheVoicesNameAsBefore() {
        val stored = HashMap<String, Any?>()
        // A choice saved by the version before, which offered only the copies on the phone.
        stored["tts_voice_eng"] = "en-us-x-iob-local"
        val prefs = ReadAloudPrefs(FakePreferences(stored))
        val choices = VoiceChoices.group(google(us, "iob") + google(us, "iom"))
        assertEquals("en-us-x-iob", VoiceChoices.selected(prefs.voice(us), choices)?.key)

        val iom = choices.first { it.key == "en-us-x-iom" }
        prefs.setVoice(us, iom.name)
        assertEquals("en-us-x-iom-local", stored["tts_voice_eng"])
        assertEquals(iom, VoiceChoices.selected(ReadAloudPrefs(FakePreferences(stored)).voice(uk), choices))
    }

    @Test fun theTableKnowsTheCommonLanguagesAndOnlyGoogleCodes() {
        val prefixes = VoiceChoices.GENDERS.keys.map { it.substringBefore("-x-") }.toSet()
        for (needed in listOf("en-us", "en-gb", "en-au", "en-in", "cmn-cn", "cmn-tw", "yue-hk", "ja-jp", "ko-kr", "de-de", "fr-fr", "es-es", "it-it", "pt-br", "nl-nl", "sv-se", "ru-ru")) {
            assertTrue(needed, needed in prefixes)
        }
        val code = Regex("^[a-z]{2,3}-[a-z]{2}-x-[a-z]{3}$")
        assertTrue(VoiceChoices.GENDERS.keys.all { code.matches(it) })
        assertEquals(FEMALE, VoiceChoices.GENDERS["cmn-cn-x-ccc"])
        assertEquals(MALE, VoiceChoices.GENDERS["sv-se-x-cmh"])
    }

    @Test fun theSampleIsSaidInTheVoicesOwnLanguage() {
        assertEquals("This is how this voice sounds.", VoiceChoices.sample(us))
        assertEquals("Så här låter den här rösten.", VoiceChoices.sample(Locale.forLanguageTag("sv-SE")))
        assertEquals("这个声音听起来是这样的。", VoiceChoices.sample(Locale.SIMPLIFIED_CHINESE))
        assertEquals("這個聲音聽起來是這樣的。", VoiceChoices.sample(Locale.TRADITIONAL_CHINESE))
        assertEquals("Slik høres denne stemmen ut.", VoiceChoices.sample(Locale.forLanguageTag("nb-NO")))
        assertEquals("כך נשמע הקול הזה.", VoiceChoices.sample(Locale.forLanguageTag("he-IL")))
        // A language without a sentence of its own says its name for itself.
        val icelandic = VoiceChoices.sample(Locale.forLanguageTag("is-IS"))
        assertEquals(Locale.forLanguageTag("is").getDisplayLanguage(Locale.forLanguageTag("is")), icelandic)
        assertFalse(icelandic.isEmpty())
    }
}
