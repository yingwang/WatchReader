package com.watchreader.mobile.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.shared.BookToc
import com.watchreader.shared.Chapter
import com.watchreader.shared.reader.LineMeasurer
import com.watchreader.shared.reader.PageGeometry
import com.watchreader.shared.reader.Paginator
import com.watchreader.shared.stats.ReadingClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ReaderUiState {
    object Loading : ReaderUiState()
    object Missing : ReaderUiState()
    class Ready(
        val page: Paginator.Page,
        val totalChars: Int,
        val chapters: List<Chapter>,
    ) : ReaderUiState() {
        /**
         * How far through the book this page begins, and the whole book on its last page. The
         * library shows the saved place the same way (a last page is saved as the end of the
         * book), so the two always agree and a book read to its end reaches 100%.
         */
        val fraction: Float get() = when {
            totalChars == 0 -> 0f
            page.end >= totalChars -> 1f
            else -> page.start.toFloat() / totalChars
        }
    }
}

/**
 * Owns the book text and the page in front of the reader. Pages are laid out by a [Paginator]
 * against the geometry and measurer the screen supplies, so the same book re-flows when the
 * phone is rotated.
 */
class ReaderViewModel(application: Application, private val bookId: String) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var book: Book? = null
    private var text: String = ""
    private var chapters: List<Chapter> = emptyList()
    private var loaded = false
    private var layout: Pair<PageGeometry, LineMeasurer>? = null
    private var paginator: Paginator? = null
    private var page: Paginator.Page? = null
    private var restoreOffset = 0

    /** The page the reader last turned to and when; a page merely left open is not a reading. */
    private var lastMove: Pair<Int, Long>? = null
    private var flipsSinceSync = 0

    /**
     * The stamp of the latest reading this screen knows of, its own or one it has followed. A
     * row carrying a later one was read on the watch while this screen sat open or in the
     * background, and the page moves there: turning on from the page left open would stamp the
     * old place with the time of the turn and send it back over the newer reading.
     */
    private var knownStamp = 0L

    /** Reading time: runs while the page is on screen. */
    private val clock = ReadingClock()
    private var screenUp = false

    init {
        viewModelScope.launch {
            val found = BookRepository.getById(bookId)
            if (found == null) {
                _state.value = ReaderUiState.Missing
                return@launch
            }
            book = found
            text = runCatching { BookRepository.loadText(found) }.getOrElse {
                _state.value = ReaderUiState.Missing
                return@launch
            }
            restoreOffset = found.readOffsetChars.coerceIn(0, text.length)
            knownStamp = found.lastReadEpochMs
            loaded = true
            rebuild()
            if (screenUp) clock.start(System.currentTimeMillis())
            withContext(NonCancellable + Dispatchers.IO) { BookRepository.markOpened(bookId) }
            // A book imported before contents were kept is scanned for headings, all of it; the
            // first page goes up before that, and the scan runs off the main thread.
            chapters = withContext(Dispatchers.Default) { BookToc.resolve(found.tocJson, text) }
            publish()
            BookRepository.observe(bookId).collect { row -> row?.let { follow(it.readOffsetChars, it.lastReadEpochMs) } }
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
        if (target in current.start until current.end) return
        if (target >= p.length && current.end >= p.length) return
        page = if (target >= p.length && p.length > 0) p.pageEndingAt(p.length) else p.pageFrom(target)
        credit(clock.turn(System.currentTimeMillis()), 0)
        lastMove = null
        flipsSinceSync = 0
        publish()
    }

    fun attachLayout(geometry: PageGeometry, measurer: LineMeasurer) {
        layout = geometry to measurer
        if (loaded) rebuild()
    }

    private fun rebuild() {
        val (geometry, measurer) = layout ?: return
        val p = Paginator(text, geometry, measurer, paragraphGaps = true)
        paginator = p
        val start = page?.start ?: restoreOffset
        page = if (start >= text.length && text.isNotEmpty()) p.pageEndingAt(text.length) else p.pageFrom(start)
        publish()
    }

    fun nextPage() {
        val p = paginator ?: return
        val current = page ?: return
        if (current.end >= p.length) return
        val wasReading = clock.running
        page = p.pageFrom(current.end)
        credit(clock.turn(System.currentTimeMillis()), if (wasReading) current.end - current.start else 0)
        if (page?.end?.let { it >= p.length } == true) {
            viewModelScope.launch { withContext(NonCancellable + Dispatchers.IO) { BookRepository.markFinished(bookId) } }
        }
        publish()
        flipped()
    }

    fun prevPage() {
        val p = paginator ?: return
        val current = page ?: return
        if (current.start <= 0) return
        page = p.pageEndingAt(current.start)
        credit(clock.turn(System.currentTimeMillis()), 0)
        publish()
        flipped()
    }

    fun jumpTo(offset: Int) {
        val p = paginator ?: return
        page = p.pageFrom(offset.coerceIn(0, p.length))
        credit(clock.turn(System.currentTimeMillis()), 0)
        publish()
        // a jump is a long way to move; the watch hears about it at once
        saveProgress(toWatch = true)
    }

    private fun publish() {
        val current = page ?: return
        _state.value = ReaderUiState.Ready(current, text.length, chapters)
    }

    /** Every turn is saved; the watch is told about one turn in [SYNC_EVERY_FLIPS] and the last one on exit. */
    private fun flipped() {
        flipsSinceSync++
        saveProgress(toWatch = flipsSinceSync >= SYNC_EVERY_FLIPS)
    }

    /** Where the open page is kept: its start, or the end of the book when it is the last one. */
    private fun savedOffset(): Int? = page?.let { if (it.end >= text.length) text.length else it.start }

    fun saveProgress(toWatch: Boolean = true) {
        val b = book ?: return
        val offset = savedOffset() ?: return
        val at = System.currentTimeMillis()
        lastMove = offset to at
        knownStamp = maxOf(knownStamp, at)
        if (toWatch) flipsSinceSync = 0
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                BookRepository.saveProgress(getApplication(), b, offset, at, toWatch)
            }
        }
    }

    /** The page is on screen again; reading time runs from now. */
    fun onScreenResumed() {
        screenUp = true
        if (loaded) clock.start(System.currentTimeMillis())
    }

    /**
     * The screen went to the background (Home, the lock button, another app). The page open until
     * now is counted, and the watch is told about the pages turned since it last heard, rather than
     * only when this screen is closed with Back, which may be never: a process in the background
     * can be ended without warning.
     */
    fun onScreenPaused() {
        screenUp = false
        credit(clock.stop(System.currentTimeMillis()), 0)
        if (flipsSinceSync == 0) return
        val b = book ?: return
        val (offset, at) = lastMove ?: return
        flipsSinceSync = 0
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) { BookRepository.saveProgress(getApplication(), b, offset, at, toWatch = true) }
        }
    }

    private fun credit(millis: Long, chars: Int) {
        if (millis <= 0 && chars <= 0) return
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) { BookRepository.recordReading(bookId, millis, chars) }
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onCleared() {
        // viewModelScope is gone by now; hand the last save to a scope that outlives us. Only a
        // page the reader actually turned to is saved, stamped with the time of that turn, so the
        // watch's reading since then is never overwritten by a page that was merely left open.
        val b = book
        val move = lastMove
        val earned = clock.stop(System.currentTimeMillis())
        if (b != null && earned > 0) GlobalScope.launch(Dispatchers.IO) { BookRepository.recordReading(b.id, earned, 0) }
        if (b != null && move != null) {
            val (offset, at) = move
            GlobalScope.launch(Dispatchers.IO) { BookRepository.saveProgress(getApplication(), b, offset, at) }
        }
        super.onCleared()
    }

    private companion object {
        /** A phone page holds several watch pages, so the watch hears from the phone more often than the reverse. */
        const val SYNC_EVERY_FLIPS = 4
    }

    class Factory(private val application: Application, private val bookId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(application, bookId) as T
    }
}
