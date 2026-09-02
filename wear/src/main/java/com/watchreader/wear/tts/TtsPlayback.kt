package com.watchreader.wear.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TtsState { IDLE, LOADING, PLAYING, PAUSED }

/** What the reader screen needs to know about the read-aloud service, published as flows. */
object TtsPlayback {
    private val _state = MutableStateFlow(TtsState.IDLE)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _bookId = MutableStateFlow<String?>(null)
    val bookId: StateFlow<String?> = _bookId.asStateFlow()

    /** Character range, in the book's text, of the sentence being spoken; null between sentences. */
    private val _sentence = MutableStateFlow<IntRange?>(null)
    val sentence: StateFlow<IntRange?> = _sentence.asStateFlow()

    internal fun set(state: TtsState, bookId: String?, sentence: IntRange?) {
        _bookId.value = bookId
        _sentence.value = sentence
        _state.value = state
    }

    internal fun sentence(range: IntRange?) {
        _sentence.value = range
    }

    internal fun state(state: TtsState) {
        _state.value = state
    }
}
