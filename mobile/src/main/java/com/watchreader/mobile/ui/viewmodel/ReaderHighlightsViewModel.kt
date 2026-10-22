package com.watchreader.mobile.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.watchreader.mobile.highlight.Highlight
import com.watchreader.mobile.highlight.HighlightColor
import com.watchreader.mobile.highlight.HighlightRepository
import com.watchreader.mobile.highlight.HighlightStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HighlightFailure { LOAD, SAVE }

data class HighlightsUiState(
    /** In book order. */
    val items: List<Highlight> = emptyList(),
    val loading: Boolean = true,
    /** The file is there but could not be read; it is kept, and no change is saved over it. */
    val loadFailed: Boolean = false,
)

/**
 * One book's highlights and notes for the phone's reader. It sits beside [ReaderViewModel] rather
 * than inside it: the reader owns the page and the reading place, this owns what the reader has
 * marked, and the two meet only in the screen.
 */
class ReaderHighlightsViewModel(application: Application, bookId: String) : AndroidViewModel(application) {
    private val store: HighlightStore = HighlightRepository.store(application.filesDir, bookId)
    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(HighlightsUiState())
    val state: StateFlow<HighlightsUiState> = _state.asStateFlow()

    private val _lastColor = MutableStateFlow(HighlightColor.fromKey(prefs.getString(KEY_COLOR, null)))
    /** The colour a plain tap on Highlight uses: the one picked last, in any book. */
    val lastColor: StateFlow<HighlightColor> = _lastColor.asStateFlow()

    private val _failures = MutableSharedFlow<HighlightFailure>(extraBufferCapacity = 4)
    /** A change that could not be saved; the screen says so, and nothing on the page has changed. */
    val failures: SharedFlow<HighlightFailure> = _failures.asSharedFlow()

    private val _deleted = MutableSharedFlow<Highlight>(extraBufferCapacity = 4)
    /** A highlight just deleted, for the screen to offer it back. */
    val deleted: SharedFlow<Highlight> = _deleted.asSharedFlow()

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.load() }
                _state.update { it.copy(loading = false, loadFailed = false) }
                store.items.collect { items -> _state.update { it.copy(items = items) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w("WatchReader", "Could not read highlights", e)
                _state.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    /** Marks [start, end) of the book, holding [quote], in [color] (the last colour when not given). */
    fun add(start: Int, end: Int, quote: String, color: HighlightColor = lastColor.value, note: String = "") {
        remember(color)
        change { store.add(start, end, quote, color, note) }
    }

    fun recolor(id: String, color: HighlightColor) {
        remember(color)
        change { store.recolor(id, color) }
    }

    fun annotate(id: String, note: String) = change { store.annotate(id, note) }

    /** Deletes a highlight; once that is saved, it is offered back through [deleted]. */
    fun delete(id: String) = change {
        store.delete(id)?.let { _deleted.tryEmit(it) }
    }

    fun restore(highlight: Highlight) = change { store.restore(highlight) }

    private fun remember(color: HighlightColor) {
        _lastColor.value = color
        prefs.edit().putString(KEY_COLOR, color.key).apply()
    }

    private fun change(block: suspend () -> Unit) {
        // An unreadable file is never written over: whatever it still holds is worth more than
        // the highlight just made.
        if (state.value.loadFailed) {
            _failures.tryEmit(HighlightFailure.LOAD)
            return
        }
        viewModelScope.launch {
            try {
                // Leaving the reader the moment after a tap must not lose the change half-written.
                withContext(NonCancellable + Dispatchers.IO) { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w("WatchReader", "Could not save a highlight", e)
                _failures.tryEmit(HighlightFailure.SAVE)
            }
        }
    }

    private companion object {
        const val PREFS = "reader_highlights"
        const val KEY_COLOR = "last_color"
    }

    class Factory(private val application: Application, private val bookId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderHighlightsViewModel(application, bookId) as T
    }
}
