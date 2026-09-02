package com.watchreader.mobile.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.repository.BookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One paragraph of the book, and where it starts in the text the watch also has. */
class Paragraph(val start: Int, val text: String)

sealed class ReaderUiState {
    object Loading : ReaderUiState()
    object Missing : ReaderUiState()
    class Ready(val book: Book, val paragraphs: List<Paragraph>, val firstParagraph: Int) : ReaderUiState()
}

class ReaderViewModel(application: Application, private val bookId: String) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var book: Book? = null
    private var paragraphs: List<Paragraph> = emptyList()

    init {
        viewModelScope.launch {
            val loaded = BookRepository.getById(bookId)
            if (loaded == null) {
                _state.value = ReaderUiState.Missing
                return@launch
            }
            book = loaded
            val text = runCatching { BookRepository.loadText(loaded) }.getOrElse {
                _state.value = ReaderUiState.Missing
                return@launch
            }
            paragraphs = withContext(Dispatchers.Default) { split(text) }
            _state.value = ReaderUiState.Ready(loaded, paragraphs, paragraphAt(loaded.readOffsetChars))
        }
    }

    /** Called as the reader scrolls; the watch hears about it too. */
    fun saveProgress(firstVisibleParagraph: Int) {
        val current = book ?: return
        val offset = paragraphs.getOrNull(firstVisibleParagraph)?.start ?: return
        if (offset == current.readOffsetChars) return
        book = current.copy(readOffsetChars = offset)
        viewModelScope.launch { BookRepository.saveProgress(getApplication(), current, offset) }
    }

    private fun paragraphAt(offset: Int): Int {
        if (offset <= 0) return 0
        val index = paragraphs.indexOfLast { it.start <= offset }
        return if (index < 0) 0 else index
    }

    /** Blank lines separate paragraphs; a very long one is broken up so scrolling stays smooth. */
    private fun split(text: String): List<Paragraph> {
        val out = ArrayList<Paragraph>()
        var start = 0
        while (start < text.length) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = text.length
            var chunkStart = start
            while (end - chunkStart > MAX_PARAGRAPH_CHARS) {
                val cut = text.lastIndexOf(' ', chunkStart + MAX_PARAGRAPH_CHARS)
                    .takeIf { it > chunkStart } ?: (chunkStart + MAX_PARAGRAPH_CHARS)
                out.add(Paragraph(chunkStart, text.substring(chunkStart, cut).trim()))
                chunkStart = cut
            }
            val line = text.substring(chunkStart, end).trim()
            if (line.isNotEmpty()) out.add(Paragraph(chunkStart, line))
            start = end + 1
        }
        return out
    }

    class Factory(private val application: Application, private val bookId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ReaderViewModel(application, bookId) as T
    }

    private companion object {
        const val MAX_PARAGRAPH_CHARS = 2000
    }
}
