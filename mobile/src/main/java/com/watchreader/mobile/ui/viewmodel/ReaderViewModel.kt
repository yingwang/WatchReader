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
        val fraction: Float get() = if (totalChars == 0) 0f else page.end.toFloat() / totalChars
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
            chapters = BookToc.fromJson(found.tocJson)
            restoreOffset = found.readOffsetChars.coerceIn(0, text.length)
            loaded = true
            rebuild()
        }
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
        page = p.pageFrom(current.end)
        publish()
        saveProgress()
    }

    fun prevPage() {
        val p = paginator ?: return
        val current = page ?: return
        if (current.start <= 0) return
        page = p.pageEndingAt(current.start)
        publish()
        saveProgress()
    }

    fun jumpTo(offset: Int) {
        val p = paginator ?: return
        page = p.pageFrom(offset.coerceIn(0, p.length))
        publish()
        saveProgress()
    }

    private fun publish() {
        val current = page ?: return
        _state.value = ReaderUiState.Ready(current, text.length, chapters)
    }

    fun saveProgress() {
        val b = book ?: return
        val offset = page?.start ?: return
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                BookRepository.saveProgress(getApplication(), b, offset)
            }
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onCleared() {
        // viewModelScope is gone by now; hand the last save to a scope that outlives us.
        val b = book
        val offset = page?.start
        if (b != null && offset != null) {
            GlobalScope.launch(Dispatchers.IO) { BookRepository.saveProgress(getApplication(), b, offset) }
        }
        super.onCleared()
    }

    class Factory(private val application: Application, private val bookId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(application, bookId) as T
    }
}
