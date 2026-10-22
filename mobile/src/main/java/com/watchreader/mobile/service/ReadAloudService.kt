package com.watchreader.mobile.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.ServiceCompat
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.mobile.tts.AudioFocusKeeper
import com.watchreader.mobile.tts.BookLanguages
import com.watchreader.mobile.tts.LanguageDetector
import com.watchreader.mobile.tts.MediaButtonClaim
import com.watchreader.mobile.tts.ReadAloudNotice
import com.watchreader.mobile.tts.ReadAloudNotification
import com.watchreader.mobile.tts.ReadAloudPlayback
import com.watchreader.mobile.tts.ReadAloudPrefs
import com.watchreader.mobile.tts.ReadAloudScript
import com.watchreader.mobile.tts.ReadAloudSession
import com.watchreader.mobile.tts.ReadAloudState
import com.watchreader.mobile.tts.SleepTimer
import com.watchreader.mobile.tts.SleepTimerAlarm
import com.watchreader.mobile.tts.TtsLanguages
import com.watchreader.mobile.tts.UtteranceSession
import com.watchreader.mobile.tts.VoiceDownloads
import com.watchreader.shared.BookToc
import com.watchreader.shared.Chapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.concurrent.thread

private const val TAG = "WatchReader"

/**
 * Reads a book aloud on the phone as a foreground media service, so the voice carries on with the
 * screen off and after the reader is closed. It is the watch's read-aloud service brought to the
 * phone, with what a phone adds: a media session for the notification, the lock screen and a
 * headset's buttons, audio focus, and a pause when headphones come out.
 *
 * The book is read sentence by sentence through the system's TextToSpeech, each sentence in the
 * language it is written in, from the reader's place, a character offset into the book's text.
 * The reader screen follows [ReadAloudPlayback] to turn its pages and tint the sentence being
 * spoken. The place reached is saved as the voice goes, through the same path a page turned by
 * hand takes, and the watch hears of it the same way: so a book closed while it was being read
 * aloud opens where the voice got to, here and on the watch.
 *
 * Everything runs on the main thread; the engine's callbacks are brought there before they touch
 * anything.
 */
class ReadAloudService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tts: TextToSpeech? = null
    private var engineReady = false
    private var pendingPlay: Pair<String, Int>? = null

    private lateinit var prefs: ReadAloudPrefs
    private lateinit var notifications: ReadAloudNotification
    private lateinit var media: ReadAloudSession
    private lateinit var focus: AudioFocusKeeper
    private var foreground = false

    private var book: Book? = null
    private var script: ReadAloudScript? = null
    private var chapters: List<Chapter> = emptyList()
    private var chapterTitle: String? = null
    private var cover: Bitmap? = null
    /** Where reading began, part-way into a sentence when the reader's page began there. */
    private var start: ReadAloudScript.Position? = null
    private var nextToQueue = 0
    /** The sentence being spoken, or the one reading will begin with. */
    private var current = 0
    /** When the current sentence began; 0 until one has. The saved place is stamped with it. */
    private var currentStartedAt = 0L
    /** Set in onDestroy so an engine that reports ready afterwards is ignored. */
    private var destroyed = false
    private var currentLocale: Locale? = null
    private var currentVoice: String? = null
    /** What the book says about its languages, as worked out from its text. */
    private var surveyed = LanguageDetector.Book()
    /** The same, with the reader's own choice of language for Latin-script text, when made. */
    private var bookLanguages = LanguageDetector.Book()
    /** Whether the engine can say each language met while reading this book; asked once per language. */
    private val sayable = HashMap<Locale, Boolean>()
    private var engineVoices: List<TtsLanguages.VoiceInfo>? = null
    /** Languages already reported as having no voice, so each is reported once per book. */
    private val warned = HashSet<Locale>()
    /** Sentences queued as silence for want of a voice, reported when reading reaches them. */
    private val skipped = HashSet<Int>()
    /** The sentence at which reading stops for a long stretch without a voice; -1 when there is none. */
    private var stopAt = -1
    /** The first sentence of the next chapter, when the sleep timer is set to the chapter's end; -1 otherwise. */
    private var chapterStop = -1
    /** The language reading paused for want of a voice, which the notification then names. */
    private var pausedFor: Locale? = null
    /** When reading began waiting for a voice the engine is fetching; 0 when it is not waiting. */
    private var waitingSince = 0L
    private var sentencesSinceSave = 0
    private var savesSinceSync = 0
    /** When the listening under way was last counted; 0 while nothing is playing. */
    private var listenedSince = 0L
    private var finishedBook = false
    private var pausedTimeout: Job? = null

    /** Bumped by every play() and every move; work left over from before checks it and stands down. */
    private val session = UtteranceSession()

    private val sleepTimerAlarm by lazy {
        SleepTimerAlarm(getSystemService(android.app.AlarmManager::class.java), Handler(Looper.getMainLooper()))
    }
    private val sleepTimer = SleepTimer(
        elapsedRealtime = SystemClock::elapsedRealtime,
        schedule = { deadline, callback -> sleepTimerAlarm.schedule(deadline, callback) },
        onChanged = ReadAloudPlayback::sleepTimer,
        onExpired = { pause() },
    )

    private val state: ReadAloudState get() = ReadAloudPlayback.state.value

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = ReadAloudPrefs(this)
        notifications = ReadAloudNotification(this) { action -> Intent(this, ReadAloudService::class.java).setAction(action) }
        media = ReadAloudSession(this, ReadAloudNotification.openApp(this), object : ReadAloudSession.Controls {
            override fun play() = resume()
            override fun pause() = this@ReadAloudService.pause()
            override fun skip(by: Int) = this@ReadAloudService.skip(by)
            override fun stop() = finish()
        })
        focus = AudioFocusKeeper(this, onPause = { passing -> pause(passing) }, onResume = { resume() })
        tts = TextToSpeech(this) { status ->
            if (destroyed) return@TextToSpeech
            engineReady = status == TextToSpeech.SUCCESS
            if (!engineReady) {
                Log.e(TAG, "TTS engine failed to initialise")
                ReadAloudPlayback.notice(ReadAloudNotice.NoEngine)
                finish()
                return@TextToSpeech
            }
            tts?.setAudioAttributes(AudioFocusKeeper.ATTRIBUTES)
            tts?.setOnUtteranceProgressListener(listener)
            pendingPlay?.let { (id, offset) -> pendingPlay = null; play(id, offset) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_PLAY) {
            // The system demands startForeground() promptly after startForegroundService(), even
            // if the book then turns out to be missing and the service stops straight away.
            publish()
            val id = intent.getStringExtra(EXTRA_BOOK_ID)
            if (id == null) {
                finish()
                return START_NOT_STICKY
            }
            val offset = intent.getIntExtra(EXTRA_OFFSET, 0)
            if ((pendingPlay?.first == id || ReadAloudPlayback.bookId.value == id) && state == ReadAloudState.LOADING) return START_NOT_STICKY
            sleepTimer.cancel()
            clearChapterStop()
            ReadAloudPlayback.set(ReadAloudState.LOADING, id, null)
            if (engineReady) play(id, offset) else pendingPlay = id to offset
            return START_NOT_STICKY
        }
        // A control that arrives after reading stopped (a stale notification, a screen behind the
        // times) must not leave a service up with nothing to read.
        if (book == null && pendingPlay == null && state == ReadAloudState.IDLE) {
            finish()
            return START_NOT_STICKY
        }
        val forThisBook = intent?.getStringExtra(EXTRA_BOOK_ID).let { it == null || it == ReadAloudPlayback.bookId.value }
        when (action) {
            ReadAloudNotification.ACTION_PAUSE -> pause()
            ReadAloudNotification.ACTION_RESUME -> resume()
            ReadAloudNotification.ACTION_NEXT -> skip(1)
            ReadAloudNotification.ACTION_PREVIOUS -> skip(-1)
            ReadAloudNotification.ACTION_STOP -> finish()
            ACTION_SEEK -> if (forThisBook) seek(intent!!.getIntExtra(EXTRA_OFFSET, 0))
            ACTION_SETTINGS -> settingsChanged()
            ACTION_SLEEP_TIMER -> if (forThisBook) setSleep(intent!!.getIntExtra(EXTRA_SLEEP_MINUTES, -1))
        }
        return START_NOT_STICKY
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun play(bookId: String, offset: Int) {
        // The book read until now is saved only every few sentences; keep its exact place,
        // stamped with the moment its sentence began so that a later jump still wins.
        val left = book
        val leftAt = spokenStart()
        val leftStamp = currentStartedAt
        val leftFinished = finishedBook
        val leftListened = takeListening(stop = true)
        if (left != null) {
            GlobalScope.launch(Dispatchers.IO + NonCancellable) {
                BookRepository.recordListening(left.id, leftListened)
                if (leftAt != null && leftStamp != 0L && !leftFinished) {
                    BookRepository.saveProgress(applicationContext, left, leftAt, leftStamp, toWatch = true)
                }
            }
        }
        val serial = session.invalidate()
        if (engineReady) tts?.stop()
        finishedBook = false
        book = null
        currentStartedAt = 0L
        pausedFor = null
        cancelPausedTimeout()
        ReadAloudPlayback.set(ReadAloudState.LOADING, bookId, null)
        publish()
        scope.launch {
            val loaded = BookRepository.getById(bookId)
            if (loaded == null) {
                if (serial == session.generation) finish()
                return@launch
            }
            // A book whose file has gone has nothing to read.
            val loadedText = runCatching { BookRepository.loadText(loaded) }.getOrElse {
                if (serial == session.generation) finish()
                return@launch
            }
            if (serial != session.generation) return@launch
            // Every sentence of the book is found, the book is looked over for its languages, and
            // its contents are read for the chapter titles; a novel's worth is too much for the
            // main thread, so all of it happens on a worker.
            val prepared = withContext(Dispatchers.Default) {
                Prepared(
                    ReadAloudScript.of(loadedText),
                    LanguageDetector.survey(loadedText, Locale.getDefault()),
                    BookToc.resolve(loaded.tocJson, loadedText),
                    loadCover(loaded.coverPath),
                )
            }
            if (serial != session.generation) return@launch
            val built = prepared.script
            val position = built.position(offset)
            if (position.index >= built.size) {
                // The place is past the last sentence: there is nothing left to read.
                finish()
                return@launch
            }
            book = loaded
            script = built
            chapters = prepared.chapters
            cover = prepared.cover
            surveyed = prepared.languages
            bookLanguages = withChoices(surveyed, loaded.id)
            sayable.clear()
            engineVoices = null
            warned.clear()
            skipped.clear()
            stopAt = -1
            waitingSince = 0L
            // The voices the book needs that are not on the phone yet are asked for now, rather
            // than when the first sentence in each comes up and fails for want of it.
            scope.launch {
                val needed = withContext(Dispatchers.Default) { runCatching { BookLanguages.of(loaded, loadedText) }.getOrNull() }
                // Pausing or skipping meanwhile changes nothing; another book coming in does.
                if (needed != null && !destroyed && book === loaded) {
                    ReadAloudPlayback.languages(needed)
                    VoiceDownloads.request(this@ReadAloudService, needed)
                }
            }
            start = position.takeIf { it.from > built.sentences[it.index].first }
            current = position.index
            nextToQueue = position.index
            sentencesSinceSave = 0
            savesSinceSync = 0
            chapterTitle = chapterAt(built.sentences[current].first)
            describe()
            if (!focus.acquire()) {
                // A call holds the audio. The book opens paused at the place, ready for play.
                ReadAloudPlayback.set(ReadAloudState.PAUSED, bookId, built.spoken(current, start))
                ReadAloudPlayback.notice(ReadAloudNotice.NoAudioFocus)
                publish()
                startPausedTimeout()
                return@launch
            }
            applyPrefs()
            ReadAloudPlayback.set(ReadAloudState.PLAYING, bookId, null)
            listenedSince = System.currentTimeMillis()
            publish()
            MediaButtonClaim.claim()
            queueMore()
        }
    }

    private class Prepared(val script: ReadAloudScript, val languages: LanguageDetector.Book, val chapters: List<Chapter>, val cover: Bitmap?)

    /** The book's languages with the reader's choice for its Latin-script text, when there is one. */
    private fun withChoices(languages: LanguageDetector.Book, bookId: String): LanguageDetector.Book =
        prefs.latinLanguage(bookId)?.let { languages.copy(latin = it) } ?: languages

    private fun applyPrefs() {
        tts?.setSpeechRate(prefs.speechRate)
        // The voice is set again for the next sentence, in case the choice of voice has changed.
        currentLocale = null
        currentVoice = null
    }

    /** Keeps the engine fed a batch at a time; a whole novel queued at once makes some engines stall. */
    private fun queueMore() {
        if (sleepTimer.checkExpired()) return
        val engine = tts ?: return
        val s = script ?: return
        // Reading is to stop at a stretch the phone cannot say; nothing goes in after it.
        if (stopAt >= 0) return
        val end = minOf(limit(), nextToQueue + BATCH)
        for (i in nextToQueue until end) {
            val sentence = s.words(s.spoken(i, start))
            // Every sentence is spoken in the language it is written in.
            val locale = LanguageDetector.detect(sentence, bookLanguages)
            val id = session.id(i)
            val result = if (canSay(engine, locale)) {
                useVoice(engine, locale)
                val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
                engine.speak(sentence, TextToSpeech.QUEUE_ADD, params, id)
            } else if (unsayableStretch(engine, i)) {
                // A long run of a language the phone has no voice for, or the rest of the book:
                // racing through it in silence would lose the reader's place and could mark the
                // book read. Reading pauses where it begins, once what is queued before it is said.
                stopAt = i
                nextToQueue = i + 1
                if (engine.playSilentUtterance(0, TextToSpeech.QUEUE_ADD, id) != TextToSpeech.SUCCESS) finish()
                return
            } else {
                // A sentence or two in a language the phone has no voice for is passed over, not
                // read in the voice of another, which says it wrong or not at all.
                skipped += i
                engine.playSilentUtterance(SKIP_MS, TextToSpeech.QUEUE_ADD, id)
            }
            if (result != TextToSpeech.SUCCESS) {
                // A sentence the engine refuses to take never gets an onError, so the queue would
                // drain and the service sit silent in PLAYING. The place is saved on the way out.
                Log.w(TAG, "Engine refused sentence $i; stopping")
                nextToQueue = i
                finish()
                return
            }
        }
        nextToQueue = end
    }

    /** One past the last sentence to queue: the end of the book, or of the chapter the timer ends with. */
    private fun limit(): Int {
        val size = script?.size ?: 0
        return if (chapterStop >= 0) minOf(chapterStop, size) else size
    }

    /** Sets the engine to the reader's chosen voice for [locale], or to the language when none is chosen. */
    private fun useVoice(engine: TextToSpeech, locale: Locale) {
        val chosen = prefs.voice(locale)
        if (locale == currentLocale && chosen == currentVoice) return
        val voice = chosen?.let { TtsLanguages.voiceNamed(engine, it) }
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) engine.setLanguage(locale)
        currentLocale = locale
        currentVoice = chosen
    }

    private fun sentenceAt(index: Int): String = script!!.let { it.words(it.sentences[index]) }

    /**
     * Whether the engine has, or can fetch, a voice for [locale]. A language it can fetch counts:
     * the first sentence in it waits for the voice (see [voiceNotReady]).
     */
    private fun canSay(engine: TextToSpeech, locale: Locale): Boolean = sayable.getOrPut(locale) {
        val voices = engineVoices ?: TtsLanguages.voices(engine).also { engineVoices = it }
        TtsLanguages.stateOf(engine, locale, voices) != TtsLanguages.State.NONE
    }

    /** Whether the sentences from [from] on, as many as make a long stretch, are all ones the phone cannot say. */
    private fun unsayableStretch(engine: TextToSpeech, from: Int): Boolean {
        val size = script?.size ?: return true
        for (j in from until minOf(size, from + UNSAYABLE_STRETCH)) {
            if (canSay(engine, LanguageDetector.detect(sentenceAt(j), bookLanguages))) return false
        }
        return true
    }

    /** Tells the reader, once per language per book, that the phone has no voice for [locale]. */
    private fun noVoice(locale: Locale, pause: Boolean) {
        if (!pause && !warned.add(locale)) return
        Log.w(TAG, "No voice for ${locale.toLanguageTag()}; ${if (pause) "pausing" else "skipping"}")
        ReadAloudPlayback.notice(ReadAloudNotice.NoVoice(locale))
    }

    /**
     * The engine could not say sentence [index] because the voice for its language is not on the
     * phone yet; the first sentence in a language new to the phone fails this way while the
     * engine fetches the voice. The rest of the batch would fail the same way, so it is dropped,
     * and the sentence is tried again every few seconds until the voice has come. Without a
     * connection, or after too long, reading pauses at that sentence instead and says why.
     */
    private fun voiceNotReady(index: Int) {
        val engine = tts ?: return
        val s = script ?: return
        if (index !in s.sentences.indices) return
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
            pausedFor = locale
            publish()
            ReadAloudPlayback.notice(if (connected) ReadAloudNotice.VoiceFailed(locale) else ReadAloudNotice.VoiceOffline(locale))
            return
        }
        ReadAloudPlayback.fetching(locale)
        publish()
        val serial = session.generation
        scope.launch {
            delay(VOICE_RETRY_MS)
            if (serial == session.generation && state == ReadAloudState.PLAYING) queueMore()
        }
    }

    private fun online(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = runCatching { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }.getOrNull()
            ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            scope.launch {
                val index = session.index(utteranceId) ?: return@launch
                if (sleepTimer.checkExpired()) return@launch
                val serial = session.generation
                if (state != ReadAloudState.PLAYING) return@launch
                val s = script ?: return@launch
                if (index !in s.sentences.indices) return@launch
                if (index == stopAt) {
                    // The long stretch without a voice has come: pause at its start and say why.
                    current = index
                    val locale = LanguageDetector.detect(sentenceAt(index), bookLanguages)
                    pause()
                    pausedFor = locale
                    publish()
                    noVoice(locale, pause = true)
                    return@launch
                }
                if (waitingSince != 0L) {
                    waitingSince = 0L
                    ReadAloudPlayback.fetching(null)
                    publish()
                }
                if (skipped.remove(index)) noVoice(LanguageDetector.detect(sentenceAt(index), bookLanguages), pause = false)
                current = index
                currentStartedAt = System.currentTimeMillis()
                val range = s.spoken(index, start)
                ReadAloudPlayback.sentence(range)
                chapterAt(range.first).let { if (it != chapterTitle) { chapterTitle = it; describe(); publish() } }
                if (++sentencesSinceSave >= SAVE_EVERY) {
                    sentencesSinceSave = 0
                    val toWatch = ++savesSinceSync >= SYNC_EVERY_SAVES
                    if (toWatch) savesSinceSync = 0
                    val listened = takeListening(stop = false)
                    book?.let { BookRepository.recordListening(it.id, listened) }
                    saveProgress(range.first, toWatch)
                }
                if (serial == session.generation && state == ReadAloudState.PLAYING) topUp(index)
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
                    if (sleepTimer.checkExpired()) return@launch
                    if (state == ReadAloudState.PLAYING) voiceNotReady(index)
                }
                return
            }
            Log.w(TAG, "Utterance $utteranceId failed with $errorCode; skipping")
            utteranceOver(utteranceId, failed = true)
        }
    }

    /** Tops the queue up when it is about to run dry. */
    private fun topUp(index: Int) {
        if (index >= nextToQueue - REFILL_AT && nextToQueue < limit()) queueMore()
    }

    /**
     * A sentence is over whether it was spoken or refused. A refused one never had its onStart, so
     * the top-up happens here, or the queue would run dry after one bad batch. A refused last
     * sentence still ends the book, but at that sentence rather than at the end of the text, so an
     * engine that fails on everything does not mark a book read. The last sentence of a chapter
     * the sleep timer ends with pauses reading before the next chapter begins.
     */
    private fun utteranceOver(utteranceId: String, failed: Boolean) {
        scope.launch {
            val index = session.index(utteranceId) ?: return@launch
            if (sleepTimer.checkExpired()) return@launch
            val serial = session.generation
            val s = script ?: return@launch
            if (state != ReadAloudState.PLAYING || index !in s.sentences.indices) return@launch
            if (failed) {
                current = index
                topUp(index)
            }
            if (serial != session.generation) return@launch
            if (index >= s.size - 1) {
                val offset = if (failed) s.spoken(index, start).first else s.text.length
                val listened = takeListening(stop = true)
                book?.let { BookRepository.recordListening(it.id, listened) }
                if (!failed) {
                    finishedBook = true
                    book?.let { BookRepository.markFinished(it.id) }
                }
                saveProgress(offset, toWatch = true)
                if (serial == session.generation) finish()
            } else if (chapterStop >= 0 && index >= chapterStop - 1) {
                // The chapter is over. Reading pauses at the first sentence of the next one, which
                // is where it is saved and where play goes on from.
                current = chapterStop.coerceAtMost(s.size - 1)
                start = null
                currentStartedAt = System.currentTimeMillis()
                ReadAloudPlayback.sentence(s.sentences[current])
                pause()
            }
        }
    }

    /**
     * Stops the voice where it is. A manual pause, a pause for a missing voice and the sleep
     * timer's own all end the sleep timer; resuming starts without one. A [passing] pause, made
     * because a call or a navigation prompt took the audio for a moment, keeps the timer and the
     * claim on the audio, so reading goes on when the interruption ends.
     */
    private fun pause(passing: Boolean = false) {
        if (!passing) {
            sleepTimer.cancel()
            clearChapterStop()
        }
        if (state != ReadAloudState.PLAYING) {
            // Paused already: a pause pressed now means reading is not to go on by itself.
            if (!passing) focus.release()
            return
        }
        session.invalidate()
        tts?.stop()
        stopAt = -1
        waitingSince = 0L
        nextToQueue = current
        ReadAloudPlayback.state(ReadAloudState.PAUSED)
        focus.release(keepForResume = passing)
        publish()
        startPausedTimeout()
        val at = currentStartedAt
        val offset = spokenStart()
        val listened = takeListening(stop = true)
        scope.launch {
            book?.let { BookRepository.recordListening(it.id, listened) }
            if (offset != null && at != 0L) saveProgress(offset, toWatch = true, atEpochMs = at)
        }
    }

    private fun resume() {
        if (state != ReadAloudState.PAUSED || script == null) return
        if (!focus.acquire()) {
            ReadAloudPlayback.notice(ReadAloudNotice.NoAudioFocus)
            return
        }
        cancelPausedTimeout()
        pausedFor = null
        applyPrefs()
        ReadAloudPlayback.state(ReadAloudState.PLAYING)
        listenedSince = System.currentTimeMillis()
        publish()
        MediaButtonClaim.claim()
        queueMore()
    }

    /** Moves by [by] sentences, from the notification, a headset or the reader's player bar. */
    private fun skip(by: Int) {
        val s = script ?: return
        if (state != ReadAloudState.PLAYING && state != ReadAloudState.PAUSED) return
        val target = if (by < 0) s.previous(current, start) else s.next(current) ?: return
        moveTo(target)
    }

    /** Goes on from [offset], where the reader turned or jumped to while listening. */
    private fun seek(offset: Int) {
        val s = script ?: return
        if (state != ReadAloudState.PLAYING && state != ReadAloudState.PAUSED) return
        val target = s.position(offset)
        if (target.index >= s.size) return
        moveTo(target)
    }

    /**
     * Reading moves to [target]: what is queued is dropped and, while playing, the voice goes on
     * from there. The reader is told at once, so its page follows a skip even while paused.
     */
    private fun moveTo(target: ReadAloudScript.Position) {
        val s = script ?: return
        session.invalidate()
        if (engineReady) tts?.stop()
        stopAt = -1
        waitingSince = 0L
        skipped.clear()
        ReadAloudPlayback.fetching(null)
        start = target.takeIf { it.from > s.sentences[it.index].first }
        current = target.index
        nextToQueue = target.index
        currentLocale = null
        currentStartedAt = System.currentTimeMillis()
        if (chapterStop >= 0) chapterStop = s.chapterEnd(chapters, current)
        val range = s.spoken(current, start)
        ReadAloudPlayback.sentence(range)
        chapterAt(range.first).let { if (it != chapterTitle) { chapterTitle = it; describe() } }
        publish()
        if (state == ReadAloudState.PLAYING) queueMore()
        else scope.launch { saveProgress(range.first, toWatch = false, atEpochMs = currentStartedAt) }
    }

    /** The speed, a voice or the book's language was changed in the reader; the sentence begins again with it. */
    private fun settingsChanged() {
        val b = book ?: return
        bookLanguages = withChoices(surveyed, b.id)
        sayable.clear()
        engineVoices = null
        applyPrefs()
        if (state == ReadAloudState.PLAYING) {
            val s = script ?: return
            moveTo(ReadAloudScript.Position(current, s.spoken(current, start).first))
        } else {
            publish()
        }
    }

    /** Zero turns the timer off, [SLEEP_CHAPTER_END] stops at the end of the chapter; timers are set only while playing. */
    private fun setSleep(minutes: Int) {
        when {
            minutes == 0 -> {
                sleepTimer.cancel()
                clearChapterStop(carryOn = true)
            }
            state != ReadAloudState.PLAYING -> Unit
            minutes == SLEEP_CHAPTER_END -> {
                val s = script ?: return
                sleepTimer.cancel()
                chapterStop = s.chapterEnd(chapters, current)
                ReadAloudPlayback.sleepAtChapterEnd(true)
            }
            minutes in SleepTimer.MINUTES -> {
                clearChapterStop(carryOn = true)
                try {
                    sleepTimer.start(minutes)
                } catch (failure: RuntimeException) {
                    Log.w(TAG, "Could not schedule sleep timer", failure)
                    android.widget.Toast.makeText(this, R.string.listen_sleep_unavailable, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** [carryOn] when reading goes on: the queue was cut at the chapter's end and runs past it now. */
    private fun clearChapterStop(carryOn: Boolean = false) {
        if (chapterStop < 0) return
        chapterStop = -1
        ReadAloudPlayback.sleepAtChapterEnd(false)
        if (carryOn && state == ReadAloudState.PLAYING) queueMore()
    }

    /** A service left paused does not hold its notification for ever; the place is saved already. */
    private fun startPausedTimeout() {
        cancelPausedTimeout()
        pausedTimeout = scope.launch {
            delay(PAUSED_TIMEOUT_MS)
            if (state == ReadAloudState.PAUSED) finish()
        }
    }

    private fun cancelPausedTimeout() {
        pausedTimeout?.cancel()
        pausedTimeout = null
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

    /** Where the sentence being spoken begins, part-way in when reading began there; null before any. */
    private fun spokenStart(): Int? {
        val s = script ?: return null
        if (current !in s.sentences.indices) return null
        return s.spoken(current, start).first
    }

    private fun chapterAt(offset: Int): String? = chapters.lastOrNull { it.start <= offset }?.title

    private fun finish() {
        sleepTimer.cancel()
        clearChapterStop()
        cancelPausedTimeout()
        pendingPlay = null
        session.invalidate()
        // stop() waits for the engine's lock, which its connection set-up holds for seconds on a
        // cold start; before the engine has reported ready there is nothing to stop anyway.
        if (engineReady) tts?.stop()
        focus.release()
        ReadAloudPlayback.set(ReadAloudState.IDLE, null, null)
        media.playback(ReadAloudState.IDLE, 1f)
        foreground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun saveProgress(offset: Int, toWatch: Boolean, atEpochMs: Long = System.currentTimeMillis()) {
        val b = book ?: return
        withContext(Dispatchers.IO + NonCancellable) {
            BookRepository.saveProgress(applicationContext, b, offset, atEpochMs, toWatch)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onDestroy() {
        sleepTimer.cancel()
        session.invalidate()
        destroyed = true
        pendingPlay = null
        val b = book
        val offset = if (finishedBook) script?.text?.length else spokenStart()
        // The reading is stamped with the moment its sentence began, not with now: a jump the
        // reader made on the watch while it was being spoken carries a later stamp and must win.
        val at = if (finishedBook) System.currentTimeMillis() else currentStartedAt
        val listened = takeListening(stop = true)
        // The service's own scope goes first, so no save from the listener lands after this one.
        scope.cancel()
        if (b != null && offset != null && at != 0L) {
            // Nothing here may cancel the final save, and blocking onDestroy() on a database write
            // and a message to the watch would stall the main thread: it rides a job of its own.
            GlobalScope.launch(Dispatchers.IO + NonCancellable) {
                BookRepository.recordListening(b.id, listened)
                BookRepository.saveProgress(applicationContext, b, offset, at, toWatch = true)
            }
        }
        focus.release()
        media.release()
        // stop() and shutdown() both take the engine's lock; waiting for that here would stall.
        val engine = tts
        tts = null
        thread(name = "tts-release") { runCatching { engine?.stop(); engine?.shutdown() } }
        ReadAloudPlayback.set(ReadAloudState.IDLE, null, null)
        super.onDestroy()
    }

    /** Brings the media session and the notification up to date with the state of reading. */
    private fun publish() {
        val current = state
        media.playback(current, prefs.speechRate)
        val title = book?.title ?: getString(R.string.listen_loading)
        val waitingFor = ReadAloudPlayback.fetching.value
        val missing = pausedFor
        val status = when {
            current == ReadAloudState.PLAYING && waitingFor != null -> getString(R.string.listen_fetching, TtsLanguages.label(waitingFor))
            current == ReadAloudState.PAUSED && missing != null -> getString(R.string.listen_no_voice, TtsLanguages.label(missing))
            current == ReadAloudState.PLAYING -> chapterTitle ?: getString(R.string.listen_reading)
            current == ReadAloudState.PAUSED -> getString(R.string.listen_paused)
            else -> getString(R.string.listen_loading)
        }
        val playing = current == ReadAloudState.PLAYING || current == ReadAloudState.LOADING
        show(notifications.build(title, status, playing, cover, media.token))
    }

    private fun describe() {
        val b = book ?: return
        media.describe(b.title, b.author, chapterTitle, cover)
    }

    /**
     * The first notification puts the service in the foreground; later ones only replace it, so
     * no update can run into the limits on starting foreground work from the background.
     */
    private fun show(notification: Notification) {
        if (foreground) {
            runCatching { getSystemService(NotificationManager::class.java)?.notify(ReadAloudNotification.ID, notification) }
            return
        }
        notifications.ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, ReadAloudNotification.ID, notification, type)
        foreground = true
    }

    /** A cover small enough for the notification and the lock screen, read once per book. */
    private fun loadCover(path: String?): Bitmap? = runCatching {
        if (path == null) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_PX && bounds.outHeight / (sample * 2) >= COVER_PX) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    companion object {
        private const val ACTION_PLAY = "com.watchreader.listen.PLAY"
        private const val ACTION_SEEK = "com.watchreader.listen.SEEK"
        private const val ACTION_SETTINGS = "com.watchreader.listen.SETTINGS"
        private const val ACTION_SLEEP_TIMER = "com.watchreader.listen.SLEEP_TIMER"
        private const val EXTRA_BOOK_ID = "book_id"
        private const val EXTRA_OFFSET = "offset"
        private const val EXTRA_SLEEP_MINUTES = "sleep_minutes"
        /** The sleep timer's "end of chapter", sent where the minutes go. */
        const val SLEEP_CHAPTER_END = -2
        private const val BATCH = 24
        private const val REFILL_AT = 8
        /** The place is saved every this many sentences... */
        private const val SAVE_EVERY = 10
        /** ...and the watch hears of it every this many saves, as it does every few pages turned by hand. */
        private const val SYNC_EVERY_SAVES = 4
        private const val MAX_LISTEN_STRETCH_MS = 5 * 60 * 1000L
        /** Silence standing in for a sentence the phone has no voice for, so its place still passes. */
        private const val SKIP_MS = 150L
        /** Sentences in a row without a voice that make reading pause rather than pass them over. */
        private const val UNSAYABLE_STRETCH = 12
        /** How often a sentence waiting for its voice is tried again, and for how long in all. */
        private const val VOICE_RETRY_MS = 3_000L
        private const val VOICE_WAIT_MS = 60_000L
        /** How long reading stays paused, notification and all, before the service lets go. */
        private const val PAUSED_TIMEOUT_MS = 30 * 60 * 1000L
        private const val COVER_PX = 256
        /**
         * What Google's engine answers for a sentence whose voice it is still fetching: it tries
         * the voice over the network first, which times out or fails, or says it is not installed yet.
         */
        private val VOICE_NOT_READY = setOf(
            TextToSpeech.ERROR_NOT_INSTALLED_YET,
            TextToSpeech.ERROR_NETWORK_TIMEOUT,
            TextToSpeech.ERROR_NETWORK,
        )

        private fun intent(context: Context, action: String) = Intent(context, ReadAloudService::class.java).setAction(action)

        /** Reads [bookId] aloud from [offset], the reader's place in its text. Call from a screen in front. */
        fun play(context: Context, bookId: String, offset: Int) {
            context.startForegroundService(intent(context, ACTION_PLAY).putExtra(EXTRA_BOOK_ID, bookId).putExtra(EXTRA_OFFSET, offset))
        }

        fun pause(context: Context) = control(context, intent(context, ReadAloudNotification.ACTION_PAUSE))
        fun resume(context: Context) = control(context, intent(context, ReadAloudNotification.ACTION_RESUME))
        fun stop(context: Context) = control(context, intent(context, ReadAloudNotification.ACTION_STOP))
        fun skip(context: Context, by: Int) =
            control(context, intent(context, if (by < 0) ReadAloudNotification.ACTION_PREVIOUS else ReadAloudNotification.ACTION_NEXT))

        /** The reader moved while listening to [bookId]: the voice goes on from [offset]. */
        fun seek(context: Context, bookId: String, offset: Int) =
            control(context, intent(context, ACTION_SEEK).putExtra(EXTRA_BOOK_ID, bookId).putExtra(EXTRA_OFFSET, offset))

        /** The speed, a voice or a book's language changed in [ReadAloudPrefs]. */
        fun settingsChanged(context: Context) = control(context, intent(context, ACTION_SETTINGS))

        /** Zero turns the timer off; it is set only for the book being read, while it plays. */
        fun setSleepTimer(context: Context, bookId: String, minutes: Int) {
            require(minutes == 0 || minutes == SLEEP_CHAPTER_END || minutes in SleepTimer.MINUTES)
            control(context, intent(context, ACTION_SLEEP_TIMER).putExtra(EXTRA_BOOK_ID, bookId).putExtra(EXTRA_SLEEP_MINUTES, minutes))
        }

        /** A book that is going away is not read on from memory. */
        fun stopIfPlaying(context: Context, bookId: String) {
            if (ReadAloudPlayback.bookId.value == bookId) stop(context)
        }

        /** Controls only reach a service that is reading; one that has stopped is not started again for them. */
        private fun control(context: Context, intent: Intent) {
            if (ReadAloudPlayback.state.value == ReadAloudState.IDLE) return
            runCatching { context.startService(intent) }.onFailure { Log.w(TAG, "Could not reach the read-aloud service", it) }
        }
    }
}
