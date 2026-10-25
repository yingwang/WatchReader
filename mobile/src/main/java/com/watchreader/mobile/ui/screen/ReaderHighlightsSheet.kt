package com.watchreader.mobile.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.watchreader.mobile.R
import com.watchreader.mobile.highlight.Highlight
import com.watchreader.mobile.highlight.HighlightChapters
import com.watchreader.mobile.highlight.HighlightMarkdown
import com.watchreader.mobile.highlight.TextIntents
import com.watchreader.mobile.highlight.percentOf
import com.watchreader.mobile.ui.viewmodel.HighlightsUiState
import com.watchreader.shared.Chapter

/**
 * The book's highlights and notes in book order, each with its chapter and place. A tap goes there
 * the way a bookmark does, so the title bar offers the way back. Export shares them all as
 * Markdown; Copy puts the same text on the clipboard. With TalkBack each row also offers its note
 * to write or edit, its words to copy and the highlight to delete, which otherwise only a tap on
 * the highlight on its page reaches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderHighlightsSheet(
    state: HighlightsUiState,
    chapters: List<Chapter>,
    totalChars: Int,
    bookTitle: String,
    author: String?,
    onJump: (Highlight) -> Unit,
    onEditNote: (Highlight) -> Unit,
    onDelete: (Highlight) -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val markdown = { HighlightMarkdown.build(bookTitle, author, state.items, chapters, totalChars) }
    val tooMuch = { onMessage(context.getString(R.string.highlight_too_much_text)) }
    val copy: (String, String) -> Unit = { label, text ->
        if (!TextIntents.copy(context, label, text)) tooMuch()
        else if (TextIntents.needsCopyConfirmation) onMessage(context.getString(R.string.highlight_copied))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.highlights_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                val any = state.items.isNotEmpty()
                TextButton(onClick = { copy(context.getString(R.string.highlights_title), markdown()) }, enabled = any) {
                    Text(stringResource(R.string.highlight_copy))
                }
                TextButton(onClick = {
                    if (!TextIntents.share(context, markdown(), bookTitle, context.getString(R.string.highlights_export))) tooMuch()
                }, enabled = any) { Text(stringResource(R.string.highlights_export)) }
            }
            when {
                state.loading -> CircularProgressIndicator(Modifier.padding(24.dp))
                state.loadFailed -> Text(stringResource(R.string.highlights_load_error), modifier = Modifier.padding(24.dp))
                state.items.isEmpty() -> Text(stringResource(R.string.highlights_empty), modifier = Modifier.padding(24.dp))
            }
            LazyColumn(Modifier.weight(1f)) {
                items(state.items, key = { it.id }) { highlight ->
                    HighlightRow(
                        highlight,
                        chapters,
                        totalChars,
                        onClick = { onJump(highlight) },
                        onEditNote = { onEditNote(highlight) },
                        onCopy = { copy(context.getString(R.string.highlight_clip_label), highlight.quote) },
                        onDelete = { onDelete(highlight) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HighlightRow(
    highlight: Highlight,
    chapters: List<Chapter>,
    totalChars: Int,
    onClick: () -> Unit,
    onEditNote: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val chapter = remember(highlight.start, chapters) { HighlightChapters.at(chapters, highlight.start)?.title }
    val percent = percentOf(highlight.start, totalChars)
    // A quote over a paragraph break reads as one run in a list.
    val quote = remember(highlight.quote) { highlight.quote.trim().replace(Regex("\\s+"), " ") }
    val noteLabel = stringResource(if (highlight.hasNote) R.string.highlight_edit_note else R.string.highlight_note_action)
    val copyLabel = stringResource(R.string.highlight_copy)
    val deleteLabel = stringResource(R.string.highlight_delete)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(enabled = highlight.start < totalChars, onClickLabel = stringResource(R.string.highlight_go_to), onClick = onClick)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(noteLabel) { onEditNote(); true },
                    CustomAccessibilityAction(copyLabel) { onCopy(); true },
                    CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                )
            }
            .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(highlight.color.solid),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                if (chapter != null) stringResource(R.string.highlight_position_chapter, chapter, percent)
                else stringResource(R.string.highlight_position, percent),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(quote, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (highlight.hasNote) {
                Text(
                    highlight.note.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
