package com.watchreader.mobile.ui.viewmodel

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.FreeBook
import com.watchreader.mobile.data.model.ReadingMilestone
import com.watchreader.mobile.data.model.ReadingTime
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.mobile.data.repository.FreeBookRepository
import com.watchreader.mobile.data.repository.GutenbergCatalog
import com.watchreader.mobile.service.BookSender
import com.watchreader.mobile.service.WatchLookup
import com.watchreader.shared.PendingDeletes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

sealed class UiEvent {
    data class Message(@StringRes val text: Int, val arg: String? = null) : UiEvent()

    /** The watch is there but has no WatchReader; offer to open Play on it. */
    data class OfferInstall(val nodeId: String, val watchName: String) : UiEvent()
}

class BookListViewModel(application: Application) : AndroidViewModel(application) {
    /** Null until the database has answered once, so a library still loading is not taken for an empty one. */
    val books: StateFlow<List<Book>?> = BookRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Every day's reading time of every book, for the library's line and the reading time page. */
    val readingTime: StateFlow<List<ReadingTime>> = BookRepository.observeReadingTime()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun readingTimeFor(bookId: String): Flow<List<ReadingTime>> = BookRepository.observeReadingTime(bookId)

    fun milestoneFor(bookId: String): Flow<ReadingMilestone?> = BookRepository.observeMilestone(bookId)

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val sender = BookSender(application)

    init {
        // A send waits for its receipt in this scope, so a book still SENDING when a new screen
        // comes up has lost its waiter (the screen was left, or the process died); free it.
        viewModelScope.launch { BookRepository.recoverStaleTransfers() }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            BookRepository.delete(book.id)
            // Whatever the phone believes about the transfer, the watch may hold a copy: a receipt
            // can go missing after a send that worked. Telling a watch that has none is harmless.
            // A watch in reach hears at once; the note left in the data layer reaches one that is
            // not when it comes back, so the book goes from both, as the dialog promised.
            (sender.findWatch() as? WatchLookup.Ready)?.let { sender.deleteBookOnWatch(book.id, it.nodeId) }
            runCatching { PendingDeletes.publish(getApplication(), book.id) }
        }
    }

    /**
     * [quietly] is for a send the reader did not ask for, a free classic's straight after it is
     * added: with no watch in reach, or one without WatchReader, the book is left Not on watch,
     * as any other book is, and nothing is said about it. The reader's own Send still offers to
     * install the app on such a watch.
     */
    fun sendToWatch(book: Book, quietly: Boolean = false) {
        if (!BookRepository.beginSending(book.id)) return
        viewModelScope.launch {
            try {
                send(book, quietly)
            } finally {
                BookRepository.endSending(book.id)
            }
        }
    }

    private suspend fun send(book: Book, quietly: Boolean) {
        val watch = when (val lookup = sender.findWatch()) {
            is WatchLookup.Ready -> lookup
            is WatchLookup.WithoutApp -> {
                if (!quietly) _events.tryEmit(UiEvent.OfferInstall(lookup.nodeId, lookup.name))
                return
            }
            WatchLookup.None -> {
                if (!quietly) _events.tryEmit(UiEvent.Message(R.string.msg_watch_not_connected))
                return
            }
        }
        BookRepository.updateSyncStatus(book.id, SyncStatus.SENDING)
        val streamed = sender.sendBook(book, watch.nodeId)
        if (!streamed) {
            BookRepository.updateSyncStatus(book.id, SyncStatus.FAILED)
            _events.tryEmit(UiEvent.Message(R.string.msg_send_failed))
            return
        }
        // The watch answers with a receipt once the book is on its books; give it a while.
        val settled = withTimeoutOrNull(ACK_TIMEOUT_MS) {
            BookRepository.observeAll().first { list ->
                list.firstOrNull { it.id == book.id }?.syncStatus != SyncStatus.SENDING
            }
        }
        if (settled == null) {
            BookRepository.updateSyncStatus(book.id, SyncStatus.FAILED)
            _events.tryEmit(UiEvent.Message(R.string.msg_no_receipt))
        }
    }

    /** The free classics offered while the library holds none of the reader's own books. */
    sealed interface Starters {
        data object Loading : Starters
        data class Ready(val picks: List<FreeBook>) : Starters
        /** The catalogue could not be reached, or had nothing to offer; the card shrinks to a line. */
        data object Unavailable : Starters
    }

    /** A classic on its way into the library, or why the last one did not get there. */
    sealed interface Pick {
        data class Adding(val bookId: Int) : Pick
        data class Failed(val bookId: Int, val message: String) : Pick
    }

    private val _starters = MutableStateFlow<Starters>(Starters.Loading)
    val starters: StateFlow<Starters> = _starters.asStateFlow()

    private val _pick = MutableStateFlow<Pick?>(null)
    val pick: StateFlow<Pick?> = _pick.asStateFlow()

    private var startersJob: Job? = null
    private var pickJob: Job? = null

    /**
     * Asks for the free classics once the library is seen to need them, which only a library
     * without the reader's own books does; nobody else's opening of the app goes to Gutenberg.
     * Picks in hand, or on their way, are not asked for twice, while a failed attempt is made
     * again, so coming back to the library once online brings the card back.
     */
    fun loadStarters() {
        if (_starters.value is Starters.Ready || startersJob?.isActive == true) return
        startersJob = viewModelScope.launch {
            _starters.value = Starters.Loading
            _starters.value = try {
                val picks = FreeBookRepository.starterPicks(GutenbergCatalog.languageFor(Locale.getDefault()))
                if (picks.isEmpty()) Starters.Unavailable else Starters.Ready(picks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline, Gutenberg busy, or an answer that was no feed: the library is no place
                // to tell them apart, and the card says only that the classics could not be had.
                Starters.Unavailable
            }
        }
    }

    /**
     * Adds a free classic exactly as the free library's Add does, after the same check of its
     * rights and trying its files in the same order, then sends it to the watch the way the
     * library's own Send does. One classic is fetched at a time. The library's job outlives the
     * screen, so a reader who opens a book meanwhile still finds the classic there afterwards.
     */
    fun addStarter(book: FreeBook) {
        if (pickJob?.isActive == true) return
        _pick.value = Pick.Adding(book.id)
        pickJob = viewModelScope.launch {
            _pick.value = try {
                val details = FreeBookRepository.details(book)
                val refusal = refusalOf(details)
                if (refusal != null) {
                    Pick.Failed(book.id, getApplication<Application>().getString(refusal))
                } else {
                    sendToWatch(FreeBookRepository.add(book, details), quietly = true)
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Pick.Failed(book.id, describeAddFailure(getApplication(), e))
            }
        }
    }

    fun openPlayOnWatch(nodeId: String) {
        viewModelScope.launch {
            if (!sender.openPlayStoreOnWatch(nodeId)) {
                _events.tryEmit(UiEvent.Message(R.string.msg_open_play_failed))
            }
        }
    }

    private companion object {
        const val ACK_TIMEOUT_MS = 90_000L
    }
}
