package com.watchreader.mobile.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.watchreader.mobile.R
import com.watchreader.mobile.ui.library.LibraryFilter
import com.watchreader.mobile.ui.library.LibrarySort

/** The title search, in place of the title while it is open. [focus] is for a search just opened. */
@Composable
internal fun LibrarySearchField(search: String, onSearchChange: (String) -> Unit, focus: Boolean) {
    val focusManager = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(focus) {
        if (focus) requester.requestFocus()
    }
    TextField(
        value = search,
        onValueChange = onSearchChange,
        modifier = Modifier.fillMaxWidth().focusRequester(requester),
        placeholder = { Text(stringResource(R.string.library_search)) },
        trailingIcon = if (search.isNotEmpty()) {
            {
                IconButton(onClick = { onSearchChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.library_clear_search))
                }
            }
        } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

/** The order of the books, from an icon in the title bar. */
@Composable
internal fun LibrarySortMenu(sort: LibrarySort, onSortChange: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(R.string.library_sort_description, stringResource(sort.label)),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LibrarySort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label)) },
                    modifier = Modifier.semantics { selected = sort == option },
                    trailingIcon = if (sort == option) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        open = false
                        onSortChange(option)
                    },
                )
            }
        }
    }
}

/** One row of filters; the one chosen is tapped again to show every book. */
@Composable
internal fun LibraryFilterChips(filter: LibraryFilter, onFilterChange: (LibraryFilter) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibraryFilter.entries.forEach { option ->
            FilterChip(
                selected = filter == option,
                onClick = { onFilterChange(if (filter == option) LibraryFilter.ALL else option) },
                label = { Text(stringResource(option.label)) },
            )
        }
    }
}

/** For a library with nothing but the guide in it: where the free books are. */
@Composable
internal fun FindBooksCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.list_find_books), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.list_find_books_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun LibraryNoResults(onClear: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.library_no_results), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.library_no_results_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onClear) { Text(stringResource(R.string.library_clear_query)) }
    }
}

private val LibraryFilter.label: Int
    get() = when (this) {
        LibraryFilter.ALL -> R.string.library_filter_all
        LibraryFilter.READING -> R.string.library_filter_reading
        LibraryFilter.FINISHED -> R.string.library_filter_finished
        LibraryFilter.ON_WATCH -> R.string.library_filter_on_watch
    }

private val LibrarySort.label: Int
    get() = when (this) {
        LibrarySort.ADDED -> R.string.library_sort_added
        LibrarySort.RECENTLY_READ -> R.string.library_sort_read
    }
