package com.watchreader.mobile.tts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class ReadAloudState { IDLE, LOADING, PLAYING, PAUSED }

/** Something the reader is told about while listening, worded by the screen that shows it. */
sealed class ReadAloudNotice {
    /** The phone has no speech engine at all. */
    object NoEngine : ReadAloudNotice()

    /** The engine has no voice for [locale]: sentences in it are passed over, or reading pauses. */
    data class NoVoice(val locale: Locale) : ReadAloudNotice()

    /** The voice for [locale] is still to be fetched and the phone is offline. */
    data class VoiceOffline(val locale: Locale) : ReadAloudNotice()

    /** The engine tried to fetch the voice for [locale] and gave up. */
    data class VoiceFailed(val locale: Locale) : ReadAloudNotice()

    /** Another app holds the audio (a call, most often), so reading could not start. */
    object NoAudioFocus : ReadAloudNotice()
}

/**
 * What the reader screen needs to know about the read-aloud service, published as flows. Only the
 * service writes them, on its main thread.
 */
object ReadAloudPlayback {
    private val _state = MutableStateFlow(ReadAloudState.IDLE)
    val state: StateFlow<ReadAloudState> = _state.asStateFlow()

    private val _bookId = MutableStateFlow<String?>(null)
    val bookId: StateFlow<String?> = _bookId.asStateFlow()

    /** Character range, in the book's text, of the sentence being spoken; null before the first. */
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

    /** Whether reading is to pause at the end of the chapter being read. */
    private val _sleepAtChapterEnd = MutableStateFlow(false)
    val sleepAtChapterEnd: StateFlow<Boolean> = _sleepAtChapterEnd.asStateFlow()

    /** The title of the chapter being read, for the player shown outside the reader; null when unknown. */
    private val _chapter = MutableStateFlow<String?>(null)
    val chapter: StateFlow<String?> = _chapter.asStateFlow()

    /** The languages the book being read is spoken in, once the service has looked it over. */
    private val _languages = MutableStateFlow<Set<Locale>>(emptySet())
    val languages: StateFlow<Set<Locale>> = _languages.asStateFlow()

    /**
     * Told once, to whichever reader screen is open at the time. Nothing is kept for a screen that
     * opens later: a message about a voice from an hour ago would only confuse.
     */
    private val _notices = MutableSharedFlow<ReadAloudNotice>(extraBufferCapacity = 4)
    val notices: SharedFlow<ReadAloudNotice> = _notices.asSharedFlow()

    internal fun set(state: ReadAloudState, bookId: String?, sentence: IntRange?) {
        if (state == ReadAloudState.IDLE || bookId != _bookId.value) {
            _sleepTimer.value = null
            _sleepAtChapterEnd.value = false
            _languages.value = emptySet()
            _chapter.value = null
        }
        _bookId.value = bookId
        _sentence.value = sentence
        _fetching.value = null
        _state.value = state
    }

    internal fun state(state: ReadAloudState) {
        if (state != ReadAloudState.PLAYING) _fetching.value = null
        _state.value = state
    }

    internal fun sentence(range: IntRange?) {
        _sentence.value = range
    }

    internal fun fetching(locale: Locale?) {
        _fetching.value = locale
    }

    internal fun sleepTimer(timer: SleepTimer.State?) {
        _sleepTimer.value = timer
    }

    internal fun sleepAtChapterEnd(on: Boolean) {
        _sleepAtChapterEnd.value = on
    }

    internal fun chapter(title: String?) {
        _chapter.value = title
    }

    internal fun languages(languages: Set<Locale>) {
        _languages.value = languages
    }

    internal fun notice(notice: ReadAloudNotice) {
        _notices.tryEmit(notice)
    }
}
