package com.watchreader.mobile.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchreader.mobile.data.repository.BookRepository
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

    fun addFromUri(uri: Uri, title: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                BookRepository.addFromUri(getApplication(), uri, title)
                _done.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to add book"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addFromUrl(url: String, title: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                BookRepository.addFromUrl(url, title.ifBlank { urlToTitle(url) })
                _done.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to download"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun urlToTitle(url: String): String {
        return url.substringAfterLast("/").substringBeforeLast(".").ifBlank { "Untitled" }
    }
}
