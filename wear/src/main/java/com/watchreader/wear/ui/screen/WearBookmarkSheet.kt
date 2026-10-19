package com.watchreader.wear.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.watchreader.shared.bookmark.BookmarkError
import com.watchreader.shared.bookmark.ReaderBookmarksState
import com.watchreader.wear.R

@Composable
internal fun WearBookmarkSheet(
    state: ReaderBookmarksState,
    pageStart: Int,
    pageEnd: Int,
    totalChars: Int,
    background: Color,
    textColor: Color,
    onToggle: () -> Unit,
    onJump: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val list = rememberScalingLazyListState()
    var deleting by remember { mutableStateOf(false) }
    val colors = ChipDefaults.chipColors(backgroundColor = textColor.copy(alpha = 0.1f).compositeOver(background), contentColor = textColor)
    BackHandler { onClose() }
    Scaffold(modifier = Modifier.fillMaxSize().background(background), positionIndicator = { PositionIndicator(scalingLazyListState = list) }) {
        ScalingLazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            item { Text(stringResource(R.string.reader_bookmarks), color = textColor, fontSize = 14.sp) }
            item {
                Chip(
                    onClick = onToggle,
                    enabled = !state.loading && !state.saving && state.error != BookmarkError.LOAD && pageStart < totalChars,
                    label = { Text(stringResource(if (state.onPage(pageStart, pageEnd) == null) R.string.reader_bookmark_add else R.string.reader_bookmark_remove)) },
                    colors = colors, modifier = Modifier.fillMaxWidth(0.86f),
                )
            }
            if (state.error != null) item {
                Text(stringResource(if (state.error == BookmarkError.LOAD) R.string.reader_bookmark_load_error else R.string.reader_bookmark_save_error),
                    color = textColor, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.8f))
            }
            if (state.error == BookmarkError.LOAD) item {
                Chip(onClick = onRetry, label = { Text(stringResource(R.string.reader_bookmark_retry)) }, colors = colors, modifier = Modifier.fillMaxWidth(0.86f))
            }
            if (!state.loading && state.items.isEmpty()) item {
                Text(stringResource(R.string.reader_bookmark_empty), color = textColor, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.8f))
            }
            items(state.items, key = { it.offset }) { bookmark ->
                Chip(
                    onClick = { onJump(bookmark.offset) },
                    enabled = bookmark.offset < totalChars,
                    label = { Text(stringResource(R.string.reader_bookmark_position, (bookmark.offset.toLong() * 100 / totalChars.coerceAtLeast(1)).toInt())) },
                    secondaryLabel = { Text(bookmark.excerpt, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    colors = colors, modifier = Modifier.fillMaxWidth(0.86f),
                )
            }
            if (state.items.isNotEmpty()) item {
                Chip(onClick = { deleting = !deleting }, label = { Text(stringResource(R.string.reader_bookmark_manage)) }, colors = colors, modifier = Modifier.fillMaxWidth(0.86f))
            }
            if (deleting) items(state.items, key = { "delete:${it.offset}" }) { bookmark ->
                Chip(onClick = { onDelete(bookmark.offset) }, enabled = !state.saving,
                    label = { Text(stringResource(R.string.reader_bookmark_delete, (bookmark.offset.toLong() * 100 / totalChars.coerceAtLeast(1)).toInt())) },
                    secondaryLabel = { Text(bookmark.excerpt, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    colors = colors, modifier = Modifier.fillMaxWidth(0.86f))
            }
            item { Chip(onClick = onClose, label = { Text(stringResource(R.string.reader_done)) }, colors = colors, modifier = Modifier.fillMaxWidth(0.86f)) }
        }
    }
}
