package com.watchreader.mobile.ui.viewmodel

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.mobile.service.BookSender
import com.watchreader.mobile.service.WatchLookup
import com.watchreader.shared.PendingDeletes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed class UiEvent {
    data class Message(@StringRes val text: Int, val arg: String? = null) : UiEvent()

    /** The watch is there but has no WatchReader; offer to open Play on it. */
    data class OfferInstall(val nodeId: String, val watchName: String) : UiEvent()
}

class BookListViewModel(application: Application) : AndroidViewModel(application) {
    /** Null until the database has answered once, so a library still loading is not taken for an empty one. */
    val books: StateFlow<List<Book>?> = BookRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

    fun sendToWatch(book: Book) {
        if (!BookRepository.beginSending(book.id)) return
        viewModelScope.launch {
            try {
                send(book)
            } finally {
                BookRepository.endSending(book.id)
            }
        }
    }

    private suspend fun send(book: Book) {
        val watch = when (val lookup = sender.findWatch()) {
            is WatchLookup.Ready -> lookup
            is WatchLookup.WithoutApp -> {
                _events.tryEmit(UiEvent.OfferInstall(lookup.nodeId, lookup.name))
                return
            }
            WatchLookup.None -> {
                _events.tryEmit(UiEvent.Message(R.string.msg_watch_not_connected))
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
