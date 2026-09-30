package com.watchreader.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import kotlin.concurrent.thread
import androidx.core.app.NotificationCompat
import com.watchreader.wear.R
import com.watchreader.wear.data.model.WearBook
import com.watchreader.wear.data.repository.WearBookRepository
import com.watchreader.wear.settings.ReaderPrefs
import com.watchreader.wear.tts.BookLanguages
import com.watchreader.wear.tts.LanguageDetector
import com.watchreader.wear.tts.SentenceParser
import com.watchreader.wear.tts.TtsLanguages
import com.watchreader.wear.tts.TtsPlayback
import com.watchreader.wear.tts.TtsState
import com.watchreader.wear.tts.UtteranceSession
import com.watchreader.wear.tts.VoiceDownloads
import com.watchreader.wear.ui.WearActivity
import kotlinx.coroutines.CoroutineScope
import com.watchreader.shared.stats.ReadingSource
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** Where each sentence lies in [text]; the words are cut out only as they are queued. */
    private var sentences: List<IntRange> = emptyList()
    private var nextToQueue = 0
    @Volatile private var current = -1
    /** When the current sentence began; the reading saved on the way out is stamped with this. */
    @Volatile private var currentStartedAt = 0L
    /** Set in onDestroy so an engine that reports ready afterwards is ignored. */
    @Volatile private var destroyed = false
    private var currentLocale: Locale? = null
    /** What the book being read says about its languages, worked out once each time play starts. */
    private var bookLanguages = LanguageDetector.Book()
    /** Whether the engine can say each language met while reading this book; asked once per language. */
    private val sayable = HashMap<Locale, Boolean>()
    private var engineVoices: List<TtsLanguages.VoiceInfo>? = null
    /** Languages already reported as having no voice, so each is reported once per book. */
    private val warned = HashSet<Locale>()
    /**
     * Sentences queued as silence for want of a voice. They are reported when reading reaches
     * them, not when they are queued, which can be a page or two earlier.
     */
    private val skipped = HashSet<Int>()
    /**
     * The sentence at which reading stops because a long stretch from there is in a language the
     * watch has no voice for; -1 when there is none ahead.
     */
    private var stopAt = -1
    /** When reading began waiting for a voice the engine is fetching; 0 when it is not waiting. */
    private var waitingSince = 0L
    private var sentencesSinceSave = 0
    /** When the listening under way was last counted; 0 while nothing is playing. */
    private var listenedSince = 0L
    private var finishedBook = false

    /** Bumped by every play(); work left over from an earlier book checks it and stands down. */
    private val session = UtteranceSession()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The system demands startForeground() promptly after startForegroundService(), even if
        // the engine then turns out to be missing and we stop straight away.
        goForeground(getString(R.string.tts_loading), playing = true)
        tts = TextToSpeech(this) { status ->
            if (destroyed) return@TextToSpeech
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
                if ((pendingPlay?.first == id || TtsPlayback.bookId.value == id) &&
                    TtsPlayback.state.value == TtsState.LOADING) return START_NOT_STICKY
                TtsPlayback.set(TtsState.LOADING, id, null)
                goForeground(titleOrLoading(), playing = true)
                if (engineReady) play(id, offset) else pendingPlay = id to offset
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> finish()
        }
        return START_NOT_STICKY
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun play(bookId: String, offset: Int) {
        // What was being read until now is saved only every few sentences; keep its exact place,
        // stamped with the moment its sentence began so that a later jump still wins.
        val left = book
        val leftAt = sentences.getOrNull(current)?.first
        val leftStamp = currentStartedAt
        val leftListened = takeListening(stop = true)
        if (left != null && leftListened > 0) {
            GlobalScope.launch(Dispatchers.IO + NonCancellable) {
                WearBookRepository.recordTime(left.id, ReadingSource.LISTEN, leftListened, 0)
            }
        }
        if (left != null && leftAt != null && leftStamp != 0L && !finishedBook) {
            GlobalScope.launch(Dispatchers.IO + NonCancellable) {
                WearBookRepository.updateProgress(left.id, leftAt, leftStamp)
                WearBookRepository.sendProgressToPhone(left, leftAt, leftStamp)
            }
        }
        val serial = session.invalidate()
        tts?.stop()
        finishedBook = false
        TtsPlayback.set(TtsState.LOADING, bookId, null)
        scope.launch {
            val loaded = WearBookRepository.getById(bookId)
            if (loaded == null) {
                if (serial == session.generation) finish()
                return@launch
            }
            // A book whose file has gone (a transfer cut short, storage cleared) has nothing to read.
            val loadedText = runCatching { WearBookRepository.loadText(loaded) }.getOrElse {
                if (serial == session.generation) finish()
                return@launch
            }
            // another play() came in while this one was reading the file; that one owns the engine now
            if (serial != session.generation) return@launch
            // Every character from here to the end is looked at, and the whole book once more for
            // the languages it is written in; a novel's worth is too much for the main thread, so
            // it happens on a worker.
            val (split, languages) = withContext(Dispatchers.IO) {
                SentenceParser.ranges(loadedText, offset.coerceIn(0, loadedText.length)) to
                    LanguageDetector.survey(loadedText, Locale.getDefault())
            }
            if (serial != session.generation) return@launch
            book = loaded
            text = loadedText
            sentences = split
            bookLanguages = languages
            sayable.clear()
            engineVoices = null
            warned.clear()
            skipped.clear()
            stopAt = -1
            waitingSince = 0L
            // The voices the book needs that are not on the watch yet are asked for now, rather
            // than when the first sentence in each comes up and fails for want of it.
            scope.launch {
                val needed = withContext(Dispatchers.IO) { runCatching { BookLanguages.of(loaded, loadedText) }.getOrNull() }
                if (needed != null && !destroyed) VoiceDownloads.request(this@TtsService, needed)
            }
            if (sentences.isEmpty()) {
                finish()
                return@launch
            }
            applyPrefs()
            nextToQueue = 0
            current = -1
            sentencesSinceSave = 0
            goForeground(loaded.title, playing = true)
            TtsPlayback.set(TtsState.PLAYING, bookId, null)
            listenedSince = System.currentTimeMillis()
            queueMore()
        }
    }

    private fun applyPrefs() {
        val prefs = ReaderPrefs(this)
        tts?.setSpeechRate(prefs.speechRate)
        currentLocale = null
    }

    /** Keeps the engine fed a batch at a time; a whole novel queued at once makes some engines stall. */
    private fun queueMore() {
        val engine = tts ?: return
        // Reading is to stop at a stretch the watch cannot say; nothing goes in after it.
        if (stopAt >= 0) return
        val end = minOf(sentences.size, nextToQueue + BATCH)
        for (i in nextToQueue until end) {
            val sentence = sentenceAt(i)
            // Every sentence is spoken in the language it is written in; there is nothing to choose.
            val locale = LanguageDetector.detect(sentence, bookLanguages)
            val id = session.id(i)
            val result = if (canSay(engine, locale)) {
                if (locale != currentLocale) {
                    engine.setLanguage(locale)
                    currentLocale = locale
                }
                val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
                engine.speak(sentence, TextToSpeech.QUEUE_ADD, params, id)
            } else if (unsayableStretch(engine, i)) {
                // A long run of a language the watch has no voice for, or the rest of the book:
                // racing through it in silence would lose the reader's place and could mark the
                // book read. Reading pauses where it begins, once what is queued before it is said.
                stopAt = i
                nextToQueue = i + 1
                if (engine.playSilentUtterance(0, TextToSpeech.QUEUE_ADD, id) != TextToSpeech.SUCCESS) finish()
                return
            } else {
                // A sentence or two in a language the watch has no voice for is passed over, not
                // read in the voice of another, which says it wrong or not at all.
                skipped += i
                engine.playSilentUtterance(SKIP_MS, TextToSpeech.QUEUE_ADD, id)
            }
            if (result != TextToSpeech.SUCCESS) {
                // A sentence the engine refuses to take never gets an onError, so the queue
                // would drain and the service sit silent in PLAYING. The engine is gone; the
                // sentence being spoken is saved on the way out, and the reader sees idle.
                Log.w(TAG, "Engine refused sentence $i; stopping")
                nextToQueue = i
                finish()
                return
            }
        }
        nextToQueue = end
    }

    private fun sentenceAt(index: Int): String = sentences[index].let { text.substring(it.first, it.last + 1) }

    /**
     * Whether the engine has, or can fetch, a voice for [locale]. A language it can fetch counts:
     * the first sentence in it waits for the voice (see [voiceNotReady]).
     */
    private fun canSay(engine: TextToSpeech, locale: Locale): Boolean = sayable.getOrPut(locale) {
        val voices = engineVoices ?: TtsLanguages.voices(engine).also { engineVoices = it }
        TtsLanguages.stateOf(engine, locale, voices) != TtsLanguages.State.NONE
    }

    /** Whether the sentences from [from] on, as many as make a long stretch, are all ones the watch cannot say. */
    private fun unsayableStretch(engine: TextToSpeech, from: Int): Boolean {
        for (j in from until minOf(sentences.size, from + UNSAYABLE_STRETCH)) {
            if (canSay(engine, LanguageDetector.detect(sentenceAt(j), bookLanguages))) return false
        }
        return true
    }

    /** Tells the reader, once per language per book, that the watch has no voice for [locale]. */
    private fun noVoice(locale: Locale, pause: Boolean) {
        if (!pause && !warned.add(locale)) return
        Log.w(TAG, "No voice for ${locale.toLanguageTag()}; ${if (pause) "pausing" else "skipping"}")
        toast(getString(R.string.tts_no_voice, TtsLanguages.label(locale)))
    }

    /**
     * The engine could not say sentence [index] because the voice for its language is not on the
     * watch yet; the first sentence in a language new to the watch fails this way while the engine
     * fetches the voice. The rest of the batch would fail the same way, so it is dropped, and the
     * sentence is tried again every few seconds until the voice has come. Without a connection, or
     * after too long, reading pauses at that sentence instead and says why.
     */
    private fun voiceNotReady(index: Int) {
        val engine = tts ?: return
        if (index !in sentences.indices) return
        val locale = LanguageDetector.detect(sentenceAt(index), bookLanguages)
        session.invalidate()
        engine.stop()
        stopAt = -1
        current = index
        nextToQueue = index
        currentLocale = null
        val now = System.currentTimeMillis()
        if (waitingSince == 0L) waitingSince = now
        val connected = online()
        if (!connected || now - waitingSince > VOICE_WAIT_MS) {
            Log.w(TAG, "No ${locale.toLanguageTag()} voice after ${now - waitingSince} ms; connected=$connected")
            waitingSince = 0L
            pause()
            val language = TtsLanguages.label(locale)
            toast(getString(if (connected) R.string.tts_voice_failed else R.string.tts_voice_offline, language))
            return
        }
        TtsPlayback.fetching(locale)
        val serial = session.generation
        scope.launch {
            delay(VOICE_RETRY_MS)
            if (serial == session.generation && TtsPlayback.state.value == TtsState.PLAYING) queueMore()
        }
    }

    private fun online(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = runCatching { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }.getOrNull()
            ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            scope.launch {
                val index = session.index(utteranceId) ?: return@launch
                val serial = session.generation
                if (TtsPlayback.state.value != TtsState.PLAYING) return@launch
                val range = sentences.getOrNull(index) ?: return@launch
                if (index == stopAt) {
                    // The long stretch without a voice has come: pause at its start and say why.
                    current = index
                    pause()
                    noVoice(LanguageDetector.detect(sentenceAt(index), bookLanguages), pause = true)
                    return@launch
                }
                if (waitingSince != 0L) {
                    waitingSince = 0L
                    TtsPlayback.fetching(null)
                }
                if (skipped.remove(index)) noVoice(LanguageDetector.detect(sentenceAt(index), bookLanguages), pause = false)
                current = index
                currentStartedAt = System.currentTimeMillis()
                TtsPlayback.sentence(range)
                if (++sentencesSinceSave >= SAVE_EVERY) {
                    sentencesSinceSave = 0
                    book?.let { WearBookRepository.recordTime(it.id, ReadingSource.LISTEN, takeListening(stop = false), 0) }
                    saveProgress(range.first, toPhone = false)
                }
                if (serial == session.generation && TtsPlayback.state.value == TtsState.PLAYING) topUp(index)
            }
        }

        override fun onDone(utteranceId: String) {
            utteranceOver(utteranceId, failed = false)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            Log.w(TAG, "Utterance $utteranceId failed; skipping")
            utteranceOver(utteranceId, failed = true)
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            if (errorCode in VOICE_NOT_READY) {
                Log.w(TAG, "Utterance $utteranceId failed with $errorCode; waiting for the voice")
                scope.launch {
                    val index = session.index(utteranceId) ?: return@launch
                    if (TtsPlayback.state.value == TtsState.PLAYING) voiceNotReady(index)
                }
                return
            }
            Log.w(TAG, "Utterance $utteranceId failed with $errorCode; skipping")
            utteranceOver(utteranceId, failed = true)
        }
    }

    /** Tops the queue up when it is about to run dry. */
    private fun topUp(index: Int) {
        if (index >= nextToQueue - REFILL_AT && nextToQueue < sentences.size) queueMore()
    }

    /**
     * A sentence is over whether it was spoken or refused. A refused one never had its onStart, so
     * the top-up happens here, or the queue would run dry after one bad batch and the service sit
     * silent in PLAYING for good. A refused last sentence still ends the book, but at that
     * sentence rather than at the end of the text, so an engine that fails on everything does not
     * mark a book read. Anything left over from an earlier book is ignored.
     */
    private fun utteranceOver(utteranceId: String, failed: Boolean) {
        scope.launch {
            val index = session.index(utteranceId) ?: return@launch
            val serial = session.generation
            if (TtsPlayback.state.value != TtsState.PLAYING || index !in sentences.indices) return@launch
            if (failed) {
                current = index
                topUp(index)
            }
            if (serial != session.generation) return@launch
            if (index >= sentences.size - 1) {
                val offset = if (failed) sentences.getOrNull(index)?.first ?: text.length else text.length
                book?.let { WearBookRepository.recordTime(it.id, ReadingSource.LISTEN, takeListening(stop = true), 0) }
                if (!failed) {
                    finishedBook = true
                    book?.let { WearBookRepository.markFinished(it.id) }
                }
                saveProgress(offset, toPhone = true)
                if (serial == session.generation) finish()
            }
        }
    }

    private fun pause() {
        if (TtsPlayback.state.value != TtsState.PLAYING) return
        session.invalidate()
        tts?.stop()
        stopAt = -1
        waitingSince = 0L
        nextToQueue = maxOf(current, 0)
        TtsPlayback.state(TtsState.PAUSED)
        goForeground(titleOrLoading(), playing = false)
        val at = currentStartedAt
        val listened = takeListening(stop = true)
        scope.launch {
            book?.let { WearBookRepository.recordTime(it.id, ReadingSource.LISTEN, listened, 0) }
            sentences.getOrNull(current)?.let { saveProgress(it.first, toPhone = true, at) }
        }
    }

    private fun resume() {
        if (TtsPlayback.state.value != TtsState.PAUSED) return
        applyPrefs()
        TtsPlayback.state(TtsState.PLAYING)
        listenedSince = System.currentTimeMillis()
        goForeground(titleOrLoading(), playing = true)
        queueMore()
    }

    /**
     * The listening time since it was last counted, which is then counted from now, or no longer
     * when [stop]. A single stretch is capped, so an engine that hangs does not run up hours.
     */
    private fun takeListening(stop: Boolean): Long {
        val since = listenedSince
        if (since == 0L) return 0
        val now = System.currentTimeMillis()
        listenedSince = if (stop) 0L else now
        return (now - since).coerceIn(0, MAX_LISTEN_STRETCH_MS)
    }

    private fun finish() {
        session.invalidate()
        // stop() waits for the engine's lock, which its connection set-up holds for seconds on a
        // cold start; before the engine has reported ready there is nothing to stop anyway.
        if (engineReady) tts?.stop()
        TtsPlayback.set(TtsState.IDLE, null, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun saveProgress(offset: Int, toPhone: Boolean, atEpochMs: Long = System.currentTimeMillis()) {
        val b = book ?: return
        WearBookRepository.updateProgress(b.id, offset, atEpochMs)
        if (toPhone) WearBookRepository.sendProgressToPhone(b, offset, atEpochMs)
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onDestroy() {
        session.invalidate()
        destroyed = true
        pendingPlay = null
        val b = book
        val offset = if (finishedBook) text.length else sentences.getOrNull(current)?.first
        // The reading is stamped with the moment its sentence began, not with now: a jump the
        // reader made while it was being spoken carries a later stamp and must win at both ends.
        val at = if (finishedBook || currentStartedAt == 0L) System.currentTimeMillis() else currentStartedAt
        val listened = takeListening(stop = true)
        // The service's own scope goes first, so no save from the listener lands after this one.
        scope.cancel()
        if (b != null && offset != null) {
            // Nothing here may cancel the final reading: a scope tied to the service would, and
            // blocking onDestroy() on a database write and a message to the phone would stall the
            // main thread. It rides a job that outlives the service and finishes on its own.
            GlobalScope.launch(Dispatchers.IO + NonCancellable) {
                WearBookRepository.recordTime(b.id, ReadingSource.LISTEN, listened, 0)
                WearBookRepository.updateProgress(b.id, offset, at)
                WearBookRepository.sendProgressToPhone(b, offset, at)
            }
        }
        // stop() and shutdown() both take the engine's lock, which its connection set-up holds
        // while it talks to the speech service; waiting for that here would stall the main thread.
        val engine = tts
        tts = null
        thread(name = "tts-release") { runCatching { engine?.stop(); engine?.shutdown() } }
        TtsPlayback.set(TtsState.IDLE, null, null)
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
        private const val MAX_LISTEN_STRETCH_MS = 5 * 60 * 1000L
        /** Silence standing in for a sentence the watch has no voice for, so its place still passes. */
        private const val SKIP_MS = 150L
        /** Sentences in a row without a voice that make reading pause rather than pass them over. */
        private const val UNSAYABLE_STRETCH = 12
        /** How often a sentence waiting for its voice is tried again, and for how long in all. */
        private const val VOICE_RETRY_MS = 3_000L
        private const val VOICE_WAIT_MS = 60_000L
        /**
         * What Google's engine answers for a sentence whose voice it is still fetching: it tries
         * the voice over the network first, which times out or fails, or says it is not installed yet.
         */
        private val VOICE_NOT_READY = setOf(
            TextToSpeech.ERROR_NOT_INSTALLED_YET,
            TextToSpeech.ERROR_NETWORK_TIMEOUT,
            TextToSpeech.ERROR_NETWORK,
        )

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
