package com.watchreader.wear.ui.screen

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.items
import androidx.wear.compose.material.rememberScalingLazyListState
import com.watchreader.wear.data.model.WearBook
import com.watchreader.wear.ui.WearActivity
import com.watchreader.wear.ui.theme.DimText
import com.watchreader.wear.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch

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
    val listState = rememberScalingLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current

    val activity = context as? WearActivity
    LaunchedEffect(Unit) {
        activity?.rotaryEvents?.collect { delta ->
            coroutineScope.launch { listState.scrollBy(-delta * 40f) }
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        ) {
            if (books.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No books", fontSize = 16.sp, color = ItemText)
                        Spacer(Modifier.height(4.dp))
                        Text("Add from phone", fontSize = 12.sp, color = SubText)
                    }
                }
            } else {
                ScalingLazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    autoCentering = null,
                ) {
                    item {
                        Text(
                            text = "Library",
                            fontSize = 14.sp,
                            color = TitleColor,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    items(books, key = { it.id }) { book ->
                        val progress = if (book.totalChars > 0) {
                            book.readOffsetChars.toFloat() / book.totalChars
                        } else 0f
                        SwipeToDeleteItem(
                            title = book.title.replace(".txt", "").replace(".epub", ""),
                            subtitle = if (progress > 0f) "${(progress * 100).toInt()}%" else "New",
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onBookClick(book.id)
                            },
                            onDelete = {
                                vm.deleteBook(book.id)
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            },
                        )
                    }
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.82f)
                                .clip(RoundedCornerShape(24.dp))
                                .background(ItemBg)
                                .clickable(onClick = onSettings)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text("Settings", color = ItemText, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeToDeleteItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val animatedOffset by animateFloatAsState(offsetX, label = "swipe")

    Box(
        modifier = Modifier
            .fillMaxWidth(0.82f)
            .clip(RoundedCornerShape(24.dp)),
    ) {
        // Delete background
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color(0xFF3A3A3A)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                "\u2715",
                color = Color(0xFFAAAAAA),
                fontSize = 14.sp,
                modifier = Modifier.padding(end = 18.dp),
            )
        }

        // Foreground item
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = animatedOffset }
                .clip(RoundedCornerShape(24.dp))
                .background(ItemBg)
                .clickable(onClick = onClick)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -size.width * 0.35f) onDelete()
                            offsetX = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            if (dragAmount < 0 || offsetX < 0) {
                                offsetX = (offsetX + dragAmount).coerceIn(-size.width * 0.6f, 0f)
                            }
                        },
                    )
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column {
                Text(
                    title,
                    color = ItemText,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(subtitle, fontSize = 10.sp, color = SubText)
            }
        }
    }
}
