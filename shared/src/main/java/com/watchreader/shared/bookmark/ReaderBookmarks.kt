package com.watchreader.shared.bookmark

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BookmarkError { LOAD, SAVE }

data class ReaderBookmarksState(
    val items: List<Bookmark> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: BookmarkError? = null,
) {
    fun onPage(start: Int, end: Int): Bookmark? = items.firstOrNull { it.offset in start until end }
}

/** UI state shared by both reader ViewModels; it never owns or modifies book/progress rows. */
class ReaderBookmarks(
    private val repository: BookmarkRepository,
    private val bookId: String,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(ReaderBookmarksState())
    val state = mutableState.asStateFlow()
    private var observer: Job? = null

    init { refresh() }

    fun refresh() {
        if (observer?.isActive != true) {
            mutableState.update { it.copy(loading = true, error = null) }
            observer = scope.launch {
                try {
                    repository.observe(bookId).collect { items ->
                        mutableState.update { it.copy(items = items, loading = false) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(loading = false, error = BookmarkError.LOAD) }
                }
            }
        }
        repository.requestSync()
    }

    fun add(offset: Int, text: String) = change { repository.add(bookId, offset, text) }
    fun remove(offset: Int) = change { repository.remove(bookId, offset) }
    fun clearError() { mutableState.update { it.copy(error = null) } }

    private fun change(block: suspend () -> Unit) {
        if (state.value.loading || state.value.saving || observer?.isActive != true) return
        mutableState.update { it.copy(saving = true, error = null) }
        scope.launch {
            try {
                // Navigating away immediately after tapping must not cancel a disk write.
                withContext(NonCancellable) { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(error = BookmarkError.SAVE) }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }
}
