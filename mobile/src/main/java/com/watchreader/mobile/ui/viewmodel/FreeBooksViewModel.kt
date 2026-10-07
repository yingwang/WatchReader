package com.watchreader.mobile.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.FreeBook
import com.watchreader.mobile.data.repository.FreeBookRepository
import com.watchreader.mobile.data.repository.Gutendex
import com.watchreader.mobile.data.repository.ImportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

class FreeBooksViewModel(application: Application) : AndroidViewModel(application) {
    /** Why a list could not be had; the screen words each one. */
    enum class Problem { OFFLINE, SLOW, UNAVAILABLE }

    data class Listing(
        /** What was searched for, which the field may since have moved on from; empty for the popular list. */
        val search: String = "",
        val books: List<FreeBook> = emptyList(),
        val next: String? = null,
        /** The first page of a new search is on its way. */
        val loading: Boolean = false,
        /** It has been on its way long enough for the screen to say why. */
        val slow: Boolean = false,
        val problem: Problem? = null,
        val loadingMore: Boolean = false,
        val moreProblem: Problem? = null,
    )

    sealed interface Adding {
        data object Idle : Adding
        data object Busy : Adding
        data class Failed(val message: String) : Adding
        data object Done : Adding
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** A language code, or null for every language. */
    private val _language = MutableStateFlow(Gutendex.languageFor(Locale.getDefault()))
    val language: StateFlow<String?> = _language.asStateFlow()

    private val _listing = MutableStateFlow(Listing(loading = true))
    val listing: StateFlow<Listing> = _listing.asStateFlow()

    /** The book whose details are open. */
    private val _selected = MutableStateFlow<FreeBook?>(null)
    val selected: StateFlow<FreeBook?> = _selected.asStateFlow()

    private val _adding = MutableStateFlow<Adding>(Adding.Idle)
    val adding: StateFlow<Adding> = _adding.asStateFlow()

    private var listJob: Job? = null
    private var moreJob: Job? = null
    private var addJob: Job? = null

    /** The search and language the list shows, or is on its way to showing. */
    private var listed: Pair<String, String?>? = null

    init {
        refresh()
    }

    /**
     * A search goes out with the keyboard's search key, not as the reader types: one Gutendex
     * has not seen lately costs it a minute or more of work, and one at every pause in the
     * typing would cost it several. An emptied field goes back to the popular list at once.
     */
    fun setQuery(text: String) {
        _query.value = text
        if (text.isBlank()) refresh()
    }

    fun search() = refresh()

    fun setLanguage(code: String?) {
        _language.value = code
        refresh()
    }

    fun retry() = refresh(again = true)

    private fun refresh(again: Boolean = false) {
        val wanted = _query.value.trim().replace(SPACES, " ") to _language.value
        // The search key pressed again on the list already showing, or on its way, asks nothing new.
        if (wanted == listed && !again && _listing.value.problem == null) return
        listJob?.cancel()
        moreJob?.cancel()
        listed = wanted
        val (search, language) = wanted
        listJob = viewModelScope.launch {
            _listing.value = Listing(search, loading = true)
            val note = launch {
                delay(SLOW_AFTER_MS)
                _listing.update { it.copy(slow = true) }
            }
            try {
                var page = FreeBookRepository.firstPage(search, language)
                // A page can hold nothing but audio books or music, none of which is listed here;
                // the next ones may have something to read.
                repeat(EMPTY_PAGES_SKIPPED) {
                    val next = page.next
                    if (page.books.isEmpty() && next != null) page = FreeBookRepository.page(next)
                }
                _listing.value = Listing(search, page.books, page.next)
            } catch (e: IOException) {
                _listing.value = Listing(search, problem = problemOf(e))
            } finally {
                note.cancel()
            }
        }
    }

    /** The next page, asked for as the list nears its end; once it has failed, only [retryMore] asks again. */
    fun loadMore() {
        val now = _listing.value
        val next = now.next ?: return
        if (now.loading || now.loadingMore || now.moreProblem != null || moreJob?.isActive == true) return
        moreJob = viewModelScope.launch {
            _listing.update { it.copy(loadingMore = true) }
            try {
                val page = FreeBookRepository.page(next)
                _listing.update { it.copy(books = (it.books + page.books).distinctBy { book -> book.id }, next = page.next, loadingMore = false) }
            } catch (e: IOException) {
                _listing.update { it.copy(loadingMore = false, moreProblem = problemOf(e)) }
            }
        }
    }

    fun retryMore() {
        _listing.update { it.copy(moreProblem = null) }
        loadMore()
    }

    /** Opens a book's details, or closes them with null; closing them stops a download under way. */
    fun select(book: FreeBook?) {
        addJob?.cancel()
        _adding.value = Adding.Idle
        _selected.value = book
    }

    /** Downloads the open book into the library; leaving the screen or closing the details stops it. */
    fun add() {
        val book = _selected.value ?: return
        if (_adding.value == Adding.Busy || _adding.value == Adding.Done) return
        _adding.value = Adding.Busy
        addJob = viewModelScope.launch {
            _adding.value = try {
                FreeBookRepository.add(book)
                Adding.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Adding.Failed(describe(e))
            }
        }
    }

    private fun problemOf(e: IOException): Problem = when (e) {
        is SocketTimeoutException -> Problem.SLOW
        // No address for the server, or no route to it: the phone is offline, or as good as.
        is UnknownHostException, is SocketException -> Problem.OFFLINE
        // An error page, or an answer that is not a list of books.
        else -> Problem.UNAVAILABLE
    }

    private fun describe(e: Exception): String {
        val app = getApplication<Application>()
        return when (e) {
            is ImportException -> e.message ?: app.getString(R.string.err_add_failed)
            is UnknownHostException, is SocketException -> app.getString(R.string.free_add_no_network)
            is SocketTimeoutException -> app.getString(R.string.err_timeout)
            else -> e.message?.takeIf { it.isNotBlank() } ?: app.getString(R.string.err_download_failed)
        }
    }

    private companion object {
        const val SLOW_AFTER_MS = 6_000L
        const val EMPTY_PAGES_SKIPPED = 2
        val SPACES = Regex("\\s+")
    }
}
