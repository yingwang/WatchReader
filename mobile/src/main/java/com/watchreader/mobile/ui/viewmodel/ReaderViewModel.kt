package com.watchreader.mobile.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.mobile.tts.ReadAloudPlayback
import com.watchreader.shared.BookToc
import com.watchreader.shared.Chapter
import com.watchreader.shared.SaveStamps
import com.watchreader.shared.bookmark.BookmarkRepository
import com.watchreader.shared.bookmark.ReaderBookmarks
import com.watchreader.shared.search.ReaderSearch
import com.watchreader.shared.search.SearchHit
import com.watchreader.shared.reader.LineMeasurer
import com.watchreader.shared.reader.PageGeometry
import com.watchreader.shared.reader.Paginator
import com.watchreader.shared.stats.ReadingClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
/** How the reader moved through the book: a page turned by hand, or a jump. */
enum class ReaderMove {
    /** The next or the previous page, by a tap, a swipe or the page turning by itself. */
    TURN,

    /** The contents, the slider, a bookmark, a search result, a highlight, the way back, the watch's place. */
    JUMP;

    /**
     * Whether the voice reading this book aloud goes on from the new place. A jump takes it
     * along. A page turned by hand only shows that page, and the next sentence brings the reader
     * back to the voice, as on the watch; while the voice is paused, a turned page does not move
     * it either, and playing goes on from where it stopped.
     */
    val movesVoice: Boolean get() = this == JUMP
}

class ReaderViewModel(application: Application, private val bookId: String) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val bookmarkReader = ReaderBookmarks(BookmarkRepository.get(application), bookId, viewModelScope)
    val bookmarks = bookmarkReader.state
    private val searcher = ReaderSearch(viewModelScope)
    val search = searcher.state

    private var book: Book? = null
    private var text: String = ""
    // Keep a search/bookmark anchor through background saves until the reader moves again.
    private var positionAnchor: Int? = null
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
    private val stamps = SaveStamps()

    private val returnPoint = ReturnPoint()
    private val _returnTo = MutableStateFlow<Int?>(null)
    /** Where the reader was before the last look-up, while there is a way back to it. */
    val returnTo: StateFlow<Int?> = _returnTo.asStateFlow()

    private val _watchPlace = MutableStateFlow<Int?>(null)
    /**
     * A place read on the watch, newer than anything seen here, while this screen was open. It is
     * offered rather than followed: being moved without warning in the middle of a page is worse
     * than being asked.
     */
    val watchPlace: StateFlow<Int?> = _watchPlace.asStateFlow()

    /** Reading time: runs while the page is on screen. */
    private val clock = ReadingClock()
    private var screenUp = false

    /**
     * While words are selected the page stays where it is: a turn would leave the selection, and
     * the toolbar over it, on a page no longer shown.
     */
    var pageTurnsHeld = false

    /** The book is being read aloud (see [setReadAloud]); [listening] while the voice plays. */
    private var readAloud = false
    private var listening = false
    private val _moved = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    /** Each place the reader jumped to, for the voice to go on from while it reads this book (see [ReaderMove]). */
    val moved: SharedFlow<Int> = _moved.asSharedFlow()

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
            // A book being read aloud opens at the sentence the voice is on, as it does from the
            // listening bar; the place the voice last saved can be a few sentences behind.
            ReadAloudPlayback.sentence.value?.takeIf { ReadAloudPlayback.bookId.value == bookId }
                ?.let { restoreOffset = it.first.coerceIn(0, text.length) }
            positionAnchor = restoreOffset
            knownStamp = found.lastReadEpochMs
            loaded = true
            rebuild()
            if (screenUp && !listening) clock.start(System.currentTimeMillis())
            withContext(NonCancellable + Dispatchers.IO) { BookRepository.markOpened(bookId) }
            // A book imported before contents were kept is scanned for headings, all of it; the
            // first page goes up before that, and the scan runs off the main thread.
            chapters = withContext(Dispatchers.Default) { BookToc.resolve(found.tocJson, text) }
            publish()
            BookRepository.observe(bookId).collect { row -> row?.let { follow(it.readOffsetChars, it.lastReadEpochMs) } }
        }
    }

    /**
     * A place read on the other device, newer than anything seen here. Before the first page is up
     * the book simply opens there; once the reader is on a page, the place is offered instead.
     */
    private fun follow(offset: Int, at: Long) {
        if (at <= knownStamp) return
        knownStamp = at
        // The voice saves the places it reads, and the page follows the voice already.
        if (readAloud) return
        val p = paginator
        val current = page
        if (p == null || current == null) {
            // Not laid out yet: the first page will open here instead.
            positionAnchor = offset.coerceIn(0, text.length)
            restoreOffset = offset.coerceIn(0, text.length)
            return
        }
        val target = offset.coerceIn(0, p.length)
        val onThisPage = target in current.start until current.end || (target >= p.length && current.end >= p.length)
        _watchPlace.value = if (onThisPage) null else target
    }

    /** Goes to the place the watch reached, with a way back to this one. */
    fun goToWatchPlace() {
        val target = _watchPlace.value ?: return
        _watchPlace.value = null
        savedOffset()?.let { returnPoint.jumpedFrom(it) }
        moveTo(target)
    }

    /**
     * Stays on this page and makes it the newest place, so the watch follows the phone rather
     * than the other way round.
     */
    fun stayHere() {
        _watchPlace.value = null
        saveProgress(toWatch = true)
    }

    /** Closes the question without choosing; whichever device is read next moves the place on. */
    fun dismissWatchPlace() {
        _watchPlace.value = null
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
        if (pageTurnsHeld) return
        val p = paginator ?: return
        val current = page ?: return
        if (current.end >= p.length) return
        val wasReading = clock.running
        page = p.pageFrom(current.end)
        credit(clock.turn(System.currentTimeMillis()), if (wasReading) current.end - current.start else 0)
        if (page?.end?.let { it >= p.length } == true) {
            viewModelScope.launch { withContext(NonCancellable + Dispatchers.IO) { BookRepository.markFinished(bookId) } }
        }
        reachedReturnPoint()
        publish()
        flipped()
    }

    fun prevPage() {
        if (pageTurnsHeld) return
        val p = paginator ?: return
        val current = page ?: return
        if (current.start <= 0) return
        page = p.pageEndingAt(current.start)
        credit(clock.turn(System.currentTimeMillis()), 0)
        reachedReturnPoint()
        publish()
        flipped()
    }

    /** A jump from the contents, the slider, a search result or a bookmark, which can be undone with [goBack]. */
    fun jumpTo(offset: Int) {
        val p = paginator ?: return
        val current = page ?: return
        val target = offset.coerceIn(0, p.length)
        val onThisPage = target in current.start until current.end || (target >= p.length && current.end >= p.length)
        if (!onThisPage) savedOffset()?.let { returnPoint.jumpedFrom(it) }
        moveTo(target)
    }

    /** A jump from the contents' slider, to the start of the paragraph [fraction] of the way in, as on the watch. */
    fun jumpToFraction(fraction: Float) {
        val p = paginator ?: return
        jumpTo(p.offsetAtFraction(fraction))
    }

    /** Back to where the reading was before the look-up. */
    fun goBack() {
        val back = returnPoint.take() ?: return
        moveTo(back)
    }

    /** Lets the way back go, for a reader who has decided to read on from here. */
    fun forgetReturnPoint() {
        returnPoint.clear()
        _returnTo.value = null
    }

    private fun reachedReturnPoint() {
        val current = page ?: return
        returnPoint.reached(current.start, current.end, text.length)
        _returnTo.value = returnPoint.offset
    }

    private fun moveTo(offset: Int) {
        val p = paginator ?: return
        val target = offset.coerceIn(0, p.length)
        page = if (target == p.length && p.length > 0) p.pageEndingAt(p.length) else p.pageFrom(target)
        credit(clock.turn(System.currentTimeMillis()), 0)
        publish()
        // a jump is a long way to move; the watch hears about it at once
        // Preserve the chosen anchor even on the phone's last page: the watch has smaller pages.
        saveProgress(toWatch = true, anchor = target)
        _returnTo.value = returnPoint.offset
        moved(ReaderMove.JUMP, target)
    }

    private fun moved(how: ReaderMove, offset: Int) {
        if (how.movesVoice) _moved.tryEmit(offset)
    }

    fun toggleBookmark() {
        val current = page ?: return
        val existing = bookmarks.value.onPage(current.start, current.end)
        if (existing != null) bookmarkReader.remove(existing.offset)
        else if (current.start < text.length) bookmarkReader.add(current.start, text)
    }

    fun removeBookmark(offset: Int) = bookmarkReader.remove(offset)
    fun retryBookmarks() = bookmarkReader.refresh()
    fun clearBookmarkError() = bookmarkReader.clearError()

    fun jumpToBookmark(offset: Int) {
        if (bookmarks.value.items.any { it.offset == offset } && offset in text.indices) jumpTo(offset)
    }

    fun searchBody(query: String) { if (loaded) searcher.search(text, query) }
    fun clearSearch() = searcher.clear()
    fun jumpToSearchResult(generation: Long, hit: SearchHit) {
        searcher.selectedOffset(generation, hit)?.let { jumpTo(it) }
    }

    private fun publish() {
        val current = page ?: return
        _state.value = ReaderUiState.Ready(current, text.length, chapters)
    }

    /** Every turn is saved; the watch is told about one turn in [SYNC_EVERY_FLIPS] and the last one on exit. */
    private fun flipped() {
        positionAnchor = null
        flipsSinceSync++
        saveProgress(toWatch = flipsSinceSync >= SYNC_EVERY_FLIPS)
        page?.let { moved(ReaderMove.TURN, it.start) }
    }

    /**
     * Turns to the sentence the voice is reading, which begins at [offset], without saving it: the
     * voice saves its own place. The next page is taken when the sentence runs onto it, so the
     * pages turn as they would by hand rather than starting wherever a sentence does.
     */
    fun followSpoken(offset: Int) {
        positionAnchor = offset.coerceIn(0, text.length)
        val p = paginator
        val current = page
        if (p == null || current == null) {
            // Not laid out yet: the first page opens at the sentence instead.
            restoreOffset = offset.coerceIn(0, text.length)
            return
        }
        if (offset in current.start until current.end) return
        page = if (offset >= current.end) {
            val next = p.pageFrom(current.end)
            if (offset in next.start until next.end) next else p.pageFrom(offset)
        } else {
            p.pageFrom(offset)
        }
        reachedReturnPoint()
        publish()
    }

    /**
     * This book is being read aloud ([active]), and the voice is playing. Listening is counted by
     * the voice, so reading time stops while it plays; the places it saves are not offered back
     * as the watch's, and the pages turned before it began are not sent over its newer ones.
     */
    fun setReadAloud(active: Boolean, playing: Boolean) {
        if (active && !readAloud) {
            flipsSinceSync = 0
            lastMove = null
        }
        readAloud = active
        if (playing == listening) return
        listening = playing
        val now = System.currentTimeMillis()
        if (playing) credit(clock.stop(now), 0) else if (screenUp && loaded) clock.start(now)
    }

    /** Where the open page is kept: its start, or the end of the book when it is the last one. */
    private fun savedOffset(): Int? = page?.let { current ->
        positionAnchor?.takeIf { it in current.start until current.end }
            ?: if (current.end >= text.length) text.length else current.start
    }

    fun saveProgress(toWatch: Boolean = true, anchor: Int? = null) {
        if (anchor != null) positionAnchor = anchor.coerceIn(0, text.length)
        val b = book ?: return
        val offset = anchor?.coerceIn(0, text.length) ?: savedOffset() ?: return
        // Each save is later than the one before, so the last of several in a millisecond wins.
        val at = stamps.next()
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
        bookmarkReader.refresh()
        if (loaded && !listening) clock.start(System.currentTimeMillis())
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
