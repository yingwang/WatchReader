package com.watchreader.wear.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.watchreader.wear.data.model.WearBook
import com.watchreader.wear.data.repository.WearBookRepository
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
    data class Ready(
        val title: String,
        val page: Paginator.Page,
        val totalChars: Int,
    ) : ReaderUiState() {
        val fraction: Float get() = if (totalChars == 0) 0f else page.end.toFloat() / totalChars
        val atEnd: Boolean get() = page.end >= totalChars
    }
}

/**
 * Owns the book text and the current page. Pages are laid out by a [Paginator] against the
 * geometry and measurer the screen supplies (it knows the font and the screen shape); until they
 * arrive the state stays Loading.
 */
class ReaderViewModel(
    application: Application,
    private val bookId: String,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var book: WearBook? = null
    private var text: String = ""
    private var loaded = false
    private var layout: Pair<PageGeometry, LineMeasurer>? = null
    private var paginator: Paginator? = null
    private var page: Paginator.Page? = null
    private var restoreOffset = 0
    private var flipsSinceSync = 0

    init {
        viewModelScope.launch {
            val found = WearBookRepository.getById(bookId)
            if (found == null) {
                _state.value = ReaderUiState.Missing
                return@launch
            }
            book = found
            text = runCatching { WearBookRepository.loadText(found) }.getOrElse {
                _state.value = ReaderUiState.Missing
                return@launch
            }
            restoreOffset = found.readOffsetChars.coerceIn(0, text.length)
            loaded = true
            rebuild()
        }
    }

    /** Called by the screen whenever the font, the screen shape or the measurer changes. */
    fun attachLayout(geometry: PageGeometry, measurer: LineMeasurer) {
        layout = geometry to measurer
        if (loaded) rebuild()
    }

    private fun rebuild() {
        val (geometry, measurer) = layout ?: return
        val p = Paginator(text, geometry, measurer)
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
        flipped()
    }

    fun prevPage() {
        val p = paginator ?: return
        val current = page ?: return
        if (current.start <= 0) return
        page = p.pageEndingAt(current.start)
        publish()
        flipped()
    }

    fun jumpToFraction(fraction: Float) {
        val p = paginator ?: return
        if (!loaded) return
        val offset = (fraction.coerceIn(0f, 1f) * p.length).toInt()
        page = if (fraction >= 1f) p.pageEndingAt(p.length) else p.pageFrom(p.paragraphStart(offset))
        publish()
        saveProgress(toPhone = true)
    }

    /** Keeps the page under the sentence being read aloud. */
    fun followSpoken(offset: Int) {
        val p = paginator ?: return
        val current = page ?: return
        if (offset in current.start until current.end) return
        page = if (offset >= current.end) {
            val next = p.pageFrom(current.end)
            if (offset in next.start until next.end) next else p.pageFrom(offset)
        } else {
            p.pageFrom(offset)
        }
        publish()
    }

    private fun publish() {
        val b = book ?: return
        val current = page ?: return
        _state.value = ReaderUiState.Ready(title = b.title, page = current, totalChars = text.length)
    }

    private fun flipped() {
        flipsSinceSync++
        val toPhone = flipsSinceSync >= SYNC_EVERY_FLIPS
        if (toPhone) flipsSinceSync = 0
        saveProgress(toPhone)
    }

    fun saveProgress(toPhone: Boolean) {
        val b = book ?: return
        val offset = page?.start ?: return
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                WearBookRepository.updateProgress(b.id, offset)
                if (toPhone) WearBookRepository.sendProgressToPhone(b, offset)
            }
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onCleared() {
        // viewModelScope is gone by now; hand the last save to a scope that outlives us.
        val b = book
        val offset = page?.start
        if (b != null && offset != null) {
            GlobalScope.launch(Dispatchers.IO) {
                WearBookRepository.updateProgress(b.id, offset)
                WearBookRepository.sendProgressToPhone(b, offset)
            }
        }
        super.onCleared()
    }

    private companion object {
        const val SYNC_EVERY_FLIPS = 8
    }

    class Factory(private val application: Application, private val bookId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(application, bookId) as T
    }
}
