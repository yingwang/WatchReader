package com.watchreader.mobile.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.watchreader.mobile.R
import com.watchreader.mobile.service.ReadAloudService
import com.watchreader.mobile.tts.ReadAloudPlayback
import com.watchreader.mobile.tts.ReadAloudState
import com.watchreader.mobile.tts.TtsLanguages
import com.watchreader.mobile.ui.viewmodel.BookListViewModel

/**
 * The slim player docked above the bottom bar while a book is read aloud and the reader is not on
 * screen: the book's cover and title, what the voice is doing, play or pause, and stop. Tapping it
 * opens the book where the voice has got to.
 *
 * It follows [ReadAloudPlayback] alone, which the service keeps and which outlives any screen: so
 * it is back as it was after a rotation, and it is gone as soon as the service is. After the
 * process has died there is no service and the state starts out idle, so there is no bar either.
 *
 * It slides in when reading aloud starts and away when it stops. With [padNavigationBar], for the
 * screens that have no navigation bar under it, it keeps itself clear of the system's navigation
 * the way that bar would have.
 */
@Composable
fun NowListeningBar(listVm: BookListViewModel, padNavigationBar: Boolean, onOpen: (String) -> Unit) {
    val state by ReadAloudPlayback.state.collectAsState()
    val bookId by ReadAloudPlayback.bookId.collectAsState()
    val active = state != ReadAloudState.IDLE && bookId != null
    // What the bar showed last, so it keeps its book and controls while it slides away. The values
    // are read here and handed down, so the bar redraws whenever they change.
    val last = remember { Shown() }
    if (active) {
        last.bookId = bookId
        last.state = state
    }
    val shownId = last.bookId
    val shownState = last.state
    AnimatedVisibility(
        visible = active,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    ) {
        if (shownId != null) Player(listVm, shownId, shownState, padNavigationBar, onOpen)
    }
}

/** Plain fields, not state: this bar recomposes on every change of the playback it copies them from. */
private class Shown {
    var bookId: String? = null
    var state: ReadAloudState = ReadAloudState.IDLE
}

@Composable
private fun Player(listVm: BookListViewModel, bookId: String, state: ReadAloudState, padNavigationBar: Boolean, onOpen: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val books by listVm.books.collectAsState()
    val book = books.orEmpty().firstOrNull { it.id == bookId }
    val chapter by ReadAloudPlayback.chapter.collectAsState()
    val fetching by ReadAloudPlayback.fetching.collectAsState()
    val waitingFor = fetching
    val status = when {
        state == ReadAloudState.LOADING -> stringResource(R.string.listen_loading)
        state == ReadAloudState.PAUSED -> chapter?.let { stringResource(R.string.nowplaying_paused_in, it) } ?: stringResource(R.string.listen_paused)
        waitingFor != null -> stringResource(R.string.listen_fetching, TtsLanguages.label(waitingFor))
        else -> chapter ?: stringResource(R.string.listen_reading)
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface) {
        // Clear of a navigation bar at the side in landscape, as the tabs are, and of the one at the
        // bottom where no tabs are under it.
        val sides = if (padNavigationBar) WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom else WindowInsetsSides.Horizontal
        Column(Modifier.windowInsetsPadding(WindowInsets.systemBars.only(sides))) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = stringResource(R.string.nowplaying_open)) { onOpen(bookId) }
                    .heightIn(min = 64.dp)
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (book != null) LibraryThumbnail(book, Modifier.width(32.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        book?.title.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Serif,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state == ReadAloudState.LOADING) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    val playing = state == ReadAloudState.PLAYING
                    IconButton(onClick = { if (playing) ReadAloudService.pause(context) else ReadAloudService.resume(context) }) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.listen_action_pause else R.string.listen_action_play),
                        )
                    }
                }
                IconButton(onClick = { ReadAloudService.stop(context) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.listen_action_stop))
                }
            }
        }
    }
}
