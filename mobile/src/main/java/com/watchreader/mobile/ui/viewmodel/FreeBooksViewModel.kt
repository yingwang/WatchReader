package com.watchreader.mobile.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.FreeBook
import com.watchreader.mobile.data.model.FreeBookDetails
import com.watchreader.mobile.data.repository.FreeBookRepository
import com.watchreader.mobile.data.repository.GutenbergCatalog
import com.watchreader.mobile.data.repository.ImportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    /** Why something could not be had from the library; the screen words each one. */
    enum class Problem { OFFLINE, UNAVAILABLE }

    data class Listing(
        /** What was searched for, which the field may since have moved on from; empty for the popular list. */
        val search: String = "",
        val books: List<FreeBook> = emptyList(),
        val next: String? = null,
        /** The first page of a new list is on its way. */
        val loading: Boolean = false,
        val problem: Problem? = null,
        val loadingMore: Boolean = false,
        val moreProblem: Problem? = null,
    )

    /** The open book's own catalogue entry, asked for when it is opened. */
    sealed interface Details {
        data object Loading : Details
        data class Loaded(val details: FreeBookDetails) : Details
        data class Failed(val problem: Problem) : Details
    }

    sealed interface Adding {
        data object Idle : Adding
        data object Busy : Adding
        data class Failed(val message: String) : Adding
        data object Done : Adding
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** A language code, or null for every language. */
    private val _language = MutableStateFlow(GutenbergCatalog.languageFor(Locale.getDefault()))
    val language: StateFlow<String?> = _language.asStateFlow()

    private val _listing = MutableStateFlow(Listing(loading = true))
    val listing: StateFlow<Listing> = _listing.asStateFlow()

    /** The book whose details are open. */
    private val _selected = MutableStateFlow<FreeBook?>(null)
    val selected: StateFlow<FreeBook?> = _selected.asStateFlow()

    private val _details = MutableStateFlow<Details>(Details.Loading)
    val details: StateFlow<Details> = _details.asStateFlow()

    private val _adding = MutableStateFlow<Adding>(Adding.Idle)
    val adding: StateFlow<Adding> = _adding.asStateFlow()

    private var listJob: Job? = null
    private var moreJob: Job? = null
    private var detailsJob: Job? = null
    private var addJob: Job? = null

    /** The search and language the list shows, or is on its way to showing. */
    private var listed: Pair<String, String?>? = null

    init {
        refresh()
    }

    /** A search goes out with the keyboard's search key; an emptied field goes back to the popular list at once. */
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
            _listing.value = try {
                val page = FreeBookRepository.firstPage(search, language)
                Listing(search, page.books, page.next)
            } catch (e: IOException) {
                Listing(search, problem = problemOf(e))
            }
        }
    }

    /**
     * The next page, asked for as the list nears its end and never before; once it has failed,
     * only [retryMore] asks again.
     */
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

    /**
     * Opens a book's details and asks for its catalogue entry, or closes them with null. Closing
     * them stops whatever was still on its way for the book, a download included.
     */
    fun select(book: FreeBook?) {
        detailsJob?.cancel()
        addJob?.cancel()
        _adding.value = Adding.Idle
        _selected.value = book
        if (book != null) loadDetails(book)
    }

    fun retryDetails() {
        _selected.value?.let(::loadDetails)
    }

    private fun loadDetails(book: FreeBook) {
        detailsJob?.cancel()
        _details.value = Details.Loading
        detailsJob = viewModelScope.launch {
            _details.value = try {
                Details.Loaded(FreeBookRepository.details(book))
            } catch (e: IOException) {
                Details.Failed(problemOf(e))
            }
        }
    }

    /** Downloads the open book into the library; leaving the screen or closing the details stops it. */
    fun add() {
        val book = _selected.value ?: return
        val details = (_details.value as? Details.Loaded)?.details ?: return
        if (refusalOf(details) != null) return
        if (_adding.value == Adding.Busy || _adding.value == Adding.Done) return
        _adding.value = Adding.Busy
        addJob = viewModelScope.launch {
            _adding.value = try {
                FreeBookRepository.add(book, details)
                Adding.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Adding.Failed(describeAddFailure(getApplication(), e))
            }
        }
    }

    private fun problemOf(e: IOException): Problem = when (e) {
        // No address for the server, or no route to it: the phone is offline, or as good as.
        is UnknownHostException, is SocketException -> Problem.OFFLINE
        // A timeout, an error page, or an answer that is not a feed.
        else -> Problem.UNAVAILABLE
    }

    private companion object {
        val SPACES = Regex("\\s+")
    }
}

/**
 * Why a book cannot be added from the free library, in the words its details give; null when it
 * can. The library's own picks are held to the same: public domain in the US, and something to read.
 */
@StringRes
internal fun refusalOf(details: FreeBookDetails): Int? = when {
    !details.publicDomain -> R.string.free_not_public_domain
    details.files.isEmpty() -> R.string.free_nothing_to_read
    else -> null
}

/** A free book's download that failed, worded the same wherever it was started. */
internal fun describeAddFailure(context: Context, e: Exception): String = when (e) {
    is ImportException -> e.message ?: context.getString(R.string.err_add_failed)
    is UnknownHostException, is SocketException -> context.getString(R.string.free_add_no_network)
    is SocketTimeoutException -> context.getString(R.string.err_timeout)
    else -> e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.err_download_failed)
}
