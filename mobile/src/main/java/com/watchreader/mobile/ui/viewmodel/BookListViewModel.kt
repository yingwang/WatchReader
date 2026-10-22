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
import com.watchreader.mobile.service.BookTransferWorker
import com.watchreader.mobile.service.ReadAloudService
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

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            BookTransferWorker.cancel(getApplication(), book.id)
            ReadAloudService.stopIfPlaying(getApplication(), book.id)
            BookRepository.delete(book.id)
            // Whatever the phone believes about the transfer, the watch may hold a copy: a receipt
            // can go missing after a send that worked. Telling a watch that has none is harmless.
            // A watch in reach hears at once; the note left in the data layer reaches one that is
            // not when it comes back, so the book goes from both, as the dialog promised.
            (sender.findWatch() as? WatchLookup.Ready)?.let { sender.deleteBookOnWatch(book.id, it.nodeId) }
            runCatching { PendingDeletes.publish(getApplication(), book.id) }
        }
    }

    /** Quiet automatic sends still queue offline; explicit sends also offer watch-app installation. */
    fun sendToWatch(book: Book, quietly: Boolean = false) {
        viewModelScope.launch {
            try {
                BookTransferWorker.enqueue(getApplication(), book)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _events.emit(UiEvent.Message(R.string.msg_send_failed))
                return@launch
            }
            if (!quietly) {
                when (val watch = sender.findWatch()) {
                    is WatchLookup.WithoutApp -> _events.emit(UiEvent.OfferInstall(watch.nodeId, watch.name))
                    WatchLookup.None -> _events.emit(UiEvent.Message(R.string.transfer_waiting))
                    is WatchLookup.Ready -> Unit
                }
            }
        }
    }

    fun cancelQueuedSend(book: Book) {
        viewModelScope.launch {
            try {
                BookTransferWorker.cancelWaiting(getApplication(), book.id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _events.emit(UiEvent.Message(R.string.msg_send_failed)) }
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

}
