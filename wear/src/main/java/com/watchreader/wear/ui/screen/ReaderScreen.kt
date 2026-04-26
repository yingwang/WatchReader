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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Text
import com.watchreader.wear.tts.SentenceParser
import com.watchreader.wear.tts.TtsState
import com.watchreader.wear.ui.WearActivity
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
    val fontSize = remember { loadFontSize(context) }
    val fontFamily = remember {
        when (loadFontFamily(context)) {
            "serif" -> FontFamily.Serif
            "mono" -> FontFamily.Monospace
            else -> FontFamily.SansSerif
        }
    }

    // Calculate chars per page based on font size
    LaunchedEffect(fontSize) {
        val cpp = (90 - (fontSize - 12) * 3.5).toInt().coerceIn(35, 90)
        vm.setCharsPerPage(cpp)
    }

    // Keep screen on during TTS
    DisposableEffect(ttsState) {
        val window = (context as? Activity)?.window
        if (ttsState == TtsState.PLAYING) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Rotary crown via Activity-level override
    val activity = context as? WearActivity
    LaunchedEffect(Unit) {
        var lastFlip = 0L
        activity?.rotaryEvents?.collect { delta ->
            val now = System.currentTimeMillis()
            if (now - lastFlip > 250) {
                if (delta < 0f) {
                    vm.nextPage()
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                } else if (delta > 0f) {
                    vm.prevPage()
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                lastFlip = now
            }
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
    val activeHighlight = if (ttsState == TtsState.PLAYING) sentenceIndex else -1
    val annotatedText = buildHighlightedText(pageText, activeHighlight)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmBlack)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val width = size.width
                        when {
                            offset.x < width / 3f -> {
                                vm.prevPage()
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            offset.x > width * 2f / 3f -> {
                                vm.nextPage()
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            else -> showToolbar = !showToolbar
                        }
                    },
                    onLongPress = { _ ->
                        showToolbar = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    },
                )
            },
    ) {
        // Main text - centered for round screen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 34.dp),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.text.BasicText(
                text = annotatedText,
                style = androidx.compose.ui.text.TextStyle(
                    color = WarmWhite,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.5f).sp,
                    fontFamily = fontFamily,
                    textAlign = TextAlign.Start,
                ),
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // TTS indicator
        if (ttsState == TtsState.PLAYING) {
            Text(
                text = "\uD83D\uDD0A",
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 16.dp),
            )
        }

        // Toolbar overlay
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        val ttsIcon = if (ttsState == TtsState.PLAYING) "\u23F8" else "\u25B6"
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
                    Text(
                        text = "${currentPage + 1} / ${pages.size}",
                        color = DimText,
                        fontSize = 12.sp,
                    )
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
    if (sentenceIndex < 0) return AnnotatedString(pageText)
    val sentences = SentenceParser.splitWithRanges(pageText)
    val range = sentences.getOrNull(sentenceIndex)?.second
    return buildAnnotatedString {
        append(pageText)
        if (range != null && range.first < pageText.length) {
            addStyle(
                SpanStyle(background = GreenAccent.copy(alpha = 0.3f)),
                start = range.first,
                end = (range.last + 1).coerceAtMost(pageText.length),
            )
        }
    }
}

class ReaderViewModelFactory(
    private val application: android.app.Application,
    private val bookId: String,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return ReaderViewModel(application, bookId) as T
    }
}
