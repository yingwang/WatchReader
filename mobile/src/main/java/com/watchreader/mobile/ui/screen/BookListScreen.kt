package com.watchreader.mobile.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.watchreader.mobile.ui.navigation.ScrollToTopOn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchreader.mobile.R
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.mobile.ui.viewmodel.BookListViewModel
import com.watchreader.mobile.ui.viewmodel.UiEvent
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchreader.mobile.ui.library.LibraryFilter
import com.watchreader.mobile.ui.library.LibraryLayout
import com.watchreader.mobile.ui.library.LibraryQuery
import com.watchreader.mobile.ui.library.LibrarySort

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookListScreen(
    onAddBook: () -> Unit,
    onOpenBook: (String) -> Unit,
    onDetails: (String) -> Unit,
    onFreeBooks: () -> Unit,
    scrollToTop: Int = 0,
    vm: BookListViewModel = viewModel(),
) {
    // Null until the database has answered, so "No books yet" is never shown to a library that
    // simply has not loaded.
    val stored by vm.books.collectAsState()
    val books = stored.orEmpty()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    // The book is kept by id, so the question survives a rotation and goes away with the book.
    var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val deleteTarget = deleteTargetId?.let { id -> books.firstOrNull { it.id == id } }
    val uiPrefs = remember { context.getSharedPreferences("library_ui", android.content.Context.MODE_PRIVATE) }
    var layout by rememberSaveable { mutableStateOf(LibraryLayout.fromPreference(uiPrefs.getString("layout", null))) }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.ADDED) }
    val query = LibraryQuery(search, filter, sort)
    val changeQuery: (LibraryQuery) -> Unit = {
        search = it.search
        filter = it.filter
        sort = it.sort
    }
    val milestonesFlow = remember { BookRepository.observeMilestones() }
    val milestones by milestonesFlow.collectAsStateWithLifecycle(initialValue = null)
    val milestoneMap = remember(milestones) { milestones.orEmpty().associateBy { it.bookId } }
    val visibleBooks = remember(books, query, milestoneMap) { query.select(books, milestoneMap) }
    val currentBook = remember(visibleBooks, milestoneMap, milestones == null) {
        if (milestones == null) null else LibraryQuery.continuation(visibleBooks, milestoneMap)
    }
    val gridState = rememberLazyGridState()
    ScrollToTopOn(scrollToTop) { gridState.animateScrollToItem(0) }
    // The search lives in the title bar, folded away until asked for; closing it clears it.
    var searchOpen by rememberSaveable { mutableStateOf(search.isNotEmpty()) }
    var focusSearch by remember { mutableStateOf(false) }
    val closeSearch = {
        searchOpen = false
        focusSearch = false
        search = ""
    }
    BackHandler(enabled = searchOpen, onBack = closeSearch)
    // A library holding only the bundled guide is a new reader's, pointed at the free books.
    val ownBooks = BookRepository.hasOwnBooks(books)
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbar.showSnackbar(
                    if (event.arg == null) context.getString(event.text) else context.getString(event.text, event.arg),
                )
                is UiEvent.OfferInstall -> {
                    // A snackbar with an action waits for it indefinitely unless told otherwise,
                    // and holds back every message after it; this one can be closed and times out.
                    val result = snackbar.showSnackbar(
                        message = context.getString(R.string.msg_watch_without_app, event.watchName),
                        actionLabel = context.getString(R.string.msg_install_action),
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) vm.openPlayOnWatch(event.nodeId)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchOpen) {
                        LibrarySearchField(search, onSearchChange = { search = it }, focus = focusSearch)
                    } else {
                        Text(stringResource(R.string.list_books))
                    }
                },
                navigationIcon = {
                    if (searchOpen) IconButton(onClick = closeSearch) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.library_search_close))
                    }
                },
                actions = {
                    if (books.isNotEmpty()) {
                        if (!searchOpen) IconButton(onClick = {
                            searchOpen = true
                            focusSearch = true
                        }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.library_search_open))
                        }
                        LibrarySortMenu(sort, onSortChange = { sort = it })
                        val next = if (layout == LibraryLayout.COMPACT) LibraryLayout.COVERS else LibraryLayout.COMPACT
                        IconButton(onClick = {
                            layout = next
                            uiPrefs.edit().putString("layout", next.name).apply()
                        }) {
                            Icon(
                                if (next == LibraryLayout.COMPACT) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                                contentDescription = stringResource(
                                    if (next == LibraryLayout.COMPACT) R.string.library_show_list else R.string.library_show_covers,
                                ),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddBook,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.list_add))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (stored == null) {
            // The first read takes a moment; the page stays blank rather than claim anything.
        } else if (books.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                // Keep the empty-library actions reachable on a small landscape screen.
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.list_empty_title),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 18.sp,
                    )
                    Text(
                        stringResource(R.string.list_empty_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp).padding(horizontal = 16.dp),
                    )
                    FilledTonalButton(onClick = onFreeBooks, modifier = Modifier.padding(top = 24.dp)) {
                        Text(stringResource(R.string.list_find_books))
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (layout == LibraryLayout.COMPACT) 1 else 2),
                state = gridState,
                modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(if (layout == LibraryLayout.COMPACT) 8.dp else 12.dp),
            ) {
                // The filters only once there is something to sort through, or while one is on.
                if (ownBooks || filter != LibraryFilter.ALL) {
                    item(key = "library:filters", span = { GridItemSpan(maxLineSpan) }) {
                        LibraryFilterChips(filter, onFilterChange = { filter = it })
                    }
                }
                // Keep the continuation visible but smaller than a cover, and never outside the query.
                currentBook?.let { book ->
                    item(key = "library:continue", span = { GridItemSpan(maxLineSpan) }) {
                        ContinueReadingCard(book, onClick = { onOpenBook(book.id) })
                    }
                }
                if (!ownBooks && search.isBlank() && filter == LibraryFilter.ALL) {
                    item(key = "library:find", span = { GridItemSpan(maxLineSpan) }) {
                        FindBooksCard(onClick = onFreeBooks)
                    }
                }
                if (milestones == null) {
                    item(key = "library:loading", span = { GridItemSpan(maxLineSpan) }) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                } else if (visibleBooks.isEmpty()) {
                    item(key = "library:empty", span = { GridItemSpan(maxLineSpan) }) {
                        LibraryNoResults(onClear = { changeQuery(query.cleared()) })
                    }
                } else {
                    items(visibleBooks, key = { "book:${it.id}" }, contentType = { layout }) { book ->
                        val readingState = LibraryQuery.readingState(book, milestoneMap[book.id])
                        if (layout == LibraryLayout.COMPACT) {
                            CompactBookRow(
                                book = book,
                                readingState = readingState,
                                onClick = { onOpenBook(book.id) },
                                onDelete = { deleteTargetId = book.id },
                                onSend = { vm.sendToWatch(book) },
                                onCancelSend = { vm.cancelQueuedSend(book) },
                                onDetails = { onDetails(book.id) },
                            )
                        } else {
                            BookCover(
                                book = book,
                                readingState = readingState,
                                onClick = { onOpenBook(book.id) },
                                onDelete = { deleteTargetId = book.id },
                                onSend = { vm.sendToWatch(book) },
                                onCancelSend = { vm.cancelQueuedSend(book) },
                                onDetails = { onDetails(book.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { deleteTargetId = null },
            title = { Text(book.title) },
            text = { Text(stringResource(R.string.delete_title) + "\n" + stringResource(R.string.delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteBook(book)
                    deleteTargetId = null
                }) { Text(stringResource(R.string.delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetId = null }) { Text(stringResource(R.string.delete_cancel)) }
            },
        )
    }
}
