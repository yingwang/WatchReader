package com.watchreader.wear.ui.screen

import android.app.Activity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.util.Log
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Text
import com.watchreader.wear.tts.SentenceParser
import com.watchreader.wear.tts.TtsState
import com.watchreader.wear.ui.theme.DimText
import com.watchreader.wear.ui.theme.GreenAccent
import com.watchreader.wear.ui.theme.WarmBlack
import com.watchreader.wear.ui.theme.WarmWhite
import com.watchreader.wear.ui.viewmodel.ReaderViewModel
import kotlinx.coroutines.delay

@Composable
fun ReaderScreen(
    bookId: String,
    vm: ReaderViewModel = viewModel(
        key = bookId,
        factory = ReaderViewModelFactory(LocalContext.current.applicationContext as android.app.Application, bookId),
    ),
) {
    val pages by vm.pages.collectAsState()
    val currentPage by vm.currentPage.collectAsState()
    val ttsState by vm.ttsState.collectAsState()
    val sentenceIndex by vm.currentSentenceIndex.collectAsState()
    val view = LocalView.current
    val context = LocalContext.current

    var showToolbar by remember { mutableStateOf(false) }
    var rotaryDelta by remember { mutableFloatStateOf(0f) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Log.d("WatchReader", "ReaderScreen: pages=${pages.size}, currentPage=$currentPage")

    // Keep screen on during TTS
    DisposableEffect(ttsState) {
        val window = (context as? Activity)?.window
        if (ttsState == TtsState.PLAYING) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Auto-hide toolbar
    LaunchedEffect(showToolbar) {
        if (showToolbar) {
            delay(4000)
            showToolbar = false
        }
    }

    val pageText = pages.getOrNull(currentPage) ?: ""
    val progress = vm.getProgress()

    // Build highlighted text
    val annotatedText = buildHighlightedText(pageText, sentenceIndex)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmBlack)
            .onRotaryScrollEvent { event ->
                rotaryDelta += event.verticalScrollPixels
                Log.d("WatchReader", "Rotary: delta=$rotaryDelta")
                if (rotaryDelta > 50f) {
                    vm.nextPage()
                    rotaryDelta = 0f
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                } else if (rotaryDelta < -50f) {
                    vm.prevPage()
                    rotaryDelta = 0f
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val width = size.width
                        Log.d("WatchReader", "Tap: x=${offset.x}, width=$width")
                        when {
                            offset.x < width / 3f -> {
                                Log.d("WatchReader", "Tap: prevPage")
                                vm.prevPage()
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            offset.x > width * 2f / 3f -> {
                                vm.nextPage()
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            else -> {
                                showToolbar = !showToolbar
                            }
                        }
                    },
                    onLongPress = { offset ->
                        val width = size.width
                        if (offset.x > width / 3f && offset.x < width * 2f / 3f) {
                            vm.toggleTts()
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                    },
                )
            },
    ) {
        // Main text content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            androidx.compose.foundation.text.BasicText(
                text = annotatedText,
                style = androidx.compose.ui.text.TextStyle(
                    color = WarmWhite,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    textAlign = TextAlign.Start,
                ),
                modifier = Modifier.weight(1f),
            )

            // Bottom: progress line + percentage
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(DimText.copy(alpha = 0.3f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(2.dp)
                        .background(DimText),
                )
            }
            Text(
                text = "${(progress * 100).toInt()}%",
                color = DimText,
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        // TTS playing indicator
        if (ttsState == TtsState.PLAYING) {
            Text(
                text = "\uD83D\uDD0A",
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 16.dp),
            )
        }

        // Toolbar overlay (Kindle style)
        AnimatedVisibility(
            visible = showToolbar,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(WarmBlack.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // TTS control
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        val ttsIcon = when (ttsState) {
                            TtsState.PLAYING -> "⏸"
                            else -> "▶"
                        }
                        Text(
                            text = ttsIcon,
                            fontSize = 24.sp,
                            color = WarmWhite,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(GreenAccent.copy(alpha = 0.3f))
                                .padding(12.dp)
                                .clickable {
                                    vm.toggleTts()
                                    showToolbar = false
                                },
                        )
                    }

                    // Page info
                    Text(
                        text = "${currentPage + 1} / ${pages.size}",
                        color = DimText,
                        fontSize = 12.sp,
                    )

                    // Speed
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Speed: ${vm.ttsManager.speechRate}x",
                            color = WarmWhite,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun buildHighlightedText(
    pageText: String,
    sentenceIndex: Int,
): AnnotatedString {
    if (sentenceIndex < 0) {
        return AnnotatedString(pageText)
    }
    val sentences = SentenceParser.splitWithRanges(pageText)
    val highlightRange = sentences.getOrNull(sentenceIndex)?.second

    return buildAnnotatedString {
        append(pageText)
        if (highlightRange != null && highlightRange.first < pageText.length) {
            val end = highlightRange.last.coerceAtMost(pageText.length - 1) + 1
            addStyle(
                SpanStyle(background = GreenAccent.copy(alpha = 0.3f)),
                start = highlightRange.first,
                end = end,
            )
        }
    }
}

// Factory for ViewModel with bookId parameter
class ReaderViewModelFactory(
    private val application: android.app.Application,
    private val bookId: String,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return ReaderViewModel(application, bookId) as T
    }
}
