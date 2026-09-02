package com.watchreader.mobile.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.ui.viewmodel.BookListViewModel
import com.watchreader.mobile.ui.viewmodel.ReaderUiState
import com.watchreader.mobile.ui.viewmodel.ReaderViewModel
import com.watchreader.mobile.ui.viewmodel.UiEvent
import kotlinx.coroutines.flow.debounce

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ReaderScreen(
    bookId: String,
    listVm: BookListViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ReaderViewModel = viewModel(
        key = bookId,
        factory = ReaderViewModel.Factory(context.applicationContext as android.app.Application, bookId),
    )
    val state by vm.state.collectAsState()
    val books by listVm.books.collectAsState()
    val book = books.firstOrNull { it.id == bookId }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        listVm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbar.showSnackbar(
                    if (event.arg == null) context.getString(event.text) else context.getString(event.text, event.arg),
                )
                is UiEvent.OfferInstall -> {
                    val result = snackbar.showSnackbar(
                        message = context.getString(R.string.msg_watch_without_app, event.watchName),
                        actionLabel = context.getString(R.string.msg_install_action),
                    )
                    if (result == SnackbarResult.ActionPerformed) listVm.openPlayOnWatch(event.nodeId)
                }
            }
        }
    }

    // Open where the reader left off, on this phone or on the watch.
    LaunchedEffect(state) {
        (state as? ReaderUiState.Ready)?.let { listState.scrollToItem(it.firstParagraph) }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .debounce(600)
            .collect { vm.saveProgress(it) }
    }

    val latestIndex by rememberUpdatedState(listState.firstVisibleItemIndex)
    DisposableEffect(Unit) {
        onDispose { vm.saveProgress(latestIndex) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        book?.title ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.add_back))
                    }
                },
                actions = {
                    val onWatch = book?.syncStatus == SyncStatus.SENT
                    IconButton(
                        onClick = { book?.let { listVm.sendToWatch(it) } },
                        // A book the watch already has does not need sending again.
                        enabled = book != null && book.syncStatus != SyncStatus.SENDING && !onWatch,
                    ) {
                        Icon(
                            if (onWatch) Icons.Filled.Check else Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(
                                if (onWatch) R.string.reader_on_watch else R.string.reader_send,
                            ),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (val s = state) {
            ReaderUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            ReaderUiState.Missing -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(stringResource(R.string.reader_missing), color = MaterialTheme.colorScheme.onBackground)
            }
            is ReaderUiState.Ready -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                items(s.paragraphs.size) { index ->
                    val paragraph = s.paragraphs[index]
                    Text(
                        text = paragraph.text,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 18.sp,
                        lineHeight = 30.sp,
                        fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
            }
        }
    }
}