package com.watchreader.mobile.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.watchreader.mobile.R
import com.watchreader.mobile.highlight.Highlight
import com.watchreader.mobile.highlight.TextIntents
import com.watchreader.mobile.ui.viewmodel.HighlightFailure
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Everything the reader does with selected words or a highlight, over the page's text: the
 * handles and toolbar of a selection, the menu of a tapped highlight, the note dialog, and the
 * messages that follow. It goes in the page's box after the text. [bookTitle] goes with words
 * shared to another app.
 */
@Composable
internal fun ReaderMarksOverlay(marks: ReaderMarks, bookTitle: String?, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm = marks.highlights
    val state by vm.state.collectAsState()
    val lastColor by vm.lastColor.collectAsState()
    val say: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }

    LaunchedEffect(vm) {
        vm.failures.collect { failure ->
            snackbar.showSnackbar(
                context.getString(if (failure == HighlightFailure.LOAD) R.string.highlights_load_error else R.string.highlight_save_error),
            )
        }
    }
    LaunchedEffect(vm) {
        vm.deleted.collect { gone ->
            val result = snackbar.showSnackbar(
                message = context.getString(R.string.highlight_deleted),
                actionLabel = context.getString(R.string.highlight_undo),
                withDismissAction = true,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) vm.restore(gone)
        }
    }

    // Back lets the selection go before it leaves the book.
    BackHandler(enabled = marks.active) { marks.clear() }

    val copy: (String) -> Unit = { text ->
        if (!TextIntents.copy(context, context.getString(R.string.highlight_clip_label), text)) {
            say(context.getString(R.string.highlight_too_much_text))
        } else if (TextIntents.needsCopyConfirmation) {
            say(context.getString(R.string.highlight_copied))
        }
    }

    SelectionControls(
        marks = marks,
        lastColor = lastColor,
        actions = SelectionActions(
            highlight = { color ->
                val span = marks.selection
                val text = marks.selectedText
                if (span != null && text != null) {
                    if (color != null) vm.add(span.start, span.end, text, color) else vm.add(span.start, span.end, text)
                    marks.clear()
                }
            },
            note = {
                val span = marks.selection
                val text = marks.selectedText
                if (span != null && text != null) marks.noteEditor = NoteEditor(null, span, text, "")
            },
            lookUp = {
                marks.selectedText?.let { text ->
                    if (!TextIntents.lookUp(context, text.trim(), context.getString(R.string.highlight_look_up_with))) {
                        say(context.getString(R.string.highlight_no_look_up))
                    }
                }
            },
            translate = {
                marks.selectedText?.let { text ->
                    if (!TextIntents.translate(context, text.trim(), context.getString(R.string.highlight_translate_with))) {
                        say(context.getString(R.string.highlight_no_translate))
                    }
                }
            },
            copy = {
                marks.selectedText?.let { copy(it) }
                marks.clear()
            },
            share = {
                marks.selectedText?.let {
                    if (!TextIntents.share(context, it.trim(), bookTitle, context.getString(R.string.highlight_share))) {
                        say(context.getString(R.string.highlight_too_much_text))
                    }
                }
                marks.clear()
            },
        ),
    )

    val opened = marks.opened
    val openedHighlight = opened?.let { o -> state.items.firstOrNull { it.id == o.id } }
    if (opened != null && openedHighlight != null) {
        HighlightMenu(
            highlight = openedHighlight,
            at = IntOffset(opened.at.x.roundToInt(), opened.at.y.roundToInt()),
            onRecolor = { vm.recolor(openedHighlight.id, it); marks.opened = null },
            onNote = {
                marks.opened = null
                marks.noteEditor = NoteEditor(openedHighlight.id, null, openedHighlight.quote, openedHighlight.note)
            },
            onCopy = { copy(openedHighlight.quote); marks.opened = null },
            onDelete = { vm.delete(openedHighlight.id); marks.opened = null },
            onDismiss = { marks.opened = null },
        )
    }

    marks.noteEditor?.let { editor ->
        NoteDialog(
            editor = editor,
            onSave = { note ->
                val id = editor.highlightId
                val span = editor.span
                if (id != null) {
                    vm.annotate(id, note)
                } else if (span != null) {
                    vm.add(span.start, span.end, editor.quote, note = note)
                    marks.clear()
                }
                marks.noteEditor = null
            },
            onDismiss = { marks.noteEditor = null },
        )
    }
}

/**
 * A tapped highlight's menu, opened where the finger was: its note to read, its colour to change,
 * and the note to write or edit, the words to copy, the highlight to delete.
 */
@Composable
private fun HighlightMenu(
    highlight: Highlight,
    at: IntOffset,
    onRecolor: (com.watchreader.mobile.highlight.HighlightColor) -> Unit,
    onNote: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The menu hangs from an empty box at the tap, so it opens beside the highlight.
    Box(Modifier.offset { at }) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
            if (highlight.hasNote) {
                Text(
                    highlight.note,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            ColorRow(selected = highlight.color, onPick = onRecolor, modifier = Modifier.padding(horizontal = 8.dp))
            DropdownMenuItem(
                text = { Text(stringResource(if (highlight.hasNote) R.string.highlight_edit_note else R.string.highlight_note_action)) },
                leadingIcon = { Icon(Icons.Filled.EditNote, contentDescription = null) },
                onClick = onNote,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.highlight_copy)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                onClick = onCopy,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.highlight_delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = onDelete,
            )
        }
    }
}

/**
 * Writing a note, with the words it is about above it. A new note on selected words makes them a
 * highlight in the last colour; clearing an existing note leaves its highlight. Only Cancel or
 * Back puts it away: a stray tap beside the dialog would throw the draft away unasked.
 */
@Composable
private fun NoteDialog(editor: NoteEditor, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(editor.highlightId, editor.span?.start, editor.span?.end) { mutableStateOf(editor.initial) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(if (editor.initial.isBlank()) R.string.highlight_note_action else R.string.highlight_edit_note)) },
        text = {
            Column {
                Text(
                    editor.quote.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.highlight_note_label)) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(focus),
                )
            }
        },
        confirmButton = {
            // An empty note on new words would be a plain highlight by a longer way round.
            TextButton(onClick = { onSave(text) }, enabled = editor.highlightId != null || text.isNotBlank()) {
                Text(stringResource(R.string.highlight_note_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.highlight_note_cancel)) } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}
