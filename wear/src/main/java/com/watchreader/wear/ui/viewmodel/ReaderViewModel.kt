package com.watchreader.wear.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.watchreader.wear.data.model.WearBook
import com.watchreader.wear.data.repository.WearBookRepository
import com.watchreader.shared.Chapter
import com.watchreader.shared.reader.LineMeasurer
import com.watchreader.shared.reader.PageGeometry
import com.watchreader.shared.reader.Paginator
import com.watchreader.shared.reader.JumpHistory
import com.watchreader.shared.stats.ReadingClock
import com.watchreader.shared.stats.ReadingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ReaderUiState {
    object Loading : ReaderUiState()
    object Missing : ReaderUiState()
    data class Ready(
        val title: String,
        val page: Paginator.Page,
        val totalChars: Int,
        val chapters: List<Chapter>,
        val canUndoJump: Boolean = false,
    ) : ReaderUiState() {
        /**
         * How far through the book this page begins, and the whole book on its last page. The
         * library shows the saved place the same way (a last page is saved as the end of the
         * book), so the two always agree and a book read to its end reaches 100%.
         */
        val fraction: Float get() = when {
            totalChars == 0 -> 0f
            atEnd -> 1f
            else -> page.start.toFloat() / totalChars
        }
        val atEnd: Boolean get() = page.end >= totalChars
    }
}

/**
 * Owns the book text and the current page. Pages are laid out by a [Paginator] against the
 * geometry and measurer the screen supplies (it knows the font and the screen shape); until they
 * arrive the state stays Loading.
 */
class ReaderViewModel(
    application: Application,
    private val bookId: String,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var book: WearBook? = null
    private var text: String = ""
    private var chapters: List<Chapter> = emptyList()
    private var loaded = false
    private var layout: Pair<PageGeometry, LineMeasurer>? = null
    private var paginator: Paginator? = null
    private var page: Paginator.Page? = null
    private var restoreOffset = 0
    private var flipsSinceSync = 0
    private var syncJob: Job? = null
    private val jumpHistory = JumpHistory()

    /** The page the reader last turned to and when; a page merely left open is not a reading. */
    private var lastMove: Pair<Int, Long>? = null

    /**
     * The stamp of the latest reading this screen knows of, its own or one it has followed. A
     * row carrying a later one was read on the phone while this screen sat open or in the
     * background, and the page moves there: turning on from the page left open would stamp the
     * old place with the time of the turn and send it back over the newer reading.
     */
    private var knownStamp = 0L

    /** Reading time: runs while the page is on screen and the voice is not reading it aloud. */
    private val clock = ReadingClock()
    private var screenUp = false
    private var listening = false

    init {
        viewModelScope.launch {
            val found = WearBookRepository.getById(bookId)
            if (found == null) {
                _state.value = ReaderUiState.Missing
                return@launch
            }
            book = found
            text = runCatching { WearBookRepository.loadText(found) }.getOrElse {
                _state.value = ReaderUiState.Missing
                return@launch
            }
            restoreOffset = found.readOffsetChars.coerceIn(0, text.length)
            knownStamp = found.lastReadEpochMs
            loaded = true
            rebuild()
            if (screenUp && !listening) clock.start(System.currentTimeMillis())
            withContext(NonCancellable + Dispatchers.IO) { WearBookRepository.markOpened(bookId) }
            // The first page goes up before the contents are known: a book without a table of
            // its own is scanned for headings, which takes a moment on a long one.
            chapters = WearBookRepository.loadChapters(found, text)
            publish()
            WearBookRepository.observe(bookId).collect { row -> row?.let { follow(it.readOffsetChars, it.lastReadEpochMs) } }
        }
    }

    /** Moves to a place read on the other device, when it is newer than anything seen here. */
    private fun follow(offset: Int, at: Long) {
        if (at <= knownStamp) return
        knownStamp = at
        val p = paginator
        val current = page
        if (p == null || current == null) {
            // Not laid out yet: the first page will open here instead.
            restoreOffset = offset.coerceIn(0, text.length)
            return
        }
        val target = offset.coerceIn(0, p.length)
        // The voice reading aloud saves the sentence it is on, which is on this page already.
        if (target in current.start until current.end) return
        if (target >= p.length && current.end >= p.length) return
        page = if (target >= p.length && p.length > 0) p.pageEndingAt(p.length) else p.pageFrom(target)
        credit(clock.turn(System.currentTimeMillis()), 0)
        lastMove = null
        flipsSinceSync = 0
        publish()
    }

    /** Called by the screen whenever the font, the screen shape or the measurer changes. */
    fun attachLayout(geometry: PageGeometry, measurer: LineMeasurer) {
        layout = geometry to measurer
        if (loaded) rebuild()
    }

    private fun rebuild() {
        val (geometry, measurer) = layout ?: return
        val p = Paginator(text, geometry, measurer)
        paginator = p
        val start = page?.start ?: restoreOffset
        page = if (start >= text.length && text.isNotEmpty()) p.pageEndingAt(text.length) else p.pageFrom(start)
        publish()
    }

    fun nextPage(): Boolean {
        val p = paginator ?: return false
        val current = page ?: return false
        if (current.end >= p.length) return false
        val wasReading = clock.running
        page = p.pageFrom(current.end)
        credit(clock.turn(System.currentTimeMillis()), if (wasReading) current.end - current.start else 0)
        if (page?.end?.let { it >= p.length } == true) {
            viewModelScope.launch { withContext(NonCancellable + Dispatchers.IO) { WearBookRepository.markFinished(bookId) } }
        }
        publish()
        flipped()
        return true
    }

    fun prevPage(): Boolean {
        val p = paginator ?: return false
        val current = page ?: return false
        if (current.start <= 0) return false
        page = p.pageEndingAt(current.start)
        credit(clock.turn(System.currentTimeMillis()), 0)
        publish()
        flipped()
        return true
    }

    fun jumpToFraction(fraction: Float) {
        val p = paginator ?: return
        if (!loaded) return
        val offset = (fraction.coerceIn(0f, 1f) * p.length).toInt()
        val current = page
        var target = if (fraction >= 1f) p.pageEndingAt(p.length) else p.pageFrom(p.paragraphStart(offset))
        // Snapping to the start of a paragraph lands on the page already open when the paragraph
        // is longer than one notch of the slider, as it is in a short book. A notch must always
        // move the reader: forward to the next paragraph, back to the page before.
        if (current != null && fraction < 1f && target.start == current.start) {
            target = when {
                offset > current.start -> p.pageFrom(p.nextParagraphStart(offset))
                current.start > 0 -> p.pageEndingAt(current.start)
                else -> target
            }
        }
        if (target.start == current?.start) return
        current?.let { jumpHistory.record(it.start) }
        page = target
        credit(clock.turn(System.currentTimeMillis()), 0)
        publish()
        saveProgress(toPhone = false)
        scheduleSync()
    }

    fun jumpToChapter(chapter: Chapter) {
        val p = paginator ?: return
        if (chapter !in chapters) return
        page?.let { jumpHistory.record(it.start) }
        page = p.pageFrom(chapter.start.coerceIn(0, p.length))
        credit(clock.turn(System.currentTimeMillis()), 0)
        publish()
        saveProgress(toPhone = false)
        scheduleSync()
    }

    fun undoJump() {
        val p = paginator ?: return
        val offset = jumpHistory.take() ?: return
        page = p.pageFrom(offset.coerceIn(0, p.length))
        credit(clock.turn(System.currentTimeMillis()), 0)
        publish()
        saveProgress(toPhone = false)
        scheduleSync()
    }

    /** Keeps the page under the sentence being read aloud. */
    fun followSpoken(offset: Int) {
        val p = paginator ?: return
        val current = page ?: return
        if (offset in current.start until current.end) return
        page = if (offset >= current.end) {
            val next = p.pageFrom(current.end)
            if (offset in next.start until next.end) next else p.pageFrom(offset)
        } else {
            p.pageFrom(offset)
        }
        jumpHistory.turned()
        publish()
    }

    private fun publish() {
        val b = book ?: return
        val current = page ?: return
        _state.value = ReaderUiState.Ready(title = b.title, page = current, totalChars = text.length, chapters = chapters, canUndoJump = jumpHistory.returnOffset != null)
    }

    private fun flipped() {
        jumpHistory.turned()
        flipsSinceSync++
        val toPhone = flipsSinceSync >= SYNC_EVERY_FLIPS
        if (toPhone) flipsSinceSync = 0
        saveProgress(toPhone)
    }

    /** Where the open page is kept: its start, or the end of the book when it is the last one. */
    private fun savedOffset(): Int? = page?.let { if (it.end >= text.length) text.length else it.start }

    fun saveProgress(toPhone: Boolean) {
        val b = book ?: return
        val offset = savedOffset() ?: return
        val at = System.currentTimeMillis()
        lastMove = offset to at
        knownStamp = maxOf(knownStamp, at)
        if (toPhone) {
            flipsSinceSync = 0
            syncJob?.cancel()
        }
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                WearBookRepository.updateProgress(b.id, offset, at)
                if (toPhone) WearBookRepository.sendProgressToPhone(b, offset, at)
            }
        }
    }

    /** The slider speaks once per notch; the phone is told once the hand has come to rest. */
    private fun scheduleSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            delay(SYNC_DEBOUNCE_MS)
            val b = book ?: return@launch
            val (offset, at) = lastMove ?: return@launch
            flipsSinceSync = 0
            withContext(NonCancellable + Dispatchers.IO) { WearBookRepository.sendProgressToPhone(b, offset, at) }
        }
    }

    /** The page is on screen again; reading time runs from now unless the voice has the book. */
    fun onScreenResumed() {
        screenUp = true
        if (loaded && !listening) clock.start(System.currentTimeMillis())
    }

    /**
     * The screen went to the background (the screen went off, another app came up). The page open
     * until now is counted, and the phone is told about the pages turned since it last heard,
     * rather than only when this screen closes, which may be never: a process in the background
     * can be ended without warning. The reading time goes to the phone either way.
     */
    fun onScreenPaused() {
        screenUp = false
        val earned = clock.stop(System.currentTimeMillis())
        val b = book ?: return
        val move = lastMove.takeIf { flipsSinceSync > 0 }
        if (move != null) {
            flipsSinceSync = 0
            syncJob?.cancel()
        }
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                if (earned > 0) WearBookRepository.recordTime(b.id, ReadingSource.WATCH, earned, 0)
                if (move != null) WearBookRepository.sendProgressToPhone(b, move.first, move.second)
                else WearBookRepository.sendStatsToPhone(b.id)
            }
        }
    }

    /** The voice is reading this book aloud: that time is listening, counted by the service. */
    fun setListening(on: Boolean) {
        if (on == listening) return
        listening = on
        val now = System.currentTimeMillis()
        if (on) credit(clock.stop(now), 0) else if (screenUp && loaded) clock.start(now)
    }

    private fun credit(millis: Long, chars: Int) {
        if (millis <= 0 && chars <= 0) return
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) { WearBookRepository.recordTime(bookId, ReadingSource.WATCH, millis, chars) }
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onCleared() {
        // viewModelScope is gone by now; hand the last save to a scope that outlives us. Only a
        // page the reader actually turned to is saved, stamped with the time of that turn, so a
        // reading the phone pushed while this screen was open is never overwritten by a page
        // that was merely left open.
        val b = book
        val move = lastMove
        val earned = clock.stop(System.currentTimeMillis())
        if (b != null && earned > 0) GlobalScope.launch(Dispatchers.IO) { WearBookRepository.recordTime(b.id, ReadingSource.WATCH, earned, 0) }
        if (b != null && move != null) {
            val (offset, at) = move
            GlobalScope.launch(Dispatchers.IO) {
                WearBookRepository.updateProgress(b.id, offset, at)
                WearBookRepository.sendProgressToPhone(b, offset, at)
            }
        }
        super.onCleared()
    }

    private companion object {
        const val SYNC_EVERY_FLIPS = 8
        const val SYNC_DEBOUNCE_MS = 1500L
    }

    class Factory(private val application: Application, private val bookId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(application, bookId) as T
    }
}
