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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.Book
import com.watchreader.mobile.data.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.watchreader.mobile.ui.library.LibraryReadingState


private val coverColors = listOf(
    Color(0xFF8B6E4E), Color(0xFF6B7B5E), Color(0xFF7B6B8A), Color(0xFF5E7B8B),
    Color(0xFF8B5E5E), Color(0xFF5E8B7B), Color(0xFF7B7B5E), Color(0xFF6B5E8B),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookCover(
    book: Book,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onSend: () -> Unit,
    onDetails: () -> Unit,
    onCancelSend: () -> Unit,
    readingState: LibraryReadingState,
) {
    val bgColor = coverColors[(book.id.hashCode() and Int.MAX_VALUE) % coverColors.size]
    val art = rememberCoverArt(book.coverPath)

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
                    contentDescription = null,
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
                LibraryBookMenu(
                    book = book,
                    onSend = onSend,
                    onCancelSend = onCancelSend,
                    onDetails = onDetails,
                    onDelete = onDelete,
                    modifier = Modifier.offset(x = 10.dp),
                )
            }
            Text(
                readingText(book, readingState),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            if (readingState != LibraryReadingState.NOT_STARTED) {
                LinearProgressIndicator(
                    progress = { book.libraryProgress(readingState) },
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
internal fun ContinueReadingCard(book: Book, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // A slim banner: one title line, with how far along it is at the end.
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryThumbnail(book, Modifier.width(30.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.list_continue), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(book.title, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Serif, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(readingText(book, LibraryReadingState.READING), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CompactBookRow(
    book: Book,
    readingState: LibraryReadingState,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onSend: () -> Unit,
    onCancelSend: () -> Unit,
    onDetails: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onDelete,
                onLongClickLabel = stringResource(R.string.delete_confirm),
            )
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryThumbnail(book, Modifier.width(44.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Serif, maxLines = 2, overflow = TextOverflow.Ellipsis)
            book.author?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(readingText(book, readingState), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                statusText(book),
                style = MaterialTheme.typography.bodySmall,
                color = if (book.syncStatus == SyncStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LibraryBookMenu(book, onSend, onCancelSend, onDetails, onDelete)
    }
}

@Composable
private fun LibraryThumbnail(book: Book, modifier: Modifier = Modifier) {
    val art = rememberCoverArt(book.coverPath)
    Box(
        modifier = modifier.aspectRatio(0.7f).clip(RoundedCornerShape(4.dp))
            .background(coverColors[(book.id.hashCode() and Int.MAX_VALUE) % coverColors.size]),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(bitmap = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                book.title,
                modifier = Modifier.padding(4.dp),
                color = Color.White.copy(alpha = 0.92f),
                fontFamily = FontFamily.Serif,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LibraryBookMenu(
    book: Book,
    onSend: () -> Unit,
    onCancelSend: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { showMenu = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.card_options, book.title))
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            if (book.syncStatus == SyncStatus.QUEUED) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_cancel_send)) },
                    onClick = { showMenu = false; onCancelSend() },
                )
            } else {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(when (book.syncStatus) {
                            SyncStatus.SENT -> R.string.card_send_again
                            SyncStatus.FAILED -> R.string.card_retry
                            else -> R.string.reader_send
                        }))
                    },
                    enabled = book.syncStatus != SyncStatus.SENDING,
                    onClick = { showMenu = false; onSend() },
                )
            }
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

@Composable
private fun readingText(book: Book, state: LibraryReadingState): String = when (state) {
    LibraryReadingState.NOT_STARTED -> stringResource(R.string.list_new)
    LibraryReadingState.READING -> stringResource(R.string.list_progress, (book.libraryProgress(state) * 100).toInt())
    LibraryReadingState.FINISHED -> stringResource(R.string.library_filter_finished)
}

private fun Book.libraryProgress(state: LibraryReadingState): Float =
    if (state == LibraryReadingState.FINISHED) 1f else readProgress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

@Composable
private fun statusText(book: Book): String = when (book.syncStatus) {
    SyncStatus.QUEUED -> stringResource(R.string.library_status_queued)
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
