package com.watchreader.wear.ui.screen

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog
import com.watchreader.wear.R
import com.watchreader.wear.data.model.WearBook
import com.watchreader.wear.tts.TtsPlayback
import com.watchreader.wear.tts.TtsState
import com.watchreader.wear.ui.viewmodel.LibraryViewModel

private val ItemBg = Color(0xFF1E1E1E)
private val ItemText = Color(0xFFD8D8D8)
private val SubText = Color(0xFF888888)
private val TitleColor = Color(0xFF9CB8A0)

@Composable
fun LibraryScreen(
    onBookClick: (String) -> Unit,
    onSettings: () -> Unit,
    vm: LibraryViewModel = viewModel(),
) {
    val books by vm.books.collectAsState()
    val ttsBook by TtsPlayback.bookId.collectAsState()
    val ttsState by TtsPlayback.state.collectAsState()
    val listState = rememberScalingLazyListState()
    val view = LocalView.current
    var deleteTarget by remember { mutableStateOf<WearBook?>(null) }

    Scaffold(
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        if (books.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.library_empty), fontSize = 16.sp, color = ItemText)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.library_empty_hint), fontSize = 12.sp, color = SubText)
                    Spacer(Modifier.height(14.dp))
                    SettingsRow(onSettings)
                }
            }
        } else {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.library_title),
                        fontSize = 14.sp,
                        color = TitleColor,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                items(books, key = { it.id }) { book ->
                    val listening = ttsBook == book.id && ttsState != TtsState.IDLE
                    val progress = if (book.totalChars > 0) book.readOffsetChars.toFloat() / book.totalChars else 0f
                    val subtitle = when {
                        listening -> stringResource(R.string.library_listening)
                        progress > 0f -> stringResource(R.string.library_progress, (progress * 100).toInt())
                        else -> stringResource(R.string.library_new)
                    }
                    BookRow(
                        title = book.title,
                        subtitle = subtitle,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onBookClick(book.id)
                        },
                        onLongClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            deleteTarget = book
                        },
                    )
                }
                item { SettingsRow(onSettings) }
            }
        }
    }

    Dialog(
        showDialog = deleteTarget != null,
        onDismissRequest = { deleteTarget = null },
    ) {
        val book = deleteTarget ?: return@Dialog
        Alert(
            title = {
                Text(
                    stringResource(R.string.library_delete_title, book.title),
                    color = ItemText,
                    fontSize = 13.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            negativeButton = {
                Button(onClick = { deleteTarget = null }, colors = ButtonDefaults.secondaryButtonColors()) {
                    Text(stringResource(R.string.library_delete_no), fontSize = 11.sp)
                }
            },
            positiveButton = {
                Button(
                    onClick = {
                        vm.deleteBook(book.id)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF8B3A3A)),
                ) {
                    Text(stringResource(R.string.library_delete_yes), fontSize = 11.sp)
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.84f)
            .clip(RoundedCornerShape(24.dp))
            .background(ItemBg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column {
            Text(title, color = ItemText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 10.sp, color = SubText)
        }
    }
}

@Composable
private fun SettingsRow(onSettings: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.84f)
            .clip(RoundedCornerShape(24.dp))
            .background(ItemBg)
            .clickable(onClick = onSettings)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(stringResource(R.string.library_settings), color = ItemText, fontSize = 14.sp)
    }
}
