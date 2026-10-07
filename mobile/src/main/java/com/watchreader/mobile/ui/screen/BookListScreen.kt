package com.watchreader.mobile.ui.screen

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.ui.viewmodel.BookListViewModel
import com.watchreader.mobile.ui.viewmodel.UiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    onStats: () -> Unit,
    onDetails: (String) -> Unit,
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
    var showHint by remember { mutableStateOf(!uiPrefs.getBoolean("hint_dismissed", false)) }
    val currentBook = books.filter { it.lastReadEpochMs > 0 }.maxByOrNull { it.lastReadEpochMs }
    val readingTime by vm.readingTime.collectAsState()

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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (showHint) item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Text(stringResource(R.string.list_tap_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = {
                                showHint = false
                                uiPrefs.edit().putBoolean("hint_dismissed", true).apply()
                            }) { Text(stringResource(R.string.list_dismiss_hint)) }
                        }
                    }
                    // Today's reading time, once there is any on record; a new reader sees no zeros.
                    if (readingTime.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        TodayLine(readingTime, onClick = onStats)
                    }
                    currentBook?.let { book ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Card(
                                onClick = { onOpenBook(book.id) },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                val cover = rememberCoverArt(book.coverPath)
                                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (cover != null) {
                                        Image(bitmap = cover, contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.width(76.dp).aspectRatio(0.7f).clip(RoundedCornerShape(6.dp)))
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(R.string.list_continue), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(book.title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        Text(stringResource(R.string.list_progress, (book.readProgress.coerceIn(0f, 1f) * 100).toInt()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        LinearProgressIndicator(progress = { book.readProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.list_books), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(books, key = { it.id }) { book ->
                        BookCover(
                            book = book,
                            onClick = { onOpenBook(book.id) },
                            onDelete = { deleteTargetId = book.id },
                            onSend = { vm.sendToWatch(book) },
                            onDetails = { onDetails(book.id) },
                        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCover(
    book: Book,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onSend: () -> Unit,
    onDetails: () -> Unit,
) {
    val bgColor = coverColors[book.id.hashCode().absoluteValue % coverColors.size]
    val art = rememberCoverArt(book.coverPath)
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onDelete,
                // TalkBack reads this out, so long press is no longer a gesture nobody is told about.
                onLongClickLabel = stringResource(R.string.delete_confirm),
            ),
    ) {
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
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleSmall,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = 4.dp),
                )
                // Sending and deleting from the library itself: before, sending meant opening the
                // book, and deleting was a long press with nothing on screen to suggest it. The
                // button is nudged out so its icon lines up with the text inset, not its own.
                Box(Modifier.offset(x = 10.dp)) {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.card_options, book.title))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        when (book.syncStatus) {
                                            SyncStatus.SENT -> R.string.card_send_again
                                            SyncStatus.FAILED -> R.string.card_retry
                                            else -> R.string.reader_send
                                        },
                                    ),
                                )
                            },
                            enabled = book.syncStatus != SyncStatus.SENDING,
                            onClick = { showMenu = false; onSend() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.card_details)) },
                            onClick = { showMenu = false; onDetails() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_confirm)) },
                            onClick = { showMenu = false; onDelete() },
                        )
                    }
                }
            }
            Text(
                if (book.lastReadEpochMs > 0 || book.readProgress > 0f) stringResource(R.string.list_progress, (book.readProgress.coerceIn(0f, 1f) * 100).toInt()) else stringResource(R.string.list_new),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            if (book.readProgress > 0f) {
                LinearProgressIndicator(
                    progress = { book.readProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            }
            Text(
                text = statusText(book),
                color = if (book.syncStatus == SyncStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.syncStatus == SyncStatus.FAILED) {
                TextButton(onClick = onSend) { Text(stringResource(R.string.reader_retry)) }
            }
        }
    }
}

@Composable
private fun statusText(book: Book): String = when (book.syncStatus) {
    SyncStatus.NOT_SENT -> stringResource(R.string.status_not_sent)
    SyncStatus.SENDING -> stringResource(R.string.status_sending)
    SyncStatus.SENT -> stringResource(R.string.status_sent)
    SyncStatus.FAILED -> book.syncMessage?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.status_failed_reason, it) }
        ?: stringResource(R.string.status_failed)
}

/**
 * Cover art from an epub, decoded off the main thread and no larger than the cell that shows it.
 * A cover as it comes in an epub is often 1600 by 2400, fifteen megabytes once decoded; a grid of
 * those decoded during composition stalled scrolling and could run the heap out. Books without a
 * cover keep the coloured block.
 */
@Composable
private fun rememberCoverArt(path: String?): ImageBitmap? {
    if (path == null) return null
    val targetWidth = with(LocalDensity.current) { COVER_DECODE_WIDTH.roundToPx() }
    val art by produceState(initialValue = CoverArt.cached(path), path, targetWidth) {
        value = CoverArt.load(path, targetWidth)
    }
    return art
}

/** Wider than any grid cell on a phone, so a cover is only ever scaled down from this. */
private val COVER_DECODE_WIDTH = 240.dp

private object CoverArt {
    private val cache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun cached(path: String): ImageBitmap? = cache.get(path)

    suspend fun load(path: String, targetWidth: Int): ImageBitmap? {
        cache.get(path)?.let { return it }
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
        if (decoded != null) cache.put(path, decoded)
        return decoded
    }
}
