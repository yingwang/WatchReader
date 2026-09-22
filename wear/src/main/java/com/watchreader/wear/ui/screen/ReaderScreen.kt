package com.watchreader.wear.ui.screen

import android.Manifest
import android.app.Activity
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.InlineSliderDefaults
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.dialog.Dialog
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.watchreader.shared.Chapter
import com.watchreader.wear.ui.theme.BlueAccent
import com.watchreader.wear.ui.theme.ListRowBg
import com.watchreader.wear.ui.theme.ListRowText
import com.watchreader.wear.R
import com.watchreader.shared.reader.LineMeasurer
import com.watchreader.wear.reader.PageMargins
import com.watchreader.wear.reader.Typefaces
import com.watchreader.shared.reader.Paginator
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
    val marginTopBottom = remember { prefs.marginTopBottom }
    val marginSides = remember { prefs.marginSides }
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
    var showHint by remember { mutableStateOf(!prefs.readerHintSeen) }
    var crownTravel by remember { mutableFloatStateOf(0f) }
    var autoTurn by remember { mutableStateOf(prefs.autoTurnEnabled) }
    var autoSeconds by remember { mutableFloatStateOf(prefs.autoTurnSeconds) }
    val focusRequester = remember { FocusRequester() }
    val lifecycleOwner = LocalLifecycleOwner.current
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

    BackHandler(enabled = showToolbar) { showToolbar = false }

    // Auto page turn: the page stays for the chosen seconds, then moves on. The wait lives
    // inside repeatOnLifecycle, so it stops the moment the watch leaves the page (screen off,
    // Home, another app) and starts afresh on the way back; otherwise a book left open would
    // keep turning in the dark and overwrite the reading position on the phone.
    LaunchedEffect(autoTurn, autoSeconds, state, showToolbar) {
        val s = state
        if (!autoTurn || showToolbar || s !is ReaderUiState.Ready) return@LaunchedEffect
        if (s.atEnd) { autoTurn = false; return@LaunchedEffect }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay((autoSeconds * 1000).toLong())
            if (autoTurn && !showToolbar) vm.nextPage()
        }
    }
    // Reading aloud turns pages by itself, so the two never run together: starting a voice
    // switches auto page turn off and remembers that, which is also what the switch then shows.
    // Turning it back on afterwards is the reader's call, not something that happens by itself.
    LaunchedEffect(ttsHere, ttsState) {
        if (ttsHere && ttsState == TtsState.PLAYING && autoTurn) {
            autoTurn = false
            prefs.autoTurnEnabled = false
        }
    }
    // The seconds a page stays can be changed in Settings while this screen sits in the back
    // stack, so read it again every time the page comes forward.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) autoSeconds = prefs.autoTurnSeconds
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val previousLabel = stringResource(R.string.reader_previous_page)
    val nextLabel = stringResource(R.string.reader_next_page)
    val controlsLabel = stringResource(R.string.reader_controls)

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
                if (showToolbar || showHint) return@onRotaryScrollEvent false
                // The crown turns pages, and it does so whether or not they are also turning by
                // themselves. It used to set the pace while auto page turn was on, which quietly
                // took the crown away from the one thing a reader reaches for it to do: a turn of
                // it went unanswered, and the pace ran down to its shortest stop without being
                // asked. The pace belongs in Settings, where it can be seen while it is changed.
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
            .pointerInput(showToolbar, showHint) {
                detectTapGestures(
                    // Nothing but a page turn on a plain tap: the toolbar was too easy to hit.
                    onTap = { offset ->
                        if (showToolbar || showHint) return@detectTapGestures
                        if (offset.x < size.width / 2f) vm.prevPage() else vm.nextPage()
                        tick()
                    },
                    onLongPress = {
                        if (showToolbar || showHint) return@detectTapGestures
                        showToolbar = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    },
                )
            },
    ) {
        // The toolbar's list takes rotary focus while it is up and clears it on the way out, so the
        // page asks for it back every time the toolbar closes, not only on first composition.
        LaunchedEffect(showToolbar) { if (!showToolbar) focusRequester.requestFocus() }

        // One left-aligned block, inset from the bezel on a round screen.
        val screenWpx = with(density) { maxWidth.roundToPx() }
        val screenHpx = with(density) { maxHeight.roundToPx() }
        val lineHeightPx = with(density) { (fontSize * 1.4f).sp.toPx() }
        val geometry = remember(screenWpx, screenHpx, lineHeightPx, isRound, marginTopBottom, marginSides) {
            PageMargins.geometry(screenWpx, screenHpx, isRound, lineHeightPx, density.density, marginTopBottom, marginSides)
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
                Canvas(modifier = Modifier.fillMaxSize().semantics {
                    contentDescription = page.lines.joinToString(" ") { lineString(page, it, null).orEmpty() }
                    customActions = listOf(
                        CustomAccessibilityAction(previousLabel) { vm.prevPage(); true },
                        CustomAccessibilityAction(nextLabel) { vm.nextPage(); true },
                        CustomAccessibilityAction(controlsLabel) { showToolbar = true; true },
                    )
                }) {
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
                } else if (autoTurn) {
                    Text(
                        text = stringResource(R.string.reader_auto_turn_interval),
                        color = colors.dim,
                        fontSize = 9.sp,
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
                        autoTurn = autoTurn,
                        onAutoTurnChange = { on ->
                            if (on && ttsHere) TtsService.stop(context)
                            autoTurn = on
                            prefs.autoTurnEnabled = on
                            tick()
                        },
                        background = colors.background,
                        textColor = colors.text,
                        chapters = s.chapters,
                        currentOffset = page.start,
                        onChapter = { chapter ->
                            if (ttsHere) TtsService.stop(context)
                            vm.jumpToChapter(chapter)
                            showToolbar = false
                            tick()
                        },
                        onClose = { showToolbar = false },
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
                            if (ttsHere) TtsService.stop(context)
                            vm.jumpToFraction(f)
                            tick()
                        },
                    )
                }
            }
        }
    }

    Dialog(showDialog = showHint && state is ReaderUiState.Ready, onDismissRequest = {
        showHint = false
        prefs.readerHintSeen = true
    }) {
        Alert(title = { Text(stringResource(R.string.reader_hint), fontSize = 14.sp, textAlign = TextAlign.Center) }) {
            item {
                Chip(onClick = { showHint = false; prefs.readerHintSeen = true }, label = { Text(stringResource(R.string.reader_got_it)) })
            }
        }
    }
}

@Composable
private fun Toolbar(
    fraction: Float,
    ttsHere: Boolean,
    ttsState: TtsState,
    autoTurn: Boolean,
    onAutoTurnChange: (Boolean) -> Unit,
    background: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    chapters: List<Chapter>,
    currentOffset: Int,
    onChapter: (Chapter) -> Unit,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onJump: (Float) -> Unit,
) {
    var contents by remember { mutableStateOf(false) }
    val listState = rememberScalingLazyListState()
    val chapterState = rememberScalingLazyListState(initialCenterItemIndex = (chapters.indexOfLast { it.start <= currentOffset } + 1).coerceAtLeast(1))
    val activeState = if (contents) chapterState else listState
    BackHandler { if (contents) contents = false else onClose() }
    Scaffold(
        modifier = Modifier.fillMaxSize().background(background),
        positionIndicator = { PositionIndicator(scalingLazyListState = activeState) },
    ) {
        ScalingLazyColumn(state = activeState, modifier = Modifier.fillMaxSize()) {
            if (contents) {
                item { Text(stringResource(R.string.reader_contents), color = textColor, fontSize = 14.sp) }
                if (chapters.isEmpty()) item {
                    Text(stringResource(R.string.reader_no_chapters), color = textColor, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.8f))
                }
                items(chapters) { chapter ->
                    Chip(
                        onClick = { onChapter(chapter) },
                        label = { Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        colors = ChipDefaults.chipColors(backgroundColor = ListRowBg, contentColor = ListRowText),
                        modifier = Modifier.fillMaxWidth(0.84f),
                    )
                }
                item { Chip(onClick = { contents = false }, label = { Text(stringResource(R.string.reader_controls)) }, colors = ChipDefaults.chipColors(backgroundColor = ListRowBg, contentColor = ListRowText), modifier = Modifier.fillMaxWidth(0.84f)) }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        val playing = ttsHere && ttsState == TtsState.PLAYING
                        TransportButton(
                            shape = if (playing) Transport.PAUSE else Transport.PLAY,
                            color = BlueAccent,
                            label = stringResource(if (playing) R.string.reader_pause else R.string.reader_play),
                            onClick = onPlayPause,
                        )
                        if (ttsHere) {
                            TransportButton(shape = Transport.STOP, color = textColor, label = stringResource(R.string.reader_stop), onClick = onStop)
                        }
                    }
                }
                item {
                    Text(
                        stringResource(if (ttsHere && ttsState == TtsState.PLAYING) R.string.reader_pause else R.string.reader_play),
                        color = textColor, fontSize = 12.sp,
                    )
                }
                item {
                    ToggleChip(
                        checked = autoTurn,
                        onCheckedChange = onAutoTurnChange,
                        label = { Text(stringResource(R.string.settings_auto_turn), fontSize = 14.sp) },
                        toggleControl = { Switch(checked = autoTurn) },
                        colors = ToggleChipDefaults.toggleChipColors(
                            checkedStartBackgroundColor = ListRowBg,
                            checkedEndBackgroundColor = ListRowBg,
                            uncheckedStartBackgroundColor = ListRowBg,
                            uncheckedEndBackgroundColor = ListRowBg,
                        ),
                        modifier = Modifier.fillMaxWidth(0.84f),
                    )
                }
                item {
                    Chip(
                        onClick = { contents = true },
                        label = { Text(stringResource(R.string.reader_contents)) },
                        colors = ChipDefaults.chipColors(backgroundColor = ListRowBg, contentColor = ListRowText),
                        modifier = Modifier.fillMaxWidth(0.84f),
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.reader_jump) + "  " + stringResource(R.string.reader_percent, (fraction * 100).roundToInt()),
                        color = textColor,
                        fontSize = 12.sp,
                    )
                }
                item {
                    // One notch is a twentieth of the book: the slider only has + and -, and
                    // crossing half a book should not take fifty presses.
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
            item {
                Chip(
                    onClick = onClose,
                    label = { Text(stringResource(R.string.reader_done)) },
                    colors = ChipDefaults.chipColors(backgroundColor = background, contentColor = textColor),
                    modifier = Modifier.fillMaxWidth(0.84f).heightIn(min = 48.dp),
                )
            }
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
private fun TransportButton(shape: Transport, color: androidx.compose.ui.graphics.Color, label: String, onClick: () -> Unit) {
    Canvas(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.22f))
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
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
