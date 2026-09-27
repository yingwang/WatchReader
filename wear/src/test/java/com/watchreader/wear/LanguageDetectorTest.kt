package com.watchreader.wear

import com.watchreader.wear.tts.LanguageDetector
import com.watchreader.wear.tts.LanguageDetector.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LanguageDetectorTest {
    /** The detector as it stood before other languages were added: Chinese or English, nothing else. */
    private fun before(text: String): Locale {
        val cjkCount = text.count { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
        val total = text.count { it.isLetterOrDigit() }
        return if (total > 0 && cjkCount.toFloat() / total > 0.3f) Locale.SIMPLIFIED_CHINESE else Locale.US
    }

    @Test
    fun chineseAndEnglishSentencesGoWhereTheyAlwaysWent() {
        val sentences = listOf(
            "他说：“走吧。”", "我今天用iPhone拍了很多照片。", "iPhone 15 Pro Max 发布了", "他说 OK。",
            "The word 你好 means hello.", "Hello 世界", "第3章", "2024年", "1942", "……", "", "abc", "中",
            "列夫・托尔斯泰写了很多书。", "It was the best of times.", "Microsoft 和 Google 都在做 AI 助手",
            "《哈利·波特》的作者是 J. K. Rowling。",
        )
        for (s in sentences) {
            assertEquals(s, before(s), LanguageDetector.detect(s))
            // The same holds in a book that surveys as Chinese with some English in it.
            assertEquals(s, before(s), LanguageDetector.detect(s, Book(latin = LanguageDetector.ENGLISH)))
        }
    }

    @Test
    fun kanaMakesASentenceJapaneseAndKanjiAloneStaysChinese() {
        assertEquals(LanguageDetector.JAPANESE, LanguageDetector.detect("吾輩は猫である。名前はまだ無い。"))
        assertEquals(LanguageDetector.JAPANESE, LanguageDetector.detect("ありがとうございます。"))
        assertEquals(LanguageDetector.JAPANESE, LanguageDetector.detect("コーヒーをください。"))
        assertEquals(LanguageDetector.JAPANESE, LanguageDetector.detect("ｺｰﾋｰ"))
        assertEquals(LanguageDetector.CHINESE, LanguageDetector.detect("第一章"))
        assertEquals(LanguageDetector.CHINESE, LanguageDetector.detect("東京大学"))
        // In a book that is Japanese throughout, a line of kanji alone is Japanese too.
        assertEquals(LanguageDetector.JAPANESE, LanguageDetector.detect("第一章", Book(kanjiIsJapanese = true)))
        // A kana word in the middle of an English sentence does not hand it to a Japanese voice.
        assertEquals(LanguageDetector.ENGLISH, LanguageDetector.detect("She ordered a bowl of ラーメン at the station."))
    }

    @Test
    fun otherScriptsAreToldApartSentenceBySentence() {
        assertEquals(LanguageDetector.KOREAN, LanguageDetector.detect("나는 오늘 Google에서 일했다."))
        assertEquals(LanguageDetector.RUSSIAN, LanguageDetector.detect("Все счастливые семьи похожи друг на друга."))
        assertEquals(LanguageDetector.RUSSIAN, LanguageDetector.detect("В 1942 году он купил iPhone."))
        assertEquals(LanguageDetector.GREEK, LanguageDetector.detect("Εν αρχή ην ο λόγος."))
        assertEquals(LanguageDetector.ARABIC, LanguageDetector.detect("كان يا ما كان في قديم الزمان."))
        assertEquals(LanguageDetector.HEBREW, LanguageDetector.detect("בראשית ברא אלוהים את השמיים ואת הארץ."))
        assertEquals(LanguageDetector.THAI, LanguageDetector.detect("กาลครั้งหนึ่งนานมาแล้ว"))
        assertEquals(LanguageDetector.HINDI, LanguageDetector.detect("एक समय की बात है।"))
        // A single foreign word leaves the sentence to its own language.
        assertEquals(LanguageDetector.ENGLISH, LanguageDetector.detect("The Greek word λόγος has many meanings in English."))
        assertEquals(LanguageDetector.FRENCH, LanguageDetector.detect("Il a dit « спасибо » avant de partir.", Book(latin = LanguageDetector.FRENCH)))
    }

    @Test
    fun latinSentencesAreReadInTheBooksLatinLanguage() {
        val french = Book(latin = LanguageDetector.FRENCH)
        assertEquals(LanguageDetector.FRENCH, LanguageDetector.detect("Elle ouvrit la porte.", french))
        assertEquals(LanguageDetector.CHINESE, LanguageDetector.detect("她打开了门。", french))
        // Nothing to go by but digits: the book's Latin language, as it was English before.
        assertEquals(LanguageDetector.FRENCH, LanguageDetector.detect("1942.", french))
    }

    @Test
    fun theLatinLanguageOfEachBookIsGuessedFromItsCommonWords() {
        for ((locale, paragraph) in PARAGRAPHS) {
            assertEquals(locale.toString(), locale, LanguageDetector.latinLanguage(paragraph))
        }
    }

    @Test
    fun tooLittleToGoOnIsEnglish() {
        assertEquals(LanguageDetector.ENGLISH, LanguageDetector.latinLanguage(""))
        assertEquals(LanguageDetector.ENGLISH, LanguageDetector.latinLanguage("Merci."))
        assertEquals(LanguageDetector.ENGLISH, LanguageDetector.latinLanguage("他说了很多话，可是没有人听。"))
        // A language the reader has no list for does not borrow a neighbour's voice on a few words.
        val indonesian = "Hari sudah sore ketika dia akhirnya sampai di rumah tua di ujung jalan. Kebun itu " +
            "sudah menjadi liar selama bertahun-tahun, dan jendelanya gelap karena debu. Dia berdiri sebentar " +
            "di dekat pagar, bertanya-tanya apakah masih ada orang yang tinggal di sana, lalu dia berjalan " +
            "menyusuri jalan setapak dan mengetuk pintu dua kali. Tidak ada yang menjawab, tetapi dia bisa " +
            "mendengar musik di suatu tempat di dalam rumah, pelan dan lambat."
        assertEquals(LanguageDetector.ENGLISH, LanguageDetector.latinLanguage(indonesian))
    }

    @Test
    fun aBookInAnotherScriptReadsItsLatinWordsInEnglish() {
        // Names in pinyin look like a little French or Italian ("si", "ma", "de") to the word lists.
        val pinyin = "张三（Zhang San）和李四（Li Si）去了北京（Beijing）。王五（Wang Wu）留在上海（Shanghai）。".repeat(40)
        assertEquals(LanguageDetector.ENGLISH, LanguageDetector.survey(pinyin).latin)
        // A reader for learners spells every word out; there are more Latin letters than Han.
        val annotated = "我 wǒ 的 de 朋友 péngyou 来 lái 了 le 吗 ma？他 tā 是 shì 我 wǒ 的 de 老师 lǎoshī。".repeat(40)
        assertEquals(LanguageDetector.ENGLISH, LanguageDetector.survey(annotated).latin)
        val quoting = "他在巴黎住了三年，每天早上都去同一家咖啡馆。".repeat(30) + PARAGRAPHS.getValue(LanguageDetector.FRENCH)
        assertEquals(LanguageDetector.ENGLISH, LanguageDetector.survey(quoting).latin)
        val russian = "Все счастливые семьи похожи друг на друга, каждая несчастливая семья несчастлива по-своему. ".repeat(20) +
            PARAGRAPHS.getValue(LanguageDetector.GERMAN)
        assertEquals(LanguageDetector.ENGLISH, LanguageDetector.survey(russian).latin)
        assertEquals(LanguageDetector.GERMAN, LanguageDetector.survey(PARAGRAPHS.getValue(LanguageDetector.GERMAN)).latin)
    }

    @Test
    fun anEnglishPrefaceDoesNotOutvoteTheBook() {
        val preface = PARAGRAPHS.getValue(LanguageDetector.ENGLISH).repeat(3)
        val book = preface + "\n\n" + PARAGRAPHS.getValue(LanguageDetector.FRENCH).repeat(40)
        assertEquals(LanguageDetector.FRENCH, LanguageDetector.latinLanguage(book))
    }

    @Test
    fun theSampleIsBoundedAndTakesWholeWords() {
        val words = LanguageDetector.latinSample(PARAGRAPHS.getValue(LanguageDetector.GERMAN).repeat(500))
        assertTrue(words.sumOf { it.length } in 15_000..21_000)
        val known = PARAGRAPHS.getValue(LanguageDetector.GERMAN).lowercase().split(Regex("[^\\p{L}]+")).toSet()
        assertTrue(words.all { it in known })
    }

    @Test
    fun aSurveyTellsJapaneseBooksFromChineseOnes() {
        val japanese = "第一章\n吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。"
        assertTrue(LanguageDetector.survey(japanese).kanjiIsJapanese)
        val chinese = "他在书店里找到了一本日文书，封面上写着「ありがとう」。" + "他站在那里读了很久，直到天黑才回家。".repeat(5)
        assertFalse(LanguageDetector.survey(chinese).kanjiIsJapanese)
        assertEquals(Book(), LanguageDetector.survey("我今天用iPhone拍了很多照片。他说 OK，然后我们一起去吃饭了。"))
    }

    @Test
    fun theVoicesABookNeedsAreTheLanguagesItIsReadIn() {
        assertEquals(setOf(LanguageDetector.CHINESE), LanguageDetector.languagesIn("他说：“走吧。”她没有动。\n第一章\n天黑了。"))
        assertEquals(setOf(LanguageDetector.JAPANESE), LanguageDetector.languagesIn("第一章\n吾輩は猫である。名前はまだ無い。"))
        assertEquals(setOf(LanguageDetector.FRENCH), LanguageDetector.languagesIn(PARAGRAPHS.getValue(LanguageDetector.FRENCH)))
        assertEquals(
            setOf(LanguageDetector.RUSSIAN, LanguageDetector.ENGLISH),
            LanguageDetector.languagesIn("Все счастливые семьи похожи друг на друга. " + PARAGRAPHS.getValue(LanguageDetector.ENGLISH)),
        )
        // One Greek word in a long English book does not ask for a Greek voice.
        val english = PARAGRAPHS.getValue(LanguageDetector.ENGLISH).repeat(5) + " She wrote λόγος on the wall."
        assertEquals(setOf(LanguageDetector.ENGLISH), LanguageDetector.languagesIn(english))
    }

    @Test
    fun everyLanguageTheDetectorCanPickIsListed() {
        val picked = PARAGRAPHS.keys + setOf(
            LanguageDetector.CHINESE, LanguageDetector.JAPANESE, LanguageDetector.KOREAN, LanguageDetector.RUSSIAN,
            LanguageDetector.GREEK, LanguageDetector.ARABIC, LanguageDetector.HEBREW, LanguageDetector.THAI, LanguageDetector.HINDI,
        )
        assertEquals(picked, LanguageDetector.LANGUAGES.toSet())
        assertEquals(listOf(LanguageDetector.ENGLISH, LanguageDetector.CHINESE), LanguageDetector.LANGUAGES.take(2))
    }

    private companion object {
        val PARAGRAPHS = linkedMapOf(
            LanguageDetector.ENGLISH to "It was late in the afternoon when she finally reached the old house at the end of the lane. " +
                "The garden had grown wild over the years, and the windows were dark with dust. She stood for a while " +
                "by the gate, wondering whether anyone still lived there, and then she walked up the path and knocked " +
                "twice on the door. Nobody answered, but she could hear music somewhere inside, faint and slow, as if " +
                "it had been playing for a very long time.",
            LanguageDetector.FRENCH to "Il était tard dans l'après-midi quand elle arriva enfin devant la vieille maison au bout " +
                "du chemin. Le jardin était devenu sauvage avec les années, et les fenêtres étaient sombres de poussière. " +
                "Elle resta un moment près de la grille, en se demandant si quelqu'un y vivait encore, puis elle monta " +
                "l'allée et frappa deux fois à la porte. Personne ne répondit, mais elle entendait de la musique quelque " +
                "part à l'intérieur, faible et lente, comme si elle jouait depuis très longtemps.",
            LanguageDetector.GERMAN to "Es war schon später Nachmittag, als sie endlich das alte Haus am Ende des Weges erreichte. " +
                "Der Garten war mit den Jahren verwildert, und die Fenster waren dunkel vor Staub. Sie blieb eine Weile " +
                "am Tor stehen und fragte sich, ob dort noch jemand wohnte, dann ging sie den Weg hinauf und klopfte " +
                "zweimal an die Tür. Niemand antwortete, aber sie hörte irgendwo im Haus Musik, leise und langsam, als " +
                "ob sie schon sehr lange spielte.",
            LanguageDetector.SPANISH to "Era tarde cuando por fin llegó a la vieja casa al final del camino. El jardín se había " +
                "vuelto salvaje con los años, y las ventanas estaban oscuras de polvo. Se quedó un rato junto a la verja, " +
                "preguntándose si todavía vivía alguien allí, y luego subió por el sendero y llamó dos veces a la puerta. " +
                "Nadie contestó, pero podía oír música en algún lugar de la casa, débil y lenta, como si llevara mucho " +
                "tiempo sonando.",
            LanguageDetector.ITALIAN to "Era tardo pomeriggio quando finalmente arrivò alla vecchia casa in fondo al sentiero. " +
                "Il giardino era diventato selvatico con gli anni, e le finestre erano scure di polvere. Rimase per un " +
                "po' vicino al cancello, chiedendosi se qualcuno ci abitasse ancora, poi salì lungo il vialetto e bussò " +
                "due volte alla porta. Nessuno rispose, ma lei sentiva della musica da qualche parte dentro la casa, " +
                "debole e lenta, come se suonasse da moltissimo tempo.",
            LanguageDetector.PORTUGUESE to "Já era fim de tarde quando ela finalmente chegou à velha casa no fim do caminho. " +
                "O jardim tinha ficado selvagem com os anos, e as janelas estavam escuras de poeira. Ela ficou um tempo " +
                "junto ao portão, perguntando-se se alguém ainda morava ali, e depois subiu pelo caminho e bateu duas " +
                "vezes na porta. Ninguém respondeu, mas ela ouvia música em algum lugar dentro da casa, fraca e lenta, " +
                "como se estivesse tocando há muito tempo.",
            LanguageDetector.DUTCH to "Het was al laat in de middag toen ze eindelijk het oude huis aan het eind van het pad " +
                "bereikte. De tuin was in de loop van de jaren verwilderd en de ramen waren donker van het stof. Ze bleef " +
                "een tijdje bij het hek staan en vroeg zich af of er nog iemand woonde, en toen liep ze het pad op en " +
                "klopte twee keer op de deur. Niemand deed open, maar ze hoorde ergens binnen muziek, zacht en langzaam, " +
                "alsof die al heel lang speelde.",
            LanguageDetector.SWEDISH to "Det var sent på eftermiddagen när hon äntligen kom fram till det gamla huset vid slutet " +
                "av vägen. Trädgården hade vuxit igen med åren, och fönstren var mörka av damm. Hon stod en stund vid " +
                "grinden och undrade om någon fortfarande bodde där, och sedan gick hon upp längs gången och knackade " +
                "två gånger på dörren. Ingen svarade, men hon kunde höra musik någonstans inne i huset, svag och " +
                "långsam, som om den hade spelat mycket länge.",
            LanguageDetector.DANISH to "Det var sent på eftermiddagen, da hun endelig nåede frem til det gamle hus for enden af " +
                "vejen. Haven var groet til med årene, og vinduerne var mørke af støv. Hun stod et stykke tid ved lågen " +
                "og spekulerede på, om der stadig boede nogen, og så gik hun op ad stien og bankede to gange på døren. " +
                "Ingen svarede, men hun kunne høre musik et sted inde i huset, svag og langsom, som om den havde spillet " +
                "i meget lang tid.",
            LanguageDetector.NORWEGIAN to "Det var sent på ettermiddagen da hun endelig kom fram til det gamle huset ved enden " +
                "av veien. Hagen hadde grodd igjen med årene, og vinduene var mørke av støv. Hun ble stående en stund " +
                "ved grinda og lurte på om det fortsatt bodde noen der, og så gikk hun opp stien og banket to ganger på " +
                "døren. Ingen svarte, men hun kunne høre musikk et sted inne i huset, svak og langsom, som om den hadde " +
                "spilt veldig lenge.",
            LanguageDetector.FINNISH to "Oli jo myöhäinen iltapäivä, kun hän vihdoin saapui vanhalle talolle tien päässä. " +
                "Puutarha oli villiintynyt vuosien mittaan, ja ikkunat olivat pölystä tummat. Hän seisoi hetken portilla " +
                "ja mietti, asuiko siellä vielä joku, ja sitten hän käveli polkua ylös ja koputti kahdesti oveen. Kukaan " +
                "ei vastannut, mutta hän kuuli jostain sisältä musiikkia, hiljaista ja hidasta, kuin se olisi soinut jo " +
                "hyvin pitkään.",
            LanguageDetector.POLISH to "Było już późne popołudnie, kiedy wreszcie dotarła do starego domu na końcu drogi. " +
                "Ogród z latami zdziczał, a okna były ciemne od kurzu. Stała przez chwilę przy furtce i zastanawiała " +
                "się, czy ktoś jeszcze tam mieszka, a potem poszła ścieżką w górę i zapukała dwa razy do drzwi. Nikt " +
                "nie odpowiedział, ale słyszała gdzieś w środku muzykę, cichą i powolną, jakby grała już od bardzo dawna.",
        )
    }
}
