package com.watchreader.mobile.data.repository

import com.watchreader.mobile.data.model.FreeBook

/**
 * The free classics a library offers before the reader has any book of their own. Gutenberg's
 * most downloaded lists are a poor welcome: German's has erotica third, Russian's opens with a
 * book of mental arithmetic, and several are headed by dictionaries. So the phone's language
 * picks three well-known, family-safe books chosen by hand, kept here with the title and author
 * the card shows, so that it needs nothing from the network until a cover is drawn or a book is
 * added.
 *
 * Every number was checked on 7 October 2026 against the book's own feed on gutenberg.org:
 * public domain in the USA, in the language it is listed under, a text rather than a recording,
 * and with an epub under the import's 20 MB. Nothing here touches the network, so all of it is
 * tested on the JVM.
 */
internal object StarterShelf {
    /** How many classics the card offers. */
    const val COUNT = 3

    /** Gutenberg's query word for fiction, which keeps reference books out of a language's list. */
    const val FICTION = "s.fiction"

    /**
     * Three classics for each language, in the order the card shows them, by Gutenberg's own
     * codes. Titles are the books' own, shortened where the catalogue runs on; authors are
     * written as Gutenberg displays them. Gutenberg holds no Russian books worth a first read,
     * only translations into other languages, so a phone in Russian is offered the English three.
     */
    val CURATED: Map<String, List<FreeBook>> = mapOf(
        "en" to listOf(
            FreeBook(1342, "Pride and Prejudice", "Jane Austen"),
            FreeBook(11, "Alice's Adventures in Wonderland", "Lewis Carroll"),
            FreeBook(1661, "The Adventures of Sherlock Holmes", "Arthur Conan Doyle"),
        ),
        "de" to listOf(
            FreeBook(35312, "Aus dem Leben eines Taugenichts", "Joseph von Eichendorff"),
            FreeBook(22367, "Die Verwandlung", "Franz Kafka"),
            FreeBook(2499, "Siddhartha", "Hermann Hesse"),
        ),
        "fr" to listOf(
            FreeBook(13951, "Les trois mousquetaires", "Alexandre Dumas"),
            FreeBook(5097, "Vingt mille lieues sous les mers", "Jules Verne"),
            FreeBook(17489, "Les misérables, tome I : Fantine", "Victor Hugo"),
        ),
        "es" to listOf(
            FreeBook(2000, "Don Quijote", "Miguel de Cervantes Saavedra"),
            FreeBook(9980, "Platero y yo", "Juan Ramón Jiménez"),
            FreeBook(320, "Lazarillo de Tormes", "Anonymous"),
        ),
        "it" to listOf(
            FreeBook(52484, "Le avventure di Pinocchio", "Carlo Collodi"),
            FreeBook(45334, "I promessi sposi", "Alessandro Manzoni"),
            FreeBook(1000, "La Divina Commedia", "Dante Alighieri"),
        ),
        "pt" to listOf(
            FreeBook(55752, "Dom Casmurro", "Machado de Assis"),
            FreeBook(3333, "Os Lusíadas", "Luís de Camões"),
            FreeBook(67740, "Iracema", "José de Alencar"),
        ),
        "nl" to listOf(
            FreeBook(11024, "Max Havelaar", "Multatuli"),
            FreeBook(15975, "Camera Obscura", "Hildebrand"),
            FreeBook(23796, "De geschiedenis van Woutertje Pieterse, deel 1", "Multatuli"),
        ),
        "sv" to listOf(
            FreeBook(30078, "Hemsöborna", "August Strindberg"),
            FreeBook(51440, "Valda berättelser", "Selma Lagerlöf"),
            FreeBook(57052, "Röda rummet", "August Strindberg"),
        ),
        "da" to listOf(
            FreeBook(76563, "Pelle Erobreren 1: Barndom", "Martin Andersen Nexø"),
            FreeBook(36942, "Kongens Fald", "Johannes V. Jensen"),
            FreeBook(51384, "Lykke-Per, første del", "Henrik Pontoppidan"),
        ),
        "no" to listOf(
            FreeBook(43724, "Markens grøde, første del", "Knut Hamsun"),
            FreeBook(30027, "Sult", "Knut Hamsun"),
            FreeBook(13041, "Vildanden", "Henrik Ibsen"),
        ),
        "fi" to listOf(
            FreeBook(11940, "Seitsemän veljestä", "Aleksis Kivi"),
            FreeBook(7000, "Kalevala", "Elias Lönnrot"),
            FreeBook(46569, "Liisan seikkailut ihmemaassa", "Lewis Carroll"),
        ),
        "pl" to listOf(
            FreeBook(31536, "Pan Tadeusz", "Adam Mickiewicz"),
            FreeBook(8119, "Sklepy cynamonowe", "Bruno Schulz"),
            FreeBook(34079, "Tajemnica Baskerville'ów", "Arthur Conan Doyle"),
        ),
        "cs" to listOf(
            FreeBook(13083, "R.U.R.", "Karel Čapek"),
            FreeBook(34225, "Zápisky z mrtvého domu", "Fyodor Dostoyevsky"),
            FreeBook(37525, "Dvojník. Nétička Nezvánova a Malinký hrdina", "Fyodor Dostoyevsky"),
        ),
        "hu" to listOf(
            FreeBook(69689, "A Pál utcai fiúk", "Ferenc Molnár"),
            FreeBook(76235, "Az egri csillagok, I. kötet", "Géza Gárdonyi"),
            FreeBook(67140, "Légy jó mindhalálig", "Zsigmond Móricz"),
        ),
        "ru" to emptyList(),
        "zh" to listOf(
            FreeBook(23962, "西遊記", "Cheng'en Wu"),
            FreeBook(24264, "紅樓夢", "Xueqin Cao"),
            FreeBook(23950, "三國志演義", "Guanzhong Luo"),
        ),
        "ja" to listOf(
            FreeBook(1982, "羅生門", "Ryūnosuke Akutagawa"),
            FreeBook(33307, "友情", "Saneatsu Mushanokoji"),
            FreeBook(31757, "お目出たき人", "Saneatsu Mushanokoji"),
        ),
        "el" to listOf(
            FreeBook(36248, "Ιλιάδα", "Homer"),
            FreeBook(29062, "Λουκής Λάρας", "Demetrios Vikelas"),
            FreeBook(36845, "Χριστουγεννιάτικα διηγήματα", "Alexandros Papadiamantes"),
        ),
    )

    private val ENGLISH = CURATED.getValue("en")

    /**
     * The classics for a phone in [language], one of Gutenberg's codes, or null when Gutenberg
     * does not have the phone's language at all, which is offered the English three.
     *
     * A language chosen for by hand gets its own, topped up from the English where it has fewer
     * than three. Any other Gutenberg language gets the first page of its fiction from
     * [fiction], the most downloaded first; a language with too little fiction for a full card
     * is offered the English three in its place rather than a card half its own.
     */
    suspend fun picks(language: String?, fiction: suspend (language: String) -> List<FreeBook>): List<FreeBook> {
        if (language == null) return ENGLISH
        CURATED[language]?.let { chosen -> return (chosen + ENGLISH).distinctBy { it.id }.take(COUNT) }
        val found = fiction(language).distinctBy { it.id }
        return if (found.size >= COUNT) found.take(COUNT) else ENGLISH
    }
}
