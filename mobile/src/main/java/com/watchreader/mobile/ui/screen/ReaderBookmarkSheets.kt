package com.watchreader.mobile.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.watchreader.mobile.R
import com.watchreader.shared.bookmark.BookmarkError
import com.watchreader.shared.bookmark.ReaderBookmarksState
import com.watchreader.shared.search.ReaderSearchState
import com.watchreader.shared.search.SearchHit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderBookmarkSheet(
    state: ReaderBookmarksState,
    pageStart: Int,
    pageEnd: Int,
    totalChars: Int,
    onToggle: () -> Unit,
    onJump: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            Text(stringResource(R.string.reader_bookmarks), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            TextButton(
                onClick = onToggle,
                enabled = !state.loading && !state.saving && state.error != BookmarkError.LOAD && pageStart < totalChars,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text(stringResource(if (state.onPage(pageStart, pageEnd) == null) R.string.reader_bookmark_add else R.string.reader_bookmark_remove))
            }
            when {
                state.loading -> CircularProgressIndicator(Modifier.padding(24.dp))
                state.error != null -> {
                    Text(stringResource(if (state.error == BookmarkError.LOAD) R.string.reader_bookmark_load_error else R.string.reader_bookmark_save_error), modifier = Modifier.padding(horizontal = 24.dp))
                    if (state.error == BookmarkError.LOAD) TextButton(onClick = onRetry) { Text(stringResource(R.string.reader_bookmark_retry)) }
                }
                state.items.isEmpty() -> Text(stringResource(R.string.reader_bookmark_empty), modifier = Modifier.padding(24.dp))
            }
            LazyColumn(Modifier.weight(1f)) {
                items(state.items, key = { it.offset }) { bookmark ->
                    val current = bookmark.offset in pageStart until pageEnd
                    Row(
                        Modifier.fillMaxWidth().background(if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).clickable(enabled = bookmark.offset < totalChars) { onJump(bookmark.offset) }.padding(start = 24.dp, top = 14.dp, bottom = 14.dp, end = 8.dp)) {
                            Text(stringResource(R.string.reader_bookmark_position, percent(bookmark.offset, totalChars)), style = MaterialTheme.typography.labelMedium)
                            Text(bookmark.excerpt, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        }
                        IconButton(onClick = { onDelete(bookmark.offset) }, enabled = !state.saving) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.reader_bookmark_delete, bookmark.excerpt))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSearchSheet(
    state: ReaderSearchState,
    totalChars: Int,
    onQuery: (String) -> Unit,
    onResult: (Long, SearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Text(stringResource(R.string.reader_search), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                label = { Text(stringResource(R.string.reader_search_query)) },
                singleLine = true,
                trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.reader_search_clear))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).focusRequester(focus),
            )
            Text(
                when {
                    state.query.isBlank() -> stringResource(R.string.reader_search_hint)
                    state.searching -> stringResource(R.string.reader_search_running)
                    state.results.hasMore -> pluralStringResource(R.plurals.reader_search_limited, state.results.hits.size, state.results.hits.size)
                    state.results.hits.isEmpty() -> stringResource(R.string.reader_search_empty)
                    else -> pluralStringResource(R.plurals.reader_search_results, state.results.hits.size, state.results.hits.size)
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(state.results.hits, key = { it.start }) { hit ->
                    Column(Modifier.fillMaxWidth().clickable { onResult(state.generation, hit) }.padding(horizontal = 24.dp, vertical = 14.dp)) {
                        Text(stringResource(R.string.reader_bookmark_position, percent(hit.start, totalChars)), style = MaterialTheme.typography.labelMedium)
                        val highlighted = buildAnnotatedString {
                            append(hit.excerpt)
                            addStyle(SpanStyle(background = MaterialTheme.colorScheme.primaryContainer, color = MaterialTheme.colorScheme.onPrimaryContainer), hit.matchStart, hit.matchEnd)
                        }
                        Text(highlighted, style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider()
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
        LaunchedEffect(state.generation) { listState.scrollToItem(0) }
    }
}

private fun percent(offset: Int, length: Int): Int = if (length <= 0) 0 else (offset.toLong() * 100 / length).toInt().coerceIn(0, 100)
