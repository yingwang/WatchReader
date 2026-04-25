package com.watchreader.mobile.ui.viewmodel

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.mobile.service.BookSender
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BookListViewModel(application: Application) : AndroidViewModel(application) {
    val books: StateFlow<List<Book>> = BookRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val sender = BookSender(application)

    fun deleteBook(id: String) {
        viewModelScope.launch {
            BookRepository.delete(id)
        }
    }

    fun sendToWatch(book: Book) {
        viewModelScope.launch {
            try {
                Log.d("WatchReader", "Finding watch node...")
                val nodeId = sender.findWatchNodeId()
                Log.d("WatchReader", "Watch node: $nodeId")
                if (nodeId == null) {
                    Toast.makeText(getApplication(), "Watch not connected", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                BookRepository.updateSyncStatus(book.id, SyncStatus.SENDING)
                Log.d("WatchReader", "Sending book: ${book.title} to $nodeId")
                val success = sender.sendBook(book, nodeId)
                Log.d("WatchReader", "Send result: $success")
                if (success) {
                    BookRepository.updateSyncStatus(book.id, SyncStatus.SENT)
                } else {
                    BookRepository.updateSyncStatus(book.id, SyncStatus.FAILED)
                    Toast.makeText(getApplication(), "Send failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("WatchReader", "Send error", e)
                BookRepository.updateSyncStatus(book.id, SyncStatus.FAILED)
                Toast.makeText(getApplication(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
