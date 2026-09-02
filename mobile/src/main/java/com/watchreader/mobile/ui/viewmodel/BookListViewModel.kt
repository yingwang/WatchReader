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
    val books: StateFlow<List<Book>> = BookRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val sender = BookSender(application)

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            if (book.syncStatus == SyncStatus.SENT || book.syncStatus == SyncStatus.SENDING) {
                (sender.findWatch() as? WatchLookup.Ready)?.let { sender.deleteBookOnWatch(book.id, it.nodeId) }
            }
            BookRepository.delete(book.id)
        }
    }

    fun sendToWatch(book: Book) {
        if (book.syncStatus == SyncStatus.SENDING) return
        viewModelScope.launch {
            val watch = when (val lookup = sender.findWatch()) {
                is WatchLookup.Ready -> lookup
                is WatchLookup.WithoutApp -> {
                    _events.tryEmit(UiEvent.OfferInstall(lookup.nodeId, lookup.name))
                    return@launch
                }
                WatchLookup.None -> {
                    _events.tryEmit(UiEvent.Message(R.string.msg_watch_not_connected))
                    return@launch
                }
            }
            BookRepository.updateSyncStatus(book.id, SyncStatus.SENDING)
            val streamed = sender.sendBook(book, watch.nodeId)
            if (!streamed) {
                BookRepository.updateSyncStatus(book.id, SyncStatus.FAILED)
                _events.tryEmit(UiEvent.Message(R.string.msg_send_failed))
                return@launch
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
