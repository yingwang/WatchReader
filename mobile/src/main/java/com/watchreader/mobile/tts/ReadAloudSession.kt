package com.watchreader.mobile.tts

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.watchreader.mobile.R

/**
 * The media session the voice is controlled through from outside the app: the notification, the
 * lock screen, Quick Settings' media player, a headset's buttons and a car. It is the platform's
 * own session, which needs no library for what it does here: a book read aloud has no audio file
 * to hand a media player, only sentences going to the speech engine.
 *
 * "Next" and "previous" move by a sentence, the step the reader can see on the page; a headset's
 * fast-forward and rewind do the same. Everything arrives on the main thread.
 */
internal class ReadAloudSession(context: Context, sessionActivity: PendingIntent, private val controls: Controls) {
    interface Controls {
        fun play()
        fun pause()
        fun skip(by: Int)
        fun stop()
    }

    private val stopLabel = context.getString(R.string.listen_action_stop)

    val session: MediaSession = MediaSession(context, "WatchReader read aloud").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = controls.play()
            override fun onPause() = controls.pause()
            override fun onStop() = controls.stop()
            override fun onSkipToNext() = controls.skip(1)
            override fun onSkipToPrevious() = controls.skip(-1)
            override fun onFastForward() = controls.skip(1)
            override fun onRewind() = controls.skip(-1)
            override fun onCustomAction(action: String, extras: Bundle?) {
                if (action == CUSTOM_STOP) controls.stop()
            }
        }, Handler(Looper.getMainLooper()))
        setSessionActivity(sessionActivity)
        // Volume keys set the media volume the voice is played at.
        setPlaybackToLocal(AudioFocusKeeper.ATTRIBUTES)
    }

    val token: MediaSession.Token get() = session.sessionToken

    /**
     * The state the system's controls show. The position is unknown: a book has no length in
     * time, and a progress bar measured in guesses would only mislead. Since Android 13 the media
     * controls draw their buttons from these actions, and show Stop only as a custom action.
     */
    fun playback(state: ReadAloudState, rate: Float) {
        val platform = when (state) {
            ReadAloudState.IDLE -> PlaybackState.STATE_STOPPED
            ReadAloudState.LOADING -> PlaybackState.STATE_BUFFERING
            ReadAloudState.PLAYING -> PlaybackState.STATE_PLAYING
            ReadAloudState.PAUSED -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(platform, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (state == ReadAloudState.PLAYING) rate else 0f)
                .addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_STOP, stopLabel, R.drawable.ic_listen_stop).build())
                .build(),
        )
        session.isActive = state != ReadAloudState.IDLE
    }

    /** The book being read, and its chapter where the controls have room for a second line. */
    fun describe(title: String, author: String?, chapter: String?, cover: Bitmap?) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, author ?: chapter.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, chapter ?: author.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, title)
                .apply { if (cover != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover) }
                .build(),
        )
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    private companion object {
        const val CUSTOM_STOP = "com.watchreader.listen.STOP"
        const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
    }
}
