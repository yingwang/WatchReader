package com.watchreader.mobile.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.FreeBook
import com.watchreader.mobile.data.repository.GutenbergCatalog
import com.watchreader.mobile.ui.viewmodel.BookListViewModel.Pick
import com.watchreader.mobile.ui.viewmodel.BookListViewModel.Starters

/**
 * What a library with none of the reader's own books offers: a few free classics from Project
 * Gutenberg in the phone's language, chosen by hand for most languages, each added with one tap
 * and sent on to the watch, and the way into the rest of the free library. Where the classics
 * have to be looked up and there is no connection, the card shrinks to one quiet line, since a
 * new reader's library is no place for a large error.
 */
@Composable
fun StarterCard(
    starters: Starters,
    pick: Pick?,
    onPick: (FreeBook) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (starters == Starters.Unavailable) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.starter_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.free_retry)) }
        }
        return
    }
    Card(
        modifier = modifier,
        // The Add book cards' own shade and serif heading: off the warm page enough to read as a
        // card, with the covers' placeholder a shade darker still.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
            Text(
                stringResource(R.string.starter_title),
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Serif,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
            )
            when (starters) {
                // About the height of the picks, so the card does not jump when they come.
                Starters.Loading -> Box(Modifier.fillMaxWidth().height(PICKS_HEIGHT), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
                is Starters.Ready -> {
                    starters.picks.forEach { book -> PickRow(book, pick, onPick) }
                    // A tap adds the book without passing the free library's note, so it is here too.
                    Text(
                        stringResource(R.string.free_legal),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 4.dp),
                    )
                }
                Starters.Unavailable -> {}
            }
            // The button's own inset lines its text up with the title's.
            TextButton(onClick = onMore, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(stringResource(R.string.starter_more))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

/**
 * One classic: its cover, title and author, added with a tap anywhere on the row. While it
 * downloads, the author makes way for a note and the plus for a spinner; the other picks wait,
 * as one book is fetched at a time. A download that fails says why under the row it was for.
 */
@Composable
private fun PickRow(book: FreeBook, pick: Pick?, onPick: (FreeBook) -> Unit) {
    val adding = pick is Pick.Adding && pick.bookId == book.id
    val failed = (pick as? Pick.Failed)?.takeIf { it.bookId == book.id }
    val enabled = pick !is Pick.Adding
    Column(Modifier.alpha(if (enabled || adding) 1f else 0.5f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClickLabel = stringResource(R.string.free_add)) { onPick(book) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(GutenbergCatalog.coverUrl(book.id, small = true), width = COVER_WIDTH)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (adding) stringResource(R.string.free_adding) else authorOf(book),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (adding) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                } else {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        failed?.let {
            Text(
                it.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp + COVER_WIDTH + 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
}

private val COVER_WIDTH = 44.dp

/** Three rows of a 44 by 62 cover with 8 above and below each. */
private val PICKS_HEIGHT = 234.dp
