package com.watchreader.wear.ui.screen

import android.Manifest
import android.app.Activity
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.InlineSliderDefaults
import androidx.wear.compose.material.Text
import com.watchreader.wear.R
import com.watchreader.wear.reader.LineMeasurer
import com.watchreader.wear.reader.PageGeometry
import com.watchreader.wear.reader.Typefaces
import com.watchreader.wear.reader.Paginator
import com.watchreader.wear.service.TtsService
import com.watchreader.wear.settings.ReaderPrefs
import com.watchreader.wear.tts.TtsPlayback
import com.watchreader.wear.tts.TtsState
import com.watchreader.wear.ui.theme.pageColors
import com.watchreader.wear.ui.viewmodel.ReaderUiState
import com.watchreader.wear.ui.viewmodel.ReaderViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlin.math.roundToInt

/** One page per crown notch; the raw stream is far finer than that. */
private const val CROWN_PIXELS_PER_PAGE = 120f

@Composable
fun ReaderScreen(
    bookId: String,
    vm: ReaderViewModel = viewModel(
        key = bookId,
        factory = ReaderViewModel.Factory(LocalContext.current.applicationContext as android.app.Application, bookId),
    ),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val prefs = remember { ReaderPrefs(context) }
    val colors = remember { pageColors(prefs.theme) }
    val fontSize = remember { prefs.fontSize }
    val fontFamily = remember { Typefaces.familyFor(prefs.fontFamily) }
    val textStyle = remember(fontSize, fontFamily) {
        TextStyle(
            color = colors.text,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.4f).sp,
            fontFamily = fontFamily,
            textAlign = TextAlign.Start,
        )
    }

    val state by vm.state.collectAsState()
    val ttsState by TtsPlayback.state.collectAsState()
    val ttsBook by TtsPlayback.bookId.collectAsState()
    val spoken by TtsPlayback.sentence.collectAsState()
    val ttsHere = ttsBook == bookId && ttsState != TtsState.IDLE
    val isRound = LocalConfiguration.current.isScreenRound

    var showToolbar by remember { mutableStateOf(false) }
    var crownTravel by remember { mutableFloatStateOf(0f) }
    val focusRequester = remember { FocusRequester() }
    val measurer = rememberTextMeasurer()

    // Keep the screen on while this page is up, if the user wants that.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        if (prefs.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Follow the voice: turn the page when the spoken sentence leaves it.
    LaunchedEffect(bookId) {
        combine(TtsPlayback.bookId, TtsPlayback.sentence) { id, range -> if (id == bookId) range else null }
            .collect { range -> if (range != null) vm.followSpoken(range.first) }
    }

    LaunchedEffect(showToolbar) {
        if (showToolbar) {
            delay(5000)
            showToolbar = false
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun startReadingAloud(offset: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.saveProgress(toPhone = false)
        TtsService.play(context, bookId, offset)
    }

    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .onRotaryScrollEvent { event ->
                // The crown reports a stream of small deltas; one page per notch, not per event.
                crownTravel += event.verticalScrollPixels
                while (crownTravel >= CROWN_PIXELS_PER_PAGE) {
                    crownTravel -= CROWN_PIXELS_PER_PAGE
                    vm.nextPage()
                    tick()
                }
                while (crownTravel <= -CROWN_PIXELS_PER_PAGE) {
                    crownTravel += CROWN_PIXELS_PER_PAGE
                    vm.prevPage()
                    tick()
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(
                    // Nothing but a page turn on a plain tap: the toolbar was too easy to hit.
                    onTap = { offset ->
                        if (offset.x < size.width / 2f) vm.prevPage() else vm.nextPage()
                        tick()
                    },
                    onLongPress = {
                        showToolbar = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    },
                )
            },
    ) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        // One left-aligned block, inset from the bezel on a round screen.
        val screenWpx = with(density) { maxWidth.roundToPx() }
        val screenHpx = with(density) { maxHeight.roundToPx() }
        val lineHeightPx = with(density) { (fontSize * 1.4f).sp.toPx() }
        val geometry = remember(screenWpx, screenHpx, lineHeightPx, isRound) {
            if (isRound) {
                PageGeometry.round(minOf(screenWpx, screenHpx), marginPx = with(density) { 9.dp.toPx() }, lineHeightPx = lineHeightPx)
            } else {
                PageGeometry.rect(screenWpx, screenHpx, marginPx = with(density) { 12.dp.toPx() }, lineHeightPx = lineHeightPx)
            }
        }
        val lineMeasurer = remember(measurer, textStyle) {
            LineMeasurer { text, start, end, widthPx ->
                val result = measurer.measure(
                    text = AnnotatedString(text.substring(start, end)),
                    style = textStyle,
                    overflow = TextOverflow.Clip,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = widthPx),
                )
                result.getLineEnd(0, visibleEnd = false)
            }
        }
        LaunchedEffect(geometry, lineMeasurer) { vm.attachLayout(geometry, lineMeasurer) }

        when (val s = state) {
            ReaderUiState.Loading -> Text(
                stringResource(R.string.reader_loading),
                color = colors.dim,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center),
            )
            ReaderUiState.Missing -> Text(
                stringResource(R.string.reader_missing),
                color = colors.dim,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            is ReaderUiState.Ready -> {
                val highlight = if (ttsHere && ttsState == TtsState.PLAYING) spoken else null
                val page = s.page
                Canvas(modifier = Modifier.fillMaxSize()) {
                    for (line in page.lines) {
                        val slot = geometry.slots.getOrNull(line.slot) ?: continue
                        val lineText = lineString(page, line, vmText = null) ?: continue
                        val layout = measurer.measure(
                            text = highlighted(lineText, line.start, highlight, colors.highlight),
                            style = textStyle,
                            overflow = TextOverflow.Clip,
                            maxLines = 1,
                            constraints = Constraints(maxWidth = slot.width),
                        )
                        drawText(layout, topLeft = androidx.compose.ui.geometry.Offset(slot.left, slot.top))
                    }
                }

                // small percent at the bottom edge, inside the round bezel
                Text(
                    text = stringResource(R.string.reader_percent, (s.fraction * 100).roundToInt()),
                    color = colors.dim,
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                )
                if (ttsHere && ttsState == TtsState.PLAYING) {
                    Text(
                        text = "♪",
                        color = colors.dim,
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                    )
                }

                AnimatedVisibility(
                    visible = showToolbar,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Toolbar(
                        fraction = s.fraction,
                        ttsHere = ttsHere,
                        ttsState = ttsState,
                        background = colors.background,
                        textColor = colors.text,
                        dimColor = colors.dim,
                        onPlayPause = {
                            when {
                                ttsHere && ttsState == TtsState.PLAYING -> TtsService.pause(context)
                                ttsHere && ttsState == TtsState.PAUSED -> TtsService.resume(context)
                                else -> startReadingAloud(page.start)
                            }
                            showToolbar = false
                        },
                        onStop = {
                            TtsService.stop(context)
                            showToolbar = false
                        },
                        onJump = { f ->
                            vm.jumpToFraction(f)
                            tick()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Toolbar(
    fraction: Float,
    ttsHere: Boolean,
    ttsState: TtsState,
    background: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    dimColor: androidx.compose.ui.graphics.Color,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onJump: (Float) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val playing = ttsHere && ttsState == TtsState.PLAYING
                TransportButton(
                    shape = if (playing) Transport.PAUSE else Transport.PLAY,
                    color = textColor,
                    onClick = onPlayPause,
                )
                if (ttsHere) {
                    TransportButton(shape = Transport.STOP, color = textColor, onClick = onStop)
                }
            }
            Text(
                text = stringResource(R.string.reader_jump) + "  " + stringResource(R.string.reader_percent, (fraction * 100).roundToInt()),
                color = textColor,
                fontSize = 12.sp,
            )
            InlineSlider(
                value = (fraction * 20).roundToInt().toFloat(),
                onValueChange = { onJump(it / 20f) },
                valueRange = 0f..20f,
                steps = 19,
                increaseIcon = { Text("+", color = textColor, fontSize = 16.sp) },
                decreaseIcon = { Text("–", color = textColor, fontSize = 16.sp) },
                colors = InlineSliderDefaults.colors(),
                modifier = Modifier.fillMaxWidth(0.8f),
            )
        }
    }
}

private enum class Transport { PLAY, PAUSE, STOP }

/**
 * The transport controls are drawn rather than typed. The glyphs for pause and stop are emoji on
 * some system fonts and plain marks on others, so a typed toolbar comes out in two different
 * styles on the same watch.
 */
@Composable
private fun TransportButton(shape: Transport, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Canvas(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.22f))
            .clickable(onClick = onClick),
    ) {
        val w = size.width
        val mark = w * 0.36f
        val left = (w - mark) / 2f
        val top = (size.height - mark) / 2f
        when (shape) {
            Transport.PLAY -> drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(left + mark * 0.08f, top)
                    lineTo(left + mark * 1.02f, top + mark / 2f)
                    lineTo(left + mark * 0.08f, top + mark)
                    close()
                },
                color,
            )
            Transport.PAUSE -> {
                val bar = mark * 0.32f
                drawRect(color, topLeft = Offset(left, top), size = Size(bar, mark))
                drawRect(color, topLeft = Offset(left + mark - bar, top), size = Size(bar, mark))
            }
            Transport.STOP -> drawRect(color, topLeft = Offset(left, top), size = Size(mark, mark))
        }
    }
}

/** The characters of one line; the page carries them so the screen never holds the whole book. */
private fun lineString(page: Paginator.Page, line: Paginator.Line, vmText: String?): String? =
    page.text(line)

private fun highlighted(
    lineText: String,
    lineStart: Int,
    spoken: IntRange?,
    color: androidx.compose.ui.graphics.Color,
): AnnotatedString {
    if (spoken == null) return AnnotatedString(lineText)
    val start = (spoken.first - lineStart).coerceIn(0, lineText.length)
    val end = (spoken.last + 1 - lineStart).coerceIn(0, lineText.length)
    if (end <= start) return AnnotatedString(lineText)
    return buildAnnotatedString {
        append(lineText)
        addStyle(SpanStyle(background = color), start, end)
    }
}
