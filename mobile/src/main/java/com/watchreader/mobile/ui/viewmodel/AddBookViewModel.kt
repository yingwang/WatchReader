package com.watchreader.mobile.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.mobile.data.repository.ImportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ImportFile(val uri: Uri, val name: String)
data class ImportFailure(val file: ImportFile?, val message: String)

class AddBookViewModel(application: Application) : AndroidViewModel(application) {
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _completed = MutableStateFlow(false)
    val completed = _completed.asStateFlow()
    private val _added = MutableStateFlow<List<Book>>(emptyList())
    val added = _added.asStateFlow()
    private val _failures = MutableStateFlow<List<ImportFailure>>(emptyList())
    val failures = _failures.asStateFlow()
    private val _progress = MutableStateFlow(0 to 0)
    val progress = _progress.asStateFlow()

    fun clearError() { _error.value = null }
    fun reset() {
        if (_isLoading.value) return
        _completed.value = false
        _added.value = emptyList()
        _failures.value = emptyList()
        _error.value = null
    }

    fun addFiles(files: List<ImportFile>, title: String = "") {
        if (_isLoading.value || files.isEmpty()) return
        _isLoading.value = true
        _completed.value = false
        _failures.value = emptyList()
        viewModelScope.launch {
            try {
                for ((index, file) in files.distinctBy { it.uri }.withIndex()) {
                    _progress.value = index + 1 to files.size
                    try {
                        val book = BookRepository.addFromUri(getApplication(), file.uri, if (files.size == 1) title else "", BookRepository.bareName(file.name))
                        _added.value += book
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { _failures.value += ImportFailure(file, describe(e)) }
                }
                _completed.value = true
            } finally { _isLoading.value = false }
        }
    }

    fun addFromUrl(url: String, title: String = "") {
        if (_isLoading.value || url.isBlank()) return
        _isLoading.value = true
        _error.value = null
        _completed.value = false
        _failures.value = emptyList()
        _progress.value = 1 to 1
        viewModelScope.launch {
            try {
                _added.value += BookRepository.addFromUrl(url, title)
                _completed.value = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _error.value = describe(e) }
            finally { _isLoading.value = false }
        }
    }

    fun retryFailed() = addFiles(_failures.value.mapNotNull { it.file })

    private fun describe(e: Exception): String = when (e) {
        is ImportException -> e.message ?: getApplication<Application>().getString(R.string.err_add_failed)
        is java.net.UnknownHostException -> getApplication<Application>().getString(R.string.err_no_network)
        is java.net.SocketTimeoutException -> getApplication<Application>().getString(R.string.err_timeout)
        else -> getApplication<Application>().getString(R.string.err_add_failed)
    }
}
