package com.watchreader.mobile.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchreader.mobile.BuildConfig
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.ui.viewmodel.BookListViewModel
import com.watchreader.mobile.ui.viewmodel.UiEvent
import kotlin.math.absoluteValue

private val coverColors = listOf(
    Color(0xFF8B6E4E), Color(0xFF6B7B5E), Color(0xFF7B6B8A), Color(0xFF5E7B8B),
    Color(0xFF8B5E5E), Color(0xFF5E8B7B), Color(0xFF7B7B5E), Color(0xFF6B5E8B),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookListScreen(
    onAddBook: () -> Unit,
    onOpenBook: (String) -> Unit,
    vm: BookListViewModel = viewModel(),
) {
    val books by vm.books.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<Book?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbar.showSnackbar(
                    if (event.arg == null) context.getString(event.text) else context.getString(event.text, event.arg),
                )
                is UiEvent.OfferInstall -> {
                    val result = snackbar.showSnackbar(
                        message = context.getString(R.string.msg_watch_without_app, event.watchName),
                        actionLabel = context.getString(R.string.msg_install_action),
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
                    Column {
                        Text(stringResource(R.string.list_title))
                        Text(
                            stringResource(R.string.list_version, BuildConfig.VERSION_NAME),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddBook) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.list_add))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (books.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.list_empty_title),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 18.sp,
                    )
                    Text(
                        stringResource(R.string.list_empty_hint),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Text(
                    stringResource(R.string.list_tap_hint),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(books, key = { it.id }) { book ->
                        BookCover(
                            book = book,
                            onClick = { onOpenBook(book.id) },
                            onLongClick = { deleteTarget = book },
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(book.title) },
            text = { Text(stringResource(R.string.delete_title) + "\n" + stringResource(R.string.delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteBook(book)
                    deleteTarget = null
                }) { Text(stringResource(R.string.delete_confirm), color = Color(0xFFEF5350)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.delete_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCover(
    book: Book,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bgColor = coverColors[book.id.hashCode().absoluteValue % coverColors.size]

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        val art = rememberCoverArt(book.coverPath)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .background(bgColor, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                )
            } else {
                Text(
                    text = book.title,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(12.dp),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor.copy(alpha = 0.6f), RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (book.syncStatus == SyncStatus.SENT && book.readProgress > 0f) {
                LinearProgressIndicator(
                    progress = { book.readProgress },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp),
                    color = Color.White.copy(alpha = 0.8f),
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
            }
            Text(
                text = statusText(book),
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun statusText(book: Book): String = when (book.syncStatus) {
    SyncStatus.NOT_SENT -> stringResource(R.string.status_not_sent)
    SyncStatus.SENDING -> stringResource(R.string.status_sending)
    SyncStatus.SENT ->
        if (book.readProgress > 0f) stringResource(R.string.status_sent_progress, (book.readProgress * 100).toInt())
        else stringResource(R.string.status_sent)
    SyncStatus.FAILED -> stringResource(R.string.status_failed)
}

/** Cover art from an epub, decoded once per file. Books without one keep the coloured block. */
@Composable
private fun rememberCoverArt(path: String?): ImageBitmap? = androidx.compose.runtime.remember(path) {
    if (path == null) return@remember null
    runCatching { android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
}
