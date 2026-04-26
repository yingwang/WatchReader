package com.watchreader.wear.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchreader.wear.data.model.WearBook
import com.watchreader.wear.data.repository.WearBookRepository
import com.watchreader.wear.tts.SentenceParser
import com.watchreader.wear.tts.TtsManager
import com.watchreader.wear.tts.TtsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReaderViewModel(
    application: Application,
    private val bookId: String,
) : AndroidViewModel(application) {

    val ttsManager = TtsManager(application)

    private val _book = MutableStateFlow<WearBook?>(null)
    val book: StateFlow<WearBook?> = _book.asStateFlow()

    private val _fullText = MutableStateFlow("")
    val fullText: StateFlow<String> = _fullText.asStateFlow()

    private val _pages = MutableStateFlow<List<String>>(emptyList())
    val pages: StateFlow<List<String>> = _pages.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _charsPerPage = MutableStateFlow(60)

    val ttsState: StateFlow<TtsState> = ttsManager.state
    val currentSentenceIndex: StateFlow<Int> = ttsManager.currentSentenceIndex

    init {
        viewModelScope.launch {
            val wearBook = WearBookRepository.getById(bookId) ?: return@launch
            _book.value = wearBook
            val text = WearBookRepository.loadText(wearBook)
            _fullText.value = text
            paginate(text)

            // Restore reading position
            if (wearBook.readOffsetChars > 0) {
                val pageIndex = findPageForOffset(wearBook.readOffsetChars)
                _currentPage.value = pageIndex
            }
        }

        // Auto-advance page when TTS finishes a page
        viewModelScope.launch {
            ttsManager.onPageDone.collect {
                if (_currentPage.value < _pages.value.size - 1) {
                    nextPage()
                    startTtsForCurrentPage()
                } else {
                    ttsManager.stop()
                }
            }
        }
    }

    private fun paginate(text: String) {
        val cpp = _charsPerPage.value
        val pageList = mutableListOf<String>()
        var pos = 0
        while (pos < text.length) {
            // Skip leading whitespace/newlines between pages
            while (pos < text.length && text[pos].isWhitespace()) pos++
            if (pos >= text.length) break
            val end = findPageBreak(text, pos, cpp)
            val page = text.substring(pos, end).trimEnd()
            if (page.isNotEmpty()) {
                pageList.add(page)
            }
            pos = end
        }
        _pages.value = pageList
    }

    private fun findPageBreak(text: String, start: Int, maxChars: Int): Int {
        val end = (start + maxChars).coerceAtMost(text.length)
        if (end >= text.length) return text.length

        // Try to break at paragraph (only search between start and end)
        for (i in end - 1 downTo start + maxChars / 2) {
            if (i + 1 < text.length && text[i] == '\n' && text[i + 1] == '\n') {
                return i + 2
            }
        }

        // Try to break at sentence
        val sentenceEnds = listOf('。', '！', '？', '.', '!', '?', '\n')
        for (i in end - 1 downTo start + maxChars / 2) {
            if (text[i] in sentenceEnds) return i + 1
        }

        // Try to break at comma or space
        val softBreaks = listOf('，', '、', ',', ' ', '；', ';')
        for (i in end - 1 downTo start + maxChars / 2) {
            if (text[i] in softBreaks) return i + 1
        }

        // Hard break
        return end
    }

    private fun findPageForOffset(charOffset: Int): Int {
        var accumulated = 0
        for ((i, page) in _pages.value.withIndex()) {
            accumulated += page.length
            if (accumulated >= charOffset) return i
        }
        return 0
    }

    private fun currentCharOffset(): Int {
        var offset = 0
        for (i in 0 until _currentPage.value.coerceAtMost(_pages.value.size)) {
            offset += _pages.value[i].length
        }
        return offset
    }

    fun nextPage() {
        val next = (_currentPage.value + 1).coerceAtMost(_pages.value.size - 1)
        if (next != _currentPage.value) {
            _currentPage.value = next
            saveProgress()
        }
    }

    fun prevPage() {
        val prev = (_currentPage.value - 1).coerceAtLeast(0)
        if (prev != _currentPage.value) {
            _currentPage.value = prev
            saveProgress()
        }
    }

    fun toggleTts() {
        when (ttsManager.state.value) {
            TtsState.IDLE -> startTtsForCurrentPage()
            TtsState.PLAYING -> ttsManager.togglePlayPause()
            TtsState.PAUSED -> ttsManager.togglePlayPause()
            TtsState.LOADING -> {}
        }
    }

    private fun startTtsForCurrentPage() {
        val pageText = _pages.value.getOrNull(_currentPage.value) ?: return
        val sentences = SentenceParser.splitWithRanges(pageText)
        ttsManager.speakPage(sentences)
    }

    fun setCharsPerPage(cpp: Int) {
        _charsPerPage.value = cpp
        val text = _fullText.value
        if (text.isNotEmpty()) {
            val currentOffset = currentCharOffset()
            paginate(text)
            _currentPage.value = findPageForOffset(currentOffset)
        }
    }

    fun getProgress(): Float {
        val total = _pages.value.size
        if (total == 0) return 0f
        return (_currentPage.value + 1).toFloat() / total
    }

    private fun saveProgress() {
        viewModelScope.launch {
            WearBookRepository.updateProgress(bookId, currentCharOffset())
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveProgress()
        ttsManager.shutdown()
    }
}
