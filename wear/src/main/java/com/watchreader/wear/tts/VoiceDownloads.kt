package com.watchreader.wear.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gets the voices a book will be read in onto the watch before they are needed.
 *
 * Google's engine keeps most of its voices off the watch and fetches one the first time something
 * is read in its language; until then that first sentence fails. Nothing here downloads anything
 * itself. It asks the engine, silently, for one short piece of speech in each language the book
 * needs whose voice is not yet on the watch, and that request is what starts the engine fetching.
 * The speech is written to a file and thrown away, so nothing is heard.
 */
object VoiceDownloads {
    private const val TAG = "WatchReader"
    /** The engine is let go after this long whatever it has answered; the fetch goes on in the engine. */
    private const val GIVE_UP_MS = 120_000L

    /** Starts the fetch of any voice [locales] need that the watch does not have. Call on the main thread. */
    fun request(context: Context, locales: Collection<Locale>) {
        if (locales.isEmpty()) return
        val app = context.applicationContext
        val released = AtomicBoolean(false)
        var engine: TextToSpeech? = null
        fun letGo() {
            if (released.compareAndSet(false, true)) TtsLanguages.release(engine)
        }
        engine = TextToSpeech(app) { status ->
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                letGo()
                return@TextToSpeech
            }
            val voices = TtsLanguages.voices(tts)
            val wanted = locales.filter { TtsLanguages.stateOf(tts, it, voices) == TtsLanguages.State.DOWNLOAD }
            if (wanted.isEmpty()) {
                letGo()
                return@TextToSpeech
            }
            val dir = File(app.cacheDir, "voice-fetch").apply { mkdirs() }
            val left = AtomicInteger(wanted.size)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onDone(utteranceId: String) = over(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) = over(utteranceId)
                override fun onError(utteranceId: String, errorCode: Int) = over(utteranceId)

                private fun over(utteranceId: String) {
                    File(dir, "$utteranceId.wav").delete()
                    if (left.decrementAndGet() == 0) letGo()
                }
            })
            for (locale in wanted) {
                Log.i(TAG, "Asking the engine for a ${locale.toLanguageTag()} voice")
                val id = "voice-" + locale.toLanguageTag()
                tts.setLanguage(locale)
                if (tts.synthesizeToFile("1", null, File(dir, "$id.wav"), id) != TextToSpeech.SUCCESS) {
                    if (left.decrementAndGet() == 0) letGo()
                }
            }
            Handler(Looper.getMainLooper()).postDelayed({ letGo() }, GIVE_UP_MS)
        }
    }
}
