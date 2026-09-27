package com.watchreader.mobile.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchreader.mobile.R
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.mobile.data.repository.ImportException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AddBookViewModel(application: Application) : AndroidViewModel(application) {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    /** A previous failure stops being true the moment the user chooses something else. */
    fun clearError() {
        _error.value = null
    }

    fun addFromUri(uri: Uri, title: String, fallbackTitle: String) = run {
        _isLoading.value = true
        _error.value = null
        try {
            BookRepository.addFromUri(getApplication(), uri, title, fallbackTitle)
            _done.value = true
        } catch (e: Exception) {
            _error.value = describe(e, R.string.err_add_failed)
        } finally {
            // A book that went in keeps the screen busy while it leaves; handing the button back
            // during the exit animation let a second tap store the same book twice.
            if (!_done.value) _isLoading.value = false
        }
    }

    fun addFromUrl(url: String, title: String) = run {
        _isLoading.value = true
        _error.value = null
        try {
            BookRepository.addFromUrl(url, title)
            _done.value = true
        } catch (e: Exception) {
            _error.value = describe(e, R.string.err_download_failed)
        } finally {
            if (!_done.value) _isLoading.value = false
        }
    }

    /** One import at a time, and none once a book is in: two quick taps would add it twice. */
    private fun run(block: suspend () -> Unit) {
        if (_isLoading.value || _done.value) return
        viewModelScope.launch { block() }
    }

    private fun describe(e: Exception, fallback: Int): String = when (e) {
        is ImportException -> e.message ?: getApplication<Application>().getString(fallback)
        is java.net.UnknownHostException -> getApplication<Application>().getString(R.string.err_no_network)
        is java.net.SocketTimeoutException -> getApplication<Application>().getString(R.string.err_timeout)
        else -> e.message?.takeIf { it.isNotBlank() } ?: getApplication<Application>().getString(fallback)
    }
}
