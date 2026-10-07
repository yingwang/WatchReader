package com.watchreader.mobile.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.FreeBook
import com.watchreader.mobile.data.model.FreeBookDetails
import com.watchreader.mobile.data.repository.FreeBookRepository
import com.watchreader.mobile.data.repository.GutenbergCatalog
import com.watchreader.mobile.ui.viewmodel.FreeBooksViewModel
import com.watchreader.mobile.ui.viewmodel.FreeBooksViewModel.Adding
import com.watchreader.mobile.ui.viewmodel.FreeBooksViewModel.Details
import com.watchreader.mobile.ui.viewmodel.FreeBooksViewModel.Listing
import com.watchreader.mobile.ui.viewmodel.FreeBooksViewModel.Problem
import java.util.Locale

/**
 * Project Gutenberg's public-domain books: the most downloaded in the phone's language to begin
 * with, a search by title or author, and a book's details with the button that adds it. A book
 * added here goes through the same import as a link, and the reader is taken to the library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeBooksScreen(
    onBack: () -> Unit,
    onAdded: () -> Unit,
    vm: FreeBooksViewModel = viewModel(),
) {
    val query by vm.query.collectAsState()
    val language by vm.language.collectAsState()
    val listing by vm.listing.collectAsState()
    val selected by vm.selected.collectAsState()
    val details by vm.details.collectAsState()
    val adding by vm.adding.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(adding) {
        if (adding == Adding.Done) onAdded()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.free_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.add_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // The keyboard covers the window rather than shrinking it, so the list and its notes keep clear of it.
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                placeholder = { Text(stringResource(R.string.free_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isEmpty()) null else {
                    {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.free_clear_search))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    vm.search()
                    keyboard?.hide()
                }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LanguageChoice(language, vm::setLanguage, Modifier.padding(start = 16.dp, top = 8.dp))
            Text(
                stringResource(R.string.free_legal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val problem = listing.problem
                when {
                    listing.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    problem != null -> ProblemNote(problem, onRetry = vm::retry)
                    listing.books.isEmpty() -> Note(
                        stringResource(R.string.free_empty_title),
                        stringResource(if (language != null) R.string.free_empty_hint_language else R.string.free_empty_hint),
                    )
                    else -> BookList(
                        listing = listing,
                        showPopular = listing.search.isEmpty(),
                        onSelect = vm::select,
                        onLoadMore = vm::loadMore,
                        onRetryMore = vm::retryMore,
                    )
                }
            }
        }
    }

    selected?.let { book ->
        BookSheet(book, details, adding, onAdd = vm::add, onRetry = vm::retryDetails, onDismiss = { vm.select(null) })
    }
}

/** The language filter: a chip that opens the list of languages, all of them first. */
@Composable
private fun LanguageChoice(language: String?, onPick: (String?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val choices = remember { GutenbergCatalog.LANGUAGES.map { it to languageName(it) }.sortedBy { it.second } }
    val all = stringResource(R.string.free_all_languages)
    val current = language?.let(::languageName) ?: all
    val description = stringResource(R.string.free_language_choice, current)
    Box(modifier) {
        AssistChip(
            onClick = { open = true },
            label = { Text(current) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
            modifier = Modifier.semantics { contentDescription = description },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf<Pair<String?, String>>(null to all) + choices).forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        if (code != language) onPick(code)
                    },
                    trailingIcon = if (code == language) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun BookList(
    listing: Listing,
    showPopular: Boolean,
    onSelect: (FreeBook) -> Unit,
    onLoadMore: () -> Unit,
    onRetryMore: () -> Unit,
) {
    val state = rememberLazyListState()
    // The next page is asked for while a few rows are still to come, so scrolling rarely waits.
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.collect { (last, total) ->
            if (total > 0 && last >= total - LOAD_AHEAD) onLoadMore()
        }
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (showPopular) item(key = "popular") {
            Text(
                stringResource(R.string.free_popular),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(listing.books, key = { it.id }) { book ->
            BookRow(book, onClick = { onSelect(book) })
        }
        val moreProblem = listing.moreProblem
        if (listing.loadingMore) item(key = "more") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        } else if (moreProblem != null) item(key = "more") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(if (moreProblem == Problem.OFFLINE) R.string.free_offline_title else R.string.free_more_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetryMore) { Text(stringResource(R.string.free_retry)) }
            }
        }
    }
}

@Composable
private fun BookRow(book: FreeBook, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(GutenbergCatalog.coverUrl(book.id, small = true), width = 52.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                authorOf(book),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * A book's details and the button that adds it. The title, author and cover are there at once;
 * the language, subjects and files come from the book's own feed, and a book Gutenberg does not
 * call public domain in the US gets a note in place of the button. Closing the details stops a
 * download under way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookSheet(
    book: FreeBook,
    details: Details,
    adding: Adding,
    onAdd: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Row {
                Cover(GutenbergCatalog.coverUrl(book.id, small = false), width = 88.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text(
                        authorOf(book),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            when (details) {
                Details.Loading -> Box(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
                is Details.Failed -> {
                    Text(
                        stringResource(if (details.problem == Problem.OFFLINE) R.string.free_offline_title else R.string.free_details_failed),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                    FilledTonalButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.free_retry))
                    }
                }
                is Details.Loaded -> LoadedDetails(details.details, adding, onAdd)
            }
        }
    }
}

@Composable
private fun LoadedDetails(details: FreeBookDetails, adding: Adding, onAdd: () -> Unit) {
    if (details.languages.isNotEmpty()) {
        Detail(stringResource(R.string.free_language_label), details.languages.joinToString(", ") { languageName(it) })
    }
    if (details.subjects.isNotEmpty()) {
        // The catalogue's headings, such as "Whaling -- Fiction"; a few say what the book is about.
        Detail(
            stringResource(R.string.free_subjects_label),
            details.subjects.take(MAX_SUBJECTS).joinToString("\n") { it.replace(" -- ", " – ") },
        )
    }
    val note = when {
        !details.publicDomain -> R.string.free_not_public_domain
        details.files.isEmpty() -> R.string.free_nothing_to_read
        else -> null
    }
    if (note != null) {
        Text(
            stringResource(note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp),
        )
        return
    }
    if (adding is Adding.Failed) {
        Text(adding.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
    }
    Spacer(Modifier.height(24.dp))
    if (adding == Adding.Busy || adding == Adding.Done) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.free_adding), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(stringResource(R.string.free_add))
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
    Text(value, style = MaterialTheme.typography.bodyMedium)
}

/** A Gutenberg cover, fetched once it is shown; the quiet block stands in until then, or for good. */
@Composable
private fun Cover(url: String, width: Dp) {
    val px = with(LocalDensity.current) { width.roundToPx() }
    val art by produceState(FreeBookRepository.cachedCover(url)?.asImageBitmap(), url, px) {
        if (value == null) value = FreeBookRepository.cover(url, px)?.asImageBitmap()
    }
    Box(
        Modifier
            .width(width)
            .aspectRatio(COVER_ASPECT)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        art?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun ProblemNote(problem: Problem, onRetry: () -> Unit) {
    val (title, hint) = when (problem) {
        Problem.OFFLINE -> R.string.free_offline_title to R.string.free_offline_hint
        Problem.UNAVAILABLE -> R.string.free_unavailable_title to R.string.free_try_later_hint
    }
    Note(stringResource(title), stringResource(hint)) {
        FilledTonalButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.free_retry))
        }
    }
}

/** A title and a line under it, centred where the list would be, as the empty library has them. */
@Composable
private fun Note(title: String, hint: String, action: @Composable () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, textAlign = TextAlign.Center)
        Text(
            hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        action()
    }
}

@Composable
private fun authorOf(book: FreeBook): String = book.author ?: stringResource(R.string.free_unknown_author)

/** The interface is in English, so languages are named in English too. */
private fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

/** Gutenberg's covers are 200 by 281. */
private const val COVER_ASPECT = 0.71f
private const val LOAD_AHEAD = 6
private const val MAX_SUBJECTS = 4
