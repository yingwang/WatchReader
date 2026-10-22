package com.watchreader.mobile.highlight

/** An app on the phone that takes selected text (Intent.ACTION_PROCESS_TEXT), as the system lists it. */
data class TextHandler(val packageName: String, val className: String, val label: String)

/** What looking up or translating a selection does, given the apps on the phone. */
sealed interface Handoff {
    /** Straight to this app. */
    data class Open(val handler: TextHandler) : Handoff

    /** The system's chooser of text apps, without these. */
    data class Choose(val leaveOut: List<TextHandler>) : Handoff

    /** No app takes selected text; a web search through the phone's search app is the last resort. */
    data object WebSearch : Handoff
}

/**
 * Picks where a selection goes. The reader never looks anything up itself: the words go to apps
 * the phone already has, which know the reader's languages better than this app could.
 */
object HandoffChoice {
    const val GOOGLE_TRANSLATE = "com.google.android.apps.translate"

    /** Words in an app's package or name that mark it as a dictionary. */
    private val DICTIONARY_HINTS = listOf(
        "dict", "lexic", "glossar", "wordreference", "merriam", "pleco", "eudic", "wörterbuch",
        "词典", "辞典", "字典", "詞典", "사전",
    )

    /** Words in an app's package or name that mark it as a translator. */
    private val TRANSLATOR_HINTS = listOf("translat", "deepl", "papago", "übersetz", "tradu", "翻译", "翻譯", "翻訳", "번역")

    /** A dictionary when the phone has one, else any app that takes text, else a web search. */
    fun lookUp(handlers: List<TextHandler>): Handoff =
        among(handlers, handlers.filter { matches(it, DICTIONARY_HINTS) })

    /** Google Translate when the phone has it, else another translator, else any app that takes text. */
    fun translate(handlers: List<TextHandler>): Handoff {
        handlers.firstOrNull { it.packageName == GOOGLE_TRANSLATE }?.let { return Handoff.Open(it) }
        return among(handlers, handlers.filter { matches(it, TRANSLATOR_HINTS) })
    }

    /**
     * One app of the kind wanted is opened; several are offered without the rest. With none of the
     * kind, every text app is offered to choose from, even a lone one: a phone without a translator
     * may have only a read-aloud tool that takes text, and opening that unasked would be a guess.
     */
    private fun among(all: List<TextHandler>, preferred: List<TextHandler>): Handoff = when {
        preferred.size == 1 -> Handoff.Open(preferred.single())
        preferred.isNotEmpty() -> Handoff.Choose(all - preferred.toSet())
        all.isEmpty() -> Handoff.WebSearch
        else -> Handoff.Choose(emptyList())
    }

    private fun matches(handler: TextHandler, hints: List<String>): Boolean {
        val name = (handler.packageName + " " + handler.label).lowercase()
        return hints.any { it in name }
    }
}
