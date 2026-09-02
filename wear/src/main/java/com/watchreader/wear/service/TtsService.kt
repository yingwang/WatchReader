package com.watchreader.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.watchreader.wear.R
import com.watchreader.wear.data.model.WearBook
import com.watchreader.wear.data.repository.WearBookRepository
import com.watchreader.wear.settings.ReaderPrefs
import com.watchreader.wear.tts.LanguageDetector
import com.watchreader.wear.tts.SentenceParser
import com.watchreader.wear.tts.TtsPlayback
import com.watchreader.wear.tts.TtsState
import com.watchreader.wear.ui.WearActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "WatchReader"

/**
 * Reads a book aloud as a foreground media service, so the voice carries on with the screen off
 * and after the reader screen is left. The reader screen follows [TtsPlayback] to turn pages.
 */
class TtsService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tts: TextToSpeech? = null
    private var engineReady = false
    private var pendingPlay: Pair<String, Int>? = null

    private var book: WearBook? = null
    private var text: String = ""
    private var sentences: List<Pair<String, IntRange>> = emptyList()
    private var nextToQueue = 0
    private var current = -1
    private var currentLocale: Locale? = null
    private var fixedVoice = false
    private var sentencesSinceSave = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The system demands startForeground() promptly after startForegroundService(), even if
        // the engine then turns out to be missing and we stop straight away.
        goForeground(getString(R.string.tts_loading), playing = true)
        tts = TextToSpeech(this) { status ->
            engineReady = status == TextToSpeech.SUCCESS
            if (!engineReady) {
                Log.e(TAG, "TTS engine failed to initialise")
                TtsPlayback.set(TtsState.IDLE, null, null)
                android.widget.Toast.makeText(this, R.string.tts_no_engine, android.widget.Toast.LENGTH_LONG).show()
                finish()
                return@TextToSpeech
            }
            tts?.setOnUtteranceProgressListener(listener)
            pendingPlay?.let { (id, offset) -> pendingPlay = null; play(id, offset) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val id = intent.getStringExtra(EXTRA_BOOK_ID)
                val offset = intent.getIntExtra(EXTRA_OFFSET, 0)
                if (id == null) {
                    finish()
                    return START_NOT_STICKY
                }
                goForeground(titleOrLoading(), playing = true)
                if (engineReady) play(id, offset) else pendingPlay = id to offset
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> finish()
        }
        return START_NOT_STICKY
    }

    private fun play(bookId: String, offset: Int) {
        TtsPlayback.set(TtsState.LOADING, bookId, null)
        scope.launch {
            val loaded = WearBookRepository.getById(bookId)
            if (loaded == null) {
                finish()
                return@launch
            }
            book = loaded
            text = WearBookRepository.loadText(loaded)
            sentences = SentenceParser.splitWithRanges(text, offset.coerceIn(0, text.length))
            if (sentences.isEmpty()) {
                finish()
                return@launch
            }
            applyPrefs()
            tts?.stop()
            nextToQueue = 0
            current = -1
            goForeground(loaded.title, playing = true)
            TtsPlayback.set(TtsState.PLAYING, bookId, null)
            queueMore()
        }
    }

    private fun applyPrefs() {
        val prefs = ReaderPrefs(this)
        tts?.setSpeechRate(prefs.speechRate)
        fixedVoice = false
        currentLocale = null
        val voiceName = prefs.ttsVoice
        if (voiceName.isNotEmpty()) {
            tts?.voices?.firstOrNull { it.name == voiceName }?.let { v ->
                tts?.voice = v
                fixedVoice = true
            }
        }
    }

    /** Keeps the engine fed a batch at a time; a whole novel queued at once makes some engines stall. */
    private fun queueMore() {
        val engine = tts ?: return
        val end = minOf(sentences.size, nextToQueue + BATCH)
        for (i in nextToQueue until end) {
            val (sentence, _) = sentences[i]
            if (!fixedVoice) {
                val locale = LanguageDetector.detect(sentence)
                if (locale != currentLocale) {
                    currentLocale = locale
                    engine.language = locale
                }
            }
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "s$i") }
            engine.speak(sentence, TextToSpeech.QUEUE_ADD, params, "s$i")
        }
        nextToQueue = end
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            val index = utteranceId.removePrefix("s").toIntOrNull() ?: return
            current = index
            val range = sentences.getOrNull(index)?.second
            scope.launch {
                TtsPlayback.sentence(range)
                if (++sentencesSinceSave >= SAVE_EVERY) {
                    sentencesSinceSave = 0
                    range?.let { saveProgress(it.first, toPhone = false) }
                }
                // top up when the queue is about to run dry
                if (index >= nextToQueue - REFILL_AT && nextToQueue < sentences.size) queueMore()
            }
        }

        override fun onDone(utteranceId: String) {
            val index = utteranceId.removePrefix("s").toIntOrNull() ?: return
            if (index >= sentences.size - 1) {
                scope.launch {
                    saveProgress(text.length, toPhone = true)
                    finish()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            Log.w(TAG, "Utterance $utteranceId failed; skipping")
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            Log.w(TAG, "Utterance $utteranceId failed with $errorCode; skipping")
        }
    }

    private fun pause() {
        if (TtsPlayback.state.value != TtsState.PLAYING) return
        tts?.stop()
        nextToQueue = maxOf(current, 0)
        TtsPlayback.state(TtsState.PAUSED)
        goForeground(titleOrLoading(), playing = false)
        scope.launch { sentences.getOrNull(current)?.second?.let { saveProgress(it.first, toPhone = true) } }
    }

    private fun resume() {
        if (TtsPlayback.state.value != TtsState.PAUSED) return
        applyPrefs()
        TtsPlayback.state(TtsState.PLAYING)
        goForeground(titleOrLoading(), playing = true)
        queueMore()
    }

    private fun finish() {
        tts?.stop()
        TtsPlayback.set(TtsState.IDLE, null, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun saveProgress(offset: Int, toPhone: Boolean) {
        val b = book ?: return
        WearBookRepository.updateProgress(b.id, offset)
        if (toPhone) WearBookRepository.sendProgressToPhone(b, offset)
    }

    override fun onDestroy() {
        val b = book
        val offset = sentences.getOrNull(current)?.second?.first
        scope.launch {
            if (b != null && offset != null) {
                WearBookRepository.updateProgress(b.id, offset)
                WearBookRepository.sendProgressToPhone(b, offset)
            }
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
        TtsPlayback.set(TtsState.IDLE, null, null)
        scope.cancel()
        super.onDestroy()
    }

    private fun titleOrLoading(): String = book?.title ?: getString(R.string.tts_loading)

    private fun goForeground(title: String, playing: Boolean) {
        startForeground(NOTIFICATION_ID, buildNotification(title, playing), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun buildNotification(title: String, playing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, WearActivity::class.java).apply {
                putExtra(WearActivity.EXTRA_BOOK_ID, book?.id)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = serviceIntent(if (playing) ACTION_PAUSE else ACTION_RESUME, 1)
        val stop = serviceIntent(ACTION_STOP, 2)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(getString(if (playing) R.string.tts_reading_aloud else R.string.tts_paused))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(if (playing) R.string.tts_action_pause else R.string.tts_action_resume), toggle)
            .addAction(0, getString(R.string.tts_action_stop), stop)
            .build()
    }

    private fun serviceIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, TtsService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_PLAY = "com.watchreader.tts.PLAY"
        const val ACTION_PAUSE = "com.watchreader.tts.PAUSE"
        const val ACTION_RESUME = "com.watchreader.tts.RESUME"
        const val ACTION_STOP = "com.watchreader.tts.STOP"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_OFFSET = "offset"
        private const val CHANNEL_ID = "read_aloud"
        private const val NOTIFICATION_ID = 41
        private const val BATCH = 24
        private const val REFILL_AT = 8
        private const val SAVE_EVERY = 10

        fun play(context: Context, bookId: String, offset: Int) {
            context.startForegroundService(
                Intent(context, TtsService::class.java)
                    .setAction(ACTION_PLAY)
                    .putExtra(EXTRA_BOOK_ID, bookId)
                    .putExtra(EXTRA_OFFSET, offset),
            )
        }

        fun pause(context: Context) = context.startService(Intent(context, TtsService::class.java).setAction(ACTION_PAUSE))
        fun resume(context: Context) = context.startService(Intent(context, TtsService::class.java).setAction(ACTION_RESUME))
        fun stop(context: Context) = context.startService(Intent(context, TtsService::class.java).setAction(ACTION_STOP))

        fun stopIfPlaying(context: Context, bookId: String) {
            if (TtsPlayback.bookId.value == bookId) stop(context)
        }

        fun ensureNotificationChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.tts_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                        setSound(null, null)
                        enableVibration(false)
                    },
                )
            }
        }
    }
}
