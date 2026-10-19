package com.watchreader.shared.search

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReaderSearchState(
    val query: String = "",
    val results: SearchResults = SearchResults(),
    val searching: Boolean = false,
    val generation: Long = 0,
)

/** Called from the UI thread. Changing a query invalidates even a worker already finishing. */
class ReaderSearch(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val debounceMillis: Long = 180,
) {
    private val mutableState = MutableStateFlow(ReaderSearchState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun search(text: String, query: String) {
        job?.cancel()
        val generation = state.value.generation + 1
        mutableState.value = ReaderSearchState(query, searching = query.isNotBlank(), generation = generation)
        if (query.isBlank()) return
        job = scope.launch {
            delay(debounceMillis)
            val results = withContext(worker) {
                val context = currentCoroutineContext()
                BookSearch.find(text, query, checkpoint = { context.ensureActive() })
            }
            if (state.value.generation == generation) {
                mutableState.value = ReaderSearchState(query, results, generation = generation)
            }
        }
    }

    /** A click from a sheet displaying an older query must not move the reader. */
    fun selectedOffset(generation: Long, hit: SearchHit): Int? = state.value.let {
        hit.start.takeIf { _ -> it.generation == generation && !it.searching && it.query.isNotBlank() && hit in it.results.hits }
    }

    fun clear() = search("", "")
}
