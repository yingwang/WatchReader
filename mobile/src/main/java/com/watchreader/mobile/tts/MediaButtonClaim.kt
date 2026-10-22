package com.watchreader.mobile.tts

import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread

/**
 * Makes this app the one the headset's buttons talk to while it reads aloud.
 *
 * Since Android 8 the play and pause buttons of a headset, wired or Bluetooth, go to the media
 * session of the app that most recently played sound. The voice is not that app: the speech
 * engine plays every sentence from its own process, so to the system the sound is the engine's,
 * the engine has no media session, and the buttons go on to whichever music app played last. A
 * moment of silence played from this app whenever reading starts or resumes puts it at the front
 * of that queue, and the engine's sentences after it change nothing, having no session to win
 * the buttons with.
 */
internal object MediaButtonClaim {
    private const val TAG = "WatchReader"
    private const val SAMPLE_RATE = 8_000
    /** A fifth of a second, long enough for the system to see a player start. */
    private const val SAMPLES = SAMPLE_RATE / 5

    fun claim() {
        // Building and playing a track can block for a moment on some phones; it is not for the main thread.
        thread(name = "media-button-claim") {
            runCatching {
                val bytes = SAMPLES * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioFocusKeeper.ATTRIBUTES)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(bytes)
                    .build()
                try {
                    track.write(ShortArray(SAMPLES), 0, SAMPLES)
                    track.play()
                    Thread.sleep(SAMPLES * 1000L / SAMPLE_RATE + 50)
                    track.stop()
                } finally {
                    track.release()
                }
            }.onFailure { Log.w(TAG, "Could not claim the media buttons", it) }
        }
    }
}
