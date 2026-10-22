package com.watchreader.mobile.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Plays fair with the phone's other sound while the voice reads: it asks for audio focus before
 * speaking, pauses when a call or another player takes it, and goes on again when a short
 * interruption (a navigation prompt, a voice message) gives it back. It also pauses the moment
 * headphones are unplugged or a Bluetooth headset drops, before the book carries on out loud.
 *
 * Spoken words are not ducked under another sound, as music may be: half-heard sentences are lost
 * sentences, so a request to duck pauses as well. All callbacks come on the main thread.
 */
internal class AudioFocusKeeper(
    private val context: Context,
    /** Pause reading; [passing] when focus is expected back, so [release] keeps the request in. */
    private val onPause: (passing: Boolean) -> Unit,
    private val onResume: () -> Unit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var held = false
    /** Paused by a passing interruption, and so to go on when focus comes back. */
    private var resumeOnGain = false
    private var noisyRegistered = false

    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(ATTRIBUTES)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, handler)
        .build()

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                resumeOnGain = false
                onPause(false)
            }
        }
    }

    /** Asks for focus before speaking; false when another app keeps it, such as during a call. */
    fun acquire(): Boolean {
        resumeOnGain = false
        if (!held) {
            val result = audio?.requestAudioFocus(request) ?: AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (held && !noisyRegistered) {
            // The system sends this broadcast itself; no other app can, so it is not exported.
            ContextCompat.registerReceiver(
                context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }
        return held
    }

    /**
     * Lets focus go when the reader pauses or stops. [keepForResume] is for a pause that focus
     * itself caused: the request stays in, so the system can hand focus back, and so does the ear
     * for headphones coming out, so that a call ended after they were unplugged does not carry
     * the book on out loud.
     */
    fun release(keepForResume: Boolean = false) {
        if (keepForResume) return
        if (noisyRegistered) {
            runCatching { context.unregisterReceiver(noisy) }
            noisyRegistered = false
        }
        resumeOnGain = false
        if (held) {
            audio?.abandonAudioFocusRequest(request)
            held = false
        }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnGain) {
                    resumeOnGain = false
                    onResume()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                onPause(true)
                // Set after the pause, which releases everything but this request.
                resumeOnGain = true
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another player has taken over for good; the reader presses play to come back.
                resumeOnGain = false
                onPause(false)
                release()
            }
        }
    }

    companion object {
        /** What the voice is to the system: spoken words, played as media. */
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
