package com.watchreader.wear.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class TtsState { IDLE, LOADING, PLAYING, PAUSED }

class TtsManager(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var isReady = false

    private val prefs by lazy {
        context.getSharedPreferences("watchreader_settings", Context.MODE_PRIVATE)
    }

    private val _state = MutableStateFlow(TtsState.LOADING)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _currentSentenceIndex = MutableStateFlow(-1)
    val currentSentenceIndex: StateFlow<Int> = _currentSentenceIndex.asStateFlow()

    private val _onPageDone = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onPageDone = _onPageDone.asSharedFlow()

    private var sentences: List<Pair<String, IntRange>> = emptyList()
    private var currentIndex = 0
    private var currentLocale: Locale = Locale.US
    private var hasCustomVoice = false

    var speechRate: Float = 1.0f
        set(value) {
            field = value
            tts?.setSpeechRate(value)
        }

    init {
        tts = TextToSpeech(context) { status ->
            isReady = status == TextToSpeech.SUCCESS
            _state.value = TtsState.IDLE

            // Load saved speed + voice preferences
            speechRate = prefs.getFloat("speech_rate", 1.0f)

            val savedVoice = prefs.getString("tts_voice", null)
            if (savedVoice != null) setVoiceName(savedVoice)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    val index = utteranceId.removePrefix("sent_").toIntOrNull() ?: return
                    _currentSentenceIndex.value = index
                }

                override fun onDone(utteranceId: String) {
                    val index = utteranceId.removePrefix("sent_").toIntOrNull() ?: return
                    if (index >= sentences.size - 1) {
                        // All sentences on this page are done
                        _currentSentenceIndex.value = -1
                        _state.value = TtsState.IDLE
                        _onPageDone.tryEmit(Unit)
                    }
                }

                @Deprecated("Deprecated")
                override fun onError(utteranceId: String) {
                    _state.value = TtsState.IDLE
                    _currentSentenceIndex.value = -1
                }
            })
        }
    }

    fun speakPage(sentencesWithRanges: List<Pair<String, IntRange>>) {
        if (!isReady) return
        tts?.stop()

        // Pick up any speed changes the user made in Settings since the last page
        speechRate = prefs.getFloat("speech_rate", 1.0f)

        sentences = sentencesWithRanges
        currentIndex = 0
        _state.value = TtsState.PLAYING

        for ((i, pair) in sentences.withIndex()) {
            val (text, _) = pair
            // Only auto-switch language if no custom voice is set
            if (!hasCustomVoice) {
                val locale = LanguageDetector.detect(text)
                if (locale != currentLocale) {
                    currentLocale = locale
                    tts?.language = locale
                }
            }

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "sent_$i")
            }
            tts?.speak(text, TextToSpeech.QUEUE_ADD, params, "sent_$i")
        }
    }

    fun togglePlayPause() {
        when (_state.value) {
            TtsState.PLAYING -> pause()
            TtsState.PAUSED -> resume()
            else -> {}
        }
    }

    private fun pause() {
        tts?.stop()
        _state.value = TtsState.PAUSED
    }

    private fun resume() {
        if (sentences.isEmpty()) return
        val resumeFrom = (_currentSentenceIndex.value).coerceAtLeast(0)
        _state.value = TtsState.PLAYING

        for (i in resumeFrom until sentences.size) {
            val (text, _) = sentences[i]
            val locale = LanguageDetector.detect(text)
            if (locale != currentLocale) {
                currentLocale = locale
                tts?.language = locale
            }
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "sent_$i")
            }
            tts?.speak(text, TextToSpeech.QUEUE_ADD, params, "sent_$i")
        }
    }

    fun stop() {
        tts?.stop()
        _state.value = TtsState.IDLE
        _currentSentenceIndex.value = -1
        sentences = emptyList()
    }

    fun getVoiceNames(): List<String> {
        return tts?.voices
            ?.filter { !it.isNetworkConnectionRequired }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    fun setVoiceName(name: String) {
        val voice = tts?.voices?.find { it.name == name }
        if (voice != null) {
            tts?.voice = voice
            hasCustomVoice = true
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
