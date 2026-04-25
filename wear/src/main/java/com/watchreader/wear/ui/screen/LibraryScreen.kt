package com.watchreader.wear.ui.screen

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.items
import androidx.wear.compose.material.rememberScalingLazyListState
import com.watchreader.wear.data.model.WearBook
import com.watchreader.wear.ui.theme.DimText
import com.watchreader.wear.ui.theme.WarmAmber
import com.watchreader.wear.ui.theme.WarmWhite
import com.watchreader.wear.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    onBookClick: (String) -> Unit,
    onSettings: () -> Unit,
    vm: LibraryViewModel = viewModel(),
) {
    val books by vm.books.collectAsState()
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { event ->
                coroutineScope.launch { listState.scrollBy(event.verticalScrollPixels) }
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        Scaffold(
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        ) {
            if (books.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No books", fontSize = 16.sp, color = WarmWhite)
                        Spacer(Modifier.height(4.dp))
                        Text("Add from phone", fontSize = 12.sp, color = DimText)
                    }
                }
            } else {
                ScalingLazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        Text(
                            text = "Library",
                            fontSize = 14.sp,
                            color = WarmAmber,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    items(books, key = { it.id }) { book ->
                        BookChip(book = book, onClick = { onBookClick(book.id) })
                    }
                    item {
                        Chip(
                            onClick = onSettings,
                            label = { Text("Settings") },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookChip(book: WearBook, onClick: () -> Unit) {
    val progress = if (book.totalChars > 0) {
        book.readOffsetChars.toFloat() / book.totalChars
    } else 0f

    Chip(
        onClick = onClick,
        label = {
            Text(
                text = book.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        secondaryLabel = {
            if (progress > 0f) {
                Text(
                    text = "${(progress * 100).toInt()}%",
                    fontSize = 10.sp,
                    color = DimText,
                )
            } else {
                Text("New", fontSize = 10.sp, color = DimText)
            }
        },
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}
