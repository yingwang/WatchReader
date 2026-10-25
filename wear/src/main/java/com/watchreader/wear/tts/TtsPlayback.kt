package com.watchreader.wear.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

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

    /**
     * The language whose voice the engine is fetching while reading waits for it; null otherwise.
     * Reading stays PLAYING meanwhile, so the controls behave as they would mid-sentence.
     */
    private val _fetching = MutableStateFlow<Locale?>(null)
    val fetching: StateFlow<Locale?> = _fetching.asStateFlow()

    /** Service-owned elapsed-realtime deadline; the screen only renders its remaining time. */
    private val _sleepTimer = MutableStateFlow<SleepTimer.State?>(null)
    val sleepTimer: StateFlow<SleepTimer.State?> = _sleepTimer.asStateFlow()

    /**
     * The book the reader jumped away from while it was read aloud, until reading starts again
     * (see TtsService.stopForJump). Nothing the service still has queued for it may move the page
     * or save a place over the jump. Read and written on the main thread only.
     */
    private var letGo: String? = null

    /** The reader jumped elsewhere in [bookId] and is stopping the voice. */
    internal fun letGo(bookId: String) {
        letGo = bookId
    }

    /** Whether [bookId] was let go of by a jump and has not been played since. */
    internal fun isLetGo(bookId: String?): Boolean = bookId != null && bookId == letGo

    /** Reading aloud starts again: the voice has the page once more. */
    internal fun takeBack() {
        letGo = null
    }

    internal fun sleepTimer(timer: SleepTimer.State?) {
        _sleepTimer.value = timer
    }

    internal fun set(state: TtsState, bookId: String?, sentence: IntRange?) {
        if (state == TtsState.IDLE || bookId != _bookId.value) _sleepTimer.value = null
        _bookId.value = bookId
        _sentence.value = sentence
        _fetching.value = null
        _state.value = state
    }

    internal fun fetching(locale: Locale?) {
        _fetching.value = locale
    }

    internal fun sentence(range: IntRange?) {
        _sentence.value = range
    }

    internal fun state(state: TtsState) {
        if (state != TtsState.PLAYING) _fetching.value = null
        _state.value = state
    }
}
