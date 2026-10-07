package com.watchreader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {
    @Test
    fun utf8WithBomIsDecodedWithoutTheMark() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "春眠不觉晓".toByteArray(Charsets.UTF_8)
        val decoded = TextNormalizer.decode(bytes)
        assertEquals("春眠不觉晓", decoded.text)
        assertEquals("UTF-8", decoded.charset)
    }

    @Test
    fun gbkBytesFallBackToGb18030() {
        val bytes = "处处闻啼鸟。夜来风雨声".toByteArray(charset("GBK"))
        val decoded = TextNormalizer.decode(bytes)
        assertEquals("处处闻啼鸟。夜来风雨声", decoded.text)
        assertEquals("GB18030", decoded.charset)
    }

    @Test
    fun big5BytesAreReadAsTraditionalChineseNotAsGb18030() {
        val text = "處處聞啼鳥。夜來風雨聲，花落知多少。春眠不覺曉，這是一個很好的早晨，我們都在這裡。"
        val decoded = TextNormalizer.decode(text.toByteArray(charset("Big5")))
        assertEquals(text, decoded.text)
        assertEquals("Big5", decoded.charset)
    }

    @Test
    fun shiftJisBytesAreReadAsJapanese() {
        val text = "吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。"
        val decoded = TextNormalizer.decode(text.toByteArray(charset("Shift_JIS")))
        assertEquals(text, decoded.text)
        assertEquals("Shift_JIS", decoded.charset)
    }

    @Test
    fun declaredCharsetWinsWhenItDecodesCleanly() {
        val bytes = "flödet är ök".toByteArray(Charsets.ISO_8859_1)
        assertEquals("flödet är ök", TextNormalizer.decode(bytes, "ISO-8859-1").text)
    }

    @Test
    fun lineEndingsAndBlankRunsCollapse() {
        val text = "第一章\r\n\r\n\r\n\r\n正文一行   \r\n第二行\r\n\r\n\r\n"
        assertEquals("第一章\n\n正文一行\n第二行", TextNormalizer.normalize(text))
    }

    private val chinese = "春眠不觉晓，处处闻啼鸟。夜来风雨声，花落知多少。这是一个很好的早晨，我们都在这里读书。"

    @Test
    fun utf8WithAStrayBadByteIsStillUtf8() {
        val good = chinese.repeat(20).toByteArray(Charsets.UTF_8)
        val bytes = good.copyOfRange(0, 300) + byteArrayOf(0xFF.toByte()) + good.copyOfRange(300, good.size)
        val decoded = TextNormalizer.decode(bytes)
        assertEquals("UTF-8", decoded.charset)
        assertEquals(chinese.repeat(20), decoded.text)
    }

    @Test
    fun aLongerGbkBookIsStillGb18030() {
        val text = "第一章 风雨\n\n" + chinese.repeat(30) + "\n\nChapter 2, with some ASCII in it.\n\n" + chinese.repeat(30)
        val decoded = TextNormalizer.decode(text.toByteArray(charset("GBK")))
        assertEquals("GB18030", decoded.charset)
        assertEquals(text, decoded.text)
    }

    @Test
    fun westernBytesFallBackToWindows1252WithTheirQuotes() {
        val text = "\u201CHello,\u201D she said \u2014 it\u2019s late, and the caf\u00E9 is shut."
        val decoded = TextNormalizer.decode(text.toByteArray(charset("windows-1252")))
        assertEquals("windows-1252", decoded.charset)
        assertEquals(text, decoded.text)
    }

    /** [text] saved in [charset] and read back with no label on it. */
    private fun assertDetected(text: String, charset: String) {
        val decoded = TextNormalizer.decode(text.toByteArray(charset(charset)))
        assertEquals(text.take(40), charset, decoded.charset)
        assertEquals(text, decoded.text)
    }

    private val czech = "Ráno bylo chladné a nad řekou ležela mlha. Starý převozník Jiří vytáhl loďku na břeh, " +
        "sedl si na převrácený kbelík a dlouho se díval, jak se světlo pomalu prodírá mezi stromy. Věděl, že dnes " +
        "nikdo nepřijde, ale přesto čekal, protože čekání bylo to jediné, co mu ještě zůstalo. Když se slunce " +
        "konečně ukázalo nad kopcem, vstal, oprášil si kalhoty a vydal se k vesnici, kde už voněl čerstvý chléb " +
        "a ze dvorů se ozýval štěkot psů. Šťastný ten, kdo má kam jít."

    private val polish = "Wieczorem nad jeziorem zapadła cisza. Stary rybak Józef usiadł na pomoście, zapalił fajkę " +
        "i patrzył, jak księżyc powoli wyłania się zza drzew. Żona wołała go już dwa razy, ale udawał, że nie słyszy, " +
        "bo właśnie w takich chwilach czuł się naprawdę szczęśliwy. Łódź kołysała się łagodnie przy brzegu, a gdzieś " +
        "daleko szczekał pies. Gdy w końcu wstał, było już zupełnie ciemno i tylko światło w oknie chaty wskazywało " +
        "mu drogę do domu."

    private val slovak = "Ráno bolo chladné a nad riekou ležala hmla. Starý prievozník Ľudovít vytiahol loďku na breh, " +
        "sadol si na prevrátené vedro a dlho sa díval, ako sa svetlo pomaly prediera pomedzi stromy. Vedel, že dnes " +
        "nikto nepríde, ale aj tak čakal, pretože čakanie bolo to jediné, čo mu ešte zostalo. Potom vstal a pomaly " +
        "kráčal k dedine, kde už voňal chlieb."

    private val hungarian = "Reggel hideg volt, és köd ült a folyó fölött. Az öreg révész kihúzta a csónakot a partra, " +
        "leült egy felfordított vödörre, és sokáig nézte, ahogy a fény lassan átszűrődik a fák között. Tudta, hogy " +
        "ma már senki sem jön, mégis várt, mert a várakozás volt az egyetlen, ami még megmaradt neki. Később " +
        "felállt, és a hűvös szélben hazaindult a falu felé, ahol már égtek a lámpák az ablakokban."

    private val russian = "Вечером над рекой поднялся туман. Старый лодочник Степан вытащил лодку на берег, сел на " +
        "перевёрнутое ведро и долго смотрел, как в окнах деревни один за другим зажигаются огни. Он знал, что " +
        "сегодня уже никто не придёт, но всё равно ждал, потому что ожидание было единственным, что у него ещё " +
        "осталось. Когда стало совсем темно, он поднялся, отряхнул штаны и медленно пошёл домой, где его ждали " +
        "горячий чай и старая кошка, спавшая на печи. «Завтра будет лучше», — подумал он."

    private val ukrainian = "Увечері над річкою піднявся туман. Старий човняр Степан витягнув човен на берег, сів на " +
        "перевернуте відро і довго дивився, як у вікнах села одне за одним запалюються вогні. Він знав, що " +
        "сьогодні вже ніхто не прийде, але все одно чекав, бо чекання було єдиним, що в нього ще залишилося. Коли " +
        "стало зовсім темно, він підвівся, обтрусив штани і повільно пішов додому, де на нього чекали гарячий чай, " +
        "свіжий хліб і стара кішка, що спала на печі. Їй було байдуже до ґанку й до гостей, бо єдине, що її цікавило, " +
        "був теплий куток."

    private val bulgarian = "Вечерта над реката се вдигна мъгла. Старият лодкар Стефан изтегли лодката на брега, " +
        "седна на обърнатата кофа и дълго гледа как в прозорците на селото една след друга светват лампите. " +
        "Знаеше, че днес никой няма да дойде, но въпреки това чакаше, защото чакането беше единственото, което " +
        "му беше останало."

    @Test
    fun centralEuropeanBooksAreReadAsWindows1250() {
        for (text in listOf(czech, polish, slovak, hungarian)) assertDetected(text, "windows-1250")
    }

    @Test
    fun cyrillicBooksAreReadAsWindows1251() {
        for (text in listOf(russian, ukrainian, bulgarian)) assertDetected(text, "windows-1251")
    }

    /** KOI8 has no guillemets or long dash; books saved in it make do with plain quotes and hyphens. */
    private fun koi8(text: String) = text.replace('«', '"').replace('»', '"').replace('—', '-')

    @Test
    fun russianAndBulgarianInKoi8AreReadAsKoi8R() {
        assertDetected(koi8(russian), "KOI8-R")
        assertDetected(bulgarian, "KOI8-R")
    }

    @Test
    fun ukrainianInKoi8IsReadAsKoi8U() {
        // KOI8-R has box-drawing characters where KOI8-U keeps і, ї, є and ґ.
        assertDetected(ukrainian, "KOI8-U")
    }

    @Test
    fun aLongerCyrillicBookKeepsItsLinesAndChapters() {
        val book = "Глава первая\n\n" + russian + "\n\n" + "Глава вторая\n\n" + russian
        assertDetected(book, "windows-1251")
        assertDetected(koi8(book), "KOI8-R")
    }

    @Test
    fun shortLinesAnEastAsianDecoderAlsoAcceptsAreStillReadInTheirCodePage() {
        // GB18030 and Big5 take every one of these without complaint, as a handful of odd characters.
        assertDetected("Řekni mi, kde je tvůj dům.", "windows-1250")
        assertDetected("Он сказал: да.", "windows-1251")
        assertDetected("Он сказал: да.", "KOI8-R")
        // So does GB18030 a book whose only curly marks are its apostrophes, each followed by a letter.
        assertDetected("Mr. Darcy’s letter was Elizabeth’s first thought, and Jane’s second.", "windows-1252")
    }

    /** One paragraph each in the Western languages Windows-1252 and ISO-8859-1 were made for. */
    private val western = listOf(
        "Le soir tombait sur la rivière et le vieux passeur tirait sa barque sur la rive. Il s’assit sur un seau " +
            "renversé et regarda longtemps les lumières du village s’allumer une à une. « Où est passé l’été ? » " +
            "se demanda-t-il, en écoutant les chiens aboyer derrière les fenêtres déjà éclairées. Ça ne finirait jamais.",
        "Am Abend lag Nebel über dem Fluss. Der alte Fährmann zog sein Boot ans Ufer, setzte sich auf einen " +
            "umgedrehten Eimer und sah lange zu, wie in den Fenstern des Dorfes ein Licht nach dem anderen anging. " +
            "Später ging er müde über die Brücke nach Hause, wo schon der Ofen glühte und es nach Gebäck roch.",
        "Al atardecer, la niebla cubría el río. El viejo barquero arrastró su bote hasta la orilla, se sentó sobre " +
            "un cubo volcado y miró durante mucho tiempo cómo se encendían las luces del pueblo. ¿Quién recordaría " +
            "su nombre mañana? ¡Nadie, pensó, y sonrió en la penumbra del 2º piso!",
        "Ao entardecer, a névoa cobria o rio. O velho barqueiro puxou o barco para a margem, sentou-se num balde " +
            "virado e ficou muito tempo a olhar as luzes da aldeia. Não havia pressa: a noite é longa e o coração, " +
            "às vezes, também. Ninguém viria mais, mas ele esperava, porque a espera é a única coisa que lhe restava.",
        "Al tramonto la nebbia copriva il fiume. Il vecchio traghettatore tirò la barca sulla riva, si sedette su " +
            "un secchio rovesciato e guardò a lungo le luci del villaggio. Così passò un’altra sera, e la città " +
            "laggiù sembrò più lontana che mai. Perché nessuno veniva? Era già notte, e lui non sapeva più cosa fare.",
        "På kvällen låg dimman över älven. Den gamle färjkarlen drog upp båten på stranden, satte sig på en " +
            "uppochnedvänd hink och såg länge på hur ljusen tändes i byns fönster. Snart blev det mörkt, och han " +
            "gick långsamt hem över den frusna ängen.",
        "Om aftenen lå tågen over åen. Den gamle færgemand trak båden op på bredden, satte sig på en væltet spand " +
            "og så længe på, hvordan lysene blev tændt i landsbyens vinduer. Til sidst gik han hjem over den kolde " +
            "mark, hvor køerne stod og sov, og døren knirkede, da han åbnede den.",
        "Um kvöldið lá þokan yfir ánni. Gamli ferjumaðurinn dró bátinn upp á bakkann, settist á fötu sem sneri " +
            "öfugt og horfði lengi á ljósin kvikna í gluggum þorpsins, hvert á fætur öðru. Hann vissi að enginn " +
            "myndi koma í dag, en hann beið samt, því biðin var það eina sem hann átti eftir.",
        "Het was een koude avond en de oude veerman zag hoe de lichtjes één voor één aangingen. Hij dacht aan " +
            "zijn café in Brussel, aan de geïnteresseerde blikken van de reünie, en aan zijn zoon die naar Zürich " +
            "was vertrokken.",
    )

    @Test
    fun westernBooksAreStillReadAsWindows1252() {
        for (text in western) assertDetected(text, "windows-1252")
        // ISO-8859-1 has the same letters in the same places.
        for (text in western.filter { it.all { c -> c.code < 0x100 } }) {
            val decoded = TextNormalizer.decode(text.toByteArray(Charsets.ISO_8859_1))
            assertEquals("windows-1252", decoded.charset)
            assertEquals(text, decoded.text)
        }
    }

    @Test
    fun anEnglishBookWithAFewStraySignsIsStillWindows1252() {
        // Each of these reads as a Polish or Czech letter in Windows-1250 (m³ as mł, £ as Ł), but
        // a handful in a whole book is no Polish.
        val book = (1..6).flatMap { paragraphs }.joinToString("\n\n") +
            "\n\nThe room measured 40 m³ and cost £ 12 a week, ½ of it paid in advance.¹"
        val decoded = TextNormalizer.decode(book.toByteArray(charset("windows-1252")))
        assertEquals("windows-1252", decoded.charset)
        assertEquals(book, decoded.text)
    }

    @Test
    fun eastAsianBooksAreNotTakenForCodePages() {
        val chineseBook = "第一章 风雨\n\n" + chinese.repeat(30) + "\n\nChapter 2, with some ASCII in it.\n\n" + chinese.repeat(30)
        assertEquals("GB18030", TextNormalizer.decode(chineseBook.toByteArray(charset("GBK"))).charset)
        val traditional = "處處聞啼鳥。夜來風雨聲，花落知多少。春眠不覺曉，這是一個很好的早晨，我們都在這裡。"
        assertEquals("Big5", TextNormalizer.decode(traditional.repeat(20).toByteArray(charset("Big5"))).charset)
        val japanese = "吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。"
        assertEquals("Shift_JIS", TextNormalizer.decode(japanese.repeat(20).toByteArray(charset("Shift_JIS"))).charset)
    }

    @Test
    fun aDeclaredLatin1IsReadAsWindows1252() {
        val text = "It\u2019s the caf\u00E9 on the corner."
        assertEquals(text, TextNormalizer.decode(text.toByteArray(charset("windows-1252")), "ISO-8859-1").text)
    }

    @Test
    fun c1ControlCodesNeverSurviveNormalize() {
        assertEquals("its \u201Cgone\u201D", TextNormalizer.normalize("it\u0092s\u0085 \u201Cgone\u201D\u0097\u007F"))
    }

    @Test
    fun binaryFilesAreNotText() {
        // A Kindle book opens with a Palm database header: a name padded with zero bytes.
        val mobi = "Some Book".toByteArray() + ByteArray(23) + "BOOKMOBI".toByteArray() + ByteArray(200) { (it * 7).toByte() }
        assertTrue(TextNormalizer.looksBinary(mobi))
        // Compressed data without a single zero byte still has control codes all through it.
        val noisy = ByteArray(4096) { i -> if (i % 9 == 0) 0x05 else (0x41 + i % 26).toByte() }
        assertTrue(TextNormalizer.looksBinary(noisy))
    }

    @Test
    fun textInAnyEncodingIsNotBinary() {
        assertFalse(TextNormalizer.looksBinary(chinese.toByteArray(Charsets.UTF_8)))
        assertFalse(TextNormalizer.looksBinary(chinese.toByteArray(charset("GBK"))))
        assertFalse(TextNormalizer.looksBinary("Line one\r\n\tLine two\u000C\r\n".toByteArray()))
        val utf16WithBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + chinese.toByteArray(Charsets.UTF_16LE)
        assertFalse(TextNormalizer.looksBinary(utf16WithBom))
    }

    @Test
    fun utf16WithoutAMarkIsTextAndDecodes() {
        val text = "A plain English book, saved as UTF-16 by a Windows tool: caf\u00E9."
        val bytes = text.toByteArray(Charsets.UTF_16LE)
        assertFalse(TextNormalizer.looksBinary(bytes))
        assertEquals(text, TextNormalizer.decode(bytes).text)
    }

    /** Words set to lines no wider than [width], the way Project Gutenberg sets its books. */
    private fun wrap(paragraph: String, width: Int = 70): String {
        val lines = ArrayList<String>()
        var line = StringBuilder()
        for (word in paragraph.split(' ')) {
            if (line.isNotEmpty() && line.length + 1 + word.length > width) {
                lines.add(line.toString())
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        lines.add(line.toString())
        return lines.joinToString("\n")
    }

    private val paragraphs = listOf(
        "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife. However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well fixed in the minds of the surrounding families, that he is considered as the rightful property of some one or other of their daughters.",
        "My dear Mr. Bennet, said his lady to him one day, have you heard that Netherfield Park is let at last? Mr. Bennet replied that he had not, and he went back to his paper without another word.",
        "But it is, returned she; for Mrs. Long has just been here, and she told me all about it, and she would not stop talking until the tea had gone quite cold in the pot beside her chair.",
    )

    @Test
    fun hardWrappedParagraphsAreJoinedBackTogether() {
        val book = (1..6).flatMap { paragraphs }.joinToString("\n\n") { wrap(it) }
        val decoded = TextNormalizer.decode(book.toByteArray()).text
        assertEquals((1..6).flatMap { paragraphs }.joinToString("\n\n"), decoded)
    }

    @Test
    fun indentedVerseAndShortLinesKeepTheirBreaks() {
        val verse = "    Shall I compare thee to a summer's day, thou art more lovely and more temperate,\n" +
            "    Rough winds do shake the darling buds of May, and summer's lease hath all too short a date."
        val book = (1..6).flatMap { paragraphs }.joinToString("\n\n") { wrap(it) } + "\n\n" + verse + "\n\nTHE END\nFinis"
        val unwrapped = TextNormalizer.unwrap(TextNormalizer.normalize(book))
        assertTrue(unwrapped.endsWith(verse + "\n\nTHE END\nFinis"))
    }

    @Test
    fun aParagraphToALineIsNotWrapped() {
        // Short paragraphs one to a line, no blank lines between: long lines that run straight
        // on, but of every length rather than broken at one width.
        val lengths = (0 until 60).map { 50 + (it * 37) % 56 }
        val book = lengths.joinToString("\n") { n -> ("She said nothing for a while and then " + "went on ".repeat(20)).take(n).trimEnd() + "." }
        assertEquals(book, TextNormalizer.unwrap(book))
    }

    @Test
    fun chineseLinesAreNeverJoined() {
        val book = (1..60).joinToString("\n") { chinese.repeat(2) }
        assertEquals(book, TextNormalizer.unwrap(book))
    }

    @Test
    fun aShortNoteIsLeftAsItIs() {
        val note = wrap(paragraphs[0])
        assertEquals(note, TextNormalizer.unwrap(note))
    }
}
