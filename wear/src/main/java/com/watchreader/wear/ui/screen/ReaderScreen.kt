package com.watchreader.wear.ui.screen

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.InlineSliderDefaults
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CompactChip
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
import com.watchreader.wear.tts.TtsLanguages
import com.watchreader.wear.tts.SleepTimer
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

/** [onAppearance] opens the text size and layout settings, from which Back returns to this page. */
@Composable
fun ReaderScreen(
    bookId: String,
    onAppearance: () -> Unit = {},
    vm: ReaderViewModel = viewModel(
        key = bookId,
        factory = ReaderViewModel.Factory(LocalContext.current.applicationContext as android.app.Application, bookId),
    ),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val prefs = remember { ReaderPrefs(context) }
    // Read again whenever the page comes back (see below): the text size and layout can be
    // changed from this page's controls, and the book is laid out afresh from the same place.
    var theme by remember { mutableStateOf(prefs.theme) }
    var fontSize by remember { mutableIntStateOf(prefs.fontSize) }
    var fontKey by remember { mutableStateOf(prefs.fontFamily) }
    var marginTopBottom by remember { mutableIntStateOf(prefs.marginTopBottom) }
    var marginSides by remember { mutableIntStateOf(prefs.marginSides) }
    val colors = remember(theme) { pageColors(theme) }
    val fontFamily = remember(fontKey) { Typefaces.familyFor(fontKey) }
    val textStyle = remember(fontSize, fontFamily, colors) {
        TextStyle(
            color = colors.text,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.4f).sp,
            fontFamily = fontFamily,
            textAlign = TextAlign.Start,
        )
    }

    val state by vm.state.collectAsState()
    val offeredPlace by vm.phonePlace.collectAsState()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val ttsState by TtsPlayback.state.collectAsState()
    val ttsBook by TtsPlayback.bookId.collectAsState()
    val spoken by TtsPlayback.sentence.collectAsState()
    val fetching by TtsPlayback.fetching.collectAsState()
    val ttsHere = ttsBook == bookId && ttsState != TtsState.IDLE
    val sleepTimer by TtsPlayback.sleepTimer.collectAsStateWithLifecycle()
    val timerHere = sleepTimer.takeIf { ttsHere }
    val sleepSeconds = sleepTimerSeconds(timerHere)
    val isRound = LocalConfiguration.current.isScreenRound

    var showToolbar by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(!prefs.readerHintSeen) }
    var crownTravel by remember { mutableFloatStateOf(0f) }
    // Auto page turn is switched on for this reading of this book only, as on the phone; only its
    // pace is kept. It used to be a setting, so it came back on in the next book and the next
    // session, and stopping at the end of a book never wrote it off. It lasts while this book's
    // page is in the back stack, a visit to the appearance settings included.
    var autoTurn by rememberSaveable(bookId) { mutableStateOf(false) }
    var autoSeconds by remember { mutableFloatStateOf(prefs.autoTurnSeconds) }
    var boundaryMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(boundaryMessage) {
        if (boundaryMessage != null) { delay(2200); boundaryMessage = null }
    }
    fun turnPage(forward: Boolean) {
        val changed = if (forward) vm.nextPage() else vm.prevPage()
        if (changed) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        // Only a turn that goes nowhere says why. Arriving on the last page is not finishing it:
        // the page has still to be read.
        else if (state is ReaderUiState.Ready) {
            boundaryMessage = context.getString(if (forward) R.string.reader_end else R.string.reader_first_page)
        }
    }
    val focusRequester = remember { FocusRequester() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val measurer = rememberTextMeasurer()

    // Keep the screen on while this page is up, if the user wants that.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        if (prefs.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Follow the voice: turn the page when the spoken sentence leaves it. A page turned by hand
    // while it reads is brought back by the next sentence; a jump lets the voice go first.
    LaunchedEffect(bookId) {
        combine(TtsPlayback.bookId, TtsPlayback.sentence) { id, range -> if (id == bookId) range else null }
            .collect { range -> if (range != null && !TtsPlayback.isLetGo(bookId)) vm.followSpoken(range.first) }
    }

    BackHandler(enabled = showToolbar) { showToolbar = false }

    // Auto page turn: the page stays for the chosen seconds, then moves on. The wait lives
    // inside repeatOnLifecycle, so it stops the moment the watch leaves the page (screen off,
    // Home, another app) and starts afresh on the way back; otherwise a book left open would
    // keep turning in the dark and overwrite the reading position on the phone.
    LaunchedEffect(autoTurn, autoSeconds, state, showToolbar, showBookmarks) {
        val s = state
        if (!autoTurn || showToolbar || showBookmarks || s !is ReaderUiState.Ready) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay((autoSeconds * 1000).toLong())
            // The last page stays its full time like any other before the turning stops.
            if (autoTurn && !showToolbar && !showBookmarks && !vm.nextPage()) {
                autoTurn = false
                boundaryMessage = context.getString(R.string.reader_end)
            }
        }
    }
    // Reading aloud turns pages by itself, so the two never run together: starting a voice
    // switches auto page turn off, which is also what the switch then shows. Turning it back on
    // afterwards is the reader's call, not something that happens by itself.
    LaunchedEffect(ttsHere, ttsState) {
        vm.setReadAloud(ttsHere)
        vm.setListening(ttsHere && ttsState == TtsState.PLAYING)
        if (ttsHere && ttsState == TtsState.PLAYING && autoTurn) autoTurn = false
        // The double tap that pauses the voice is said once, on the page where it works, the
        // first time a voice reads here.
        if (ttsHere && ttsState == TtsState.PLAYING && !prefs.doubleTapHintSeen) {
            prefs.doubleTapHintSeen = true
            boundaryMessage = context.getString(R.string.reader_double_pause)
        }
    }
    // The seconds a page stays can be changed in Settings while this screen sits in the back
    // stack, so read it again every time the page comes forward. Reading time runs while the
    // page is in front, and on the way to the background the phone hears about the last pages.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                autoSeconds = prefs.autoTurnSeconds
                theme = prefs.theme
                fontSize = prefs.fontSize
                fontKey = prefs.fontFamily
                marginTopBottom = prefs.marginTopBottom
                marginSides = prefs.marginSides
                vm.onScreenResumed()
            }
            if (event == Lifecycle.Event.ON_PAUSE) vm.onScreenPaused()
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
                    turnPage(true)
                }
                while (crownTravel <= -CROWN_PIXELS_PER_PAGE) {
                    crownTravel += CROWN_PIXELS_PER_PAGE
                    turnPage(false)
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(showToolbar, showHint, ttsHere, ttsState) {
                detectTapGestures(
                    // Nothing but a page turn on a plain tap: the toolbar was too easy to hit.
                    onTap = { offset ->
                        if (showToolbar || showHint) return@detectTapGestures
                        turnPage(offset.x >= size.width / 2f)
                    },
                    // A double tap pauses a voice that is reading, and does nothing else. Listening
                    // for one makes every tap wait to see whether a second follows, and when paused
                    // two quick taps are two pages; resuming is in the controls.
                    onDoubleTap = if (ttsHere && ttsState == TtsState.PLAYING) ({
                        if (!showToolbar && !showHint) TtsService.pause(context)
                    }) else null,
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
                        CustomAccessibilityAction(previousLabel) { turnPage(false); true },
                        CustomAccessibilityAction(nextLabel) { turnPage(true); true },
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
                    text = boundaryMessage ?: stringResource(R.string.reader_percent, (s.fraction * 100).toInt()),
                    color = if (boundaryMessage != null) colors.text else colors.dim,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        // "End of the book" and the like are said when they come up; the
                        // percentage of every page turned is not.
                        .semantics { if (boundaryMessage != null) liveRegion = LiveRegionMode.Polite },
                )
                if (ttsHere) {
                    val waitingFor = fetching
                    Text(
                        text = if (sleepSeconds != null) {
                            stringResource(R.string.sleep_timer_countdown, sleepSeconds / 60, sleepSeconds % 60)
                        } else if (ttsState == TtsState.PLAYING && waitingFor != null) {
                            stringResource(R.string.reader_fetching_short, TtsLanguages.label(waitingFor))
                        } else stringResource(when (ttsState) {
                            TtsState.LOADING -> R.string.reader_preparing_short
                            TtsState.PLAYING -> R.string.reader_playing_short
                            else -> R.string.tts_paused
                        }),
                        color = colors.dim,
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                    )
                } else if (offeredPlace != null && s.totalChars > 0) {
                    // The phone has read on elsewhere while this page was open: offered, not
                    // followed, and Return to before jump in the controls comes back here.
                    val percent = ((offeredPlace ?: 0).toLong() * 100 / s.totalChars).toInt()
                    val goThere = stringResource(R.string.reader_phone_place_go, percent)
                    CompactChip(
                        onClick = { vm.goToPhonePlace(); tick() },
                        label = { Text(stringResource(R.string.reader_phone_place, percent), fontSize = 11.sp, maxLines = 1) },
                        colors = ChipDefaults.chipColors(backgroundColor = colors.text.copy(alpha = 0.18f).compositeOver(colors.background), contentColor = colors.text),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 4.dp)
                            .semantics {
                                contentDescription = goThere
                                liveRegion = LiveRegionMode.Polite
                            },
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
                        sleepTimer = timerHere,
                        sleepSeconds = sleepSeconds,
                        onSleepTimer = { minutes -> TtsService.setSleepTimer(context, bookId, minutes) },
                        autoTurn = autoTurn,
                        onAutoTurnChange = { on ->
                            if (on && ttsHere) TtsService.stop(context)
                            autoTurn = on
                            tick()
                        },
                        background = colors.background,
                        textColor = colors.text,
                        chapters = s.chapters,
                        currentOffset = page.start,
                        totalChars = s.totalChars,
                        canUndoJump = s.canUndoJump,
                        onUndoJump = {
                            if (ttsHere) TtsService.stopForJump(context, bookId)
                            vm.undoJump()
                            showToolbar = false
                        },
                        onChapter = { chapter ->
                            if (ttsHere) TtsService.stopForJump(context, bookId)
                            vm.jumpToChapter(chapter)
                            showToolbar = false
                            tick()
                        },
                        onBookmarks = { showToolbar = false; showBookmarks = true },
                        onAppearance = { showToolbar = false; onAppearance() },
                        onClose = { showToolbar = false },
                        onPlayPause = {
                            when {
                                ttsHere && ttsState == TtsState.PLAYING -> TtsService.pause(context)
                                ttsHere && ttsState == TtsState.PAUSED -> TtsService.resume(context)
                                ttsHere && ttsState == TtsState.LOADING -> return@Toolbar
                                else -> startReadingAloud(page.start)
                            }
                            showToolbar = false
                        },
                        onStop = {
                            TtsService.stop(context)
                            showToolbar = false
                        },
                        onJump = { f ->
                            if (ttsHere) TtsService.stopForJump(context, bookId)
                            vm.jumpToFraction(f)
                            tick()
                        },
                    )
                }
            }
        }
    }

    val readyForBookmarks = state as? ReaderUiState.Ready
    Dialog(showDialog = showBookmarks && readyForBookmarks != null, onDismissRequest = { showBookmarks = false }) {
        readyForBookmarks?.let { ready ->
            WearBookmarkSheet(
                state = bookmarks,
                pageStart = ready.page.start,
                pageEnd = ready.page.end,
                totalChars = ready.totalChars,
                background = colors.background,
                textColor = colors.text,
                onToggle = vm::toggleBookmark,
                onDelete = vm::removeBookmark,
                onRetry = vm::retryBookmarks,
                onJump = { offset ->
                    if (ttsHere) TtsService.stopForJump(context, bookId)
                    vm.jumpToBookmark(offset)
                    showBookmarks = false
                    tick()
                },
                onClose = { showBookmarks = false },
            )
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
    onBookmarks: () -> Unit,
    onAppearance: () -> Unit,
    ttsHere: Boolean,
    ttsState: TtsState,
    sleepTimer: SleepTimer.State?,
    sleepSeconds: Long?,
    onSleepTimer: (Int) -> Unit,
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
    totalChars: Int,
    canUndoJump: Boolean,
    onUndoJump: () -> Unit,
) {
    val controlBg = textColor.copy(alpha = 0.10f).compositeOver(background)
    var contents by remember { mutableStateOf(false) }
    var sleepOptions by remember { mutableStateOf(false) }
    // The position shown follows the reading until the reader presses + or -; from then on it
    // is theirs, and a voice turning the page underneath must not put it back.
    var jumpEdited by remember { mutableStateOf(false) }
    var editedFraction by remember { mutableFloatStateOf(fraction) }
    val draftFraction = if (jumpEdited) editedFraction else fraction
    val currentChapter = chapters.lastOrNull { it.start <= currentOffset }
    val targetChapter = chapters.lastOrNull { it.start <= (draftFraction * totalChars).toInt() }
    val listState = rememberScalingLazyListState()
    val chapterState = rememberScalingLazyListState(initialCenterItemIndex = (chapters.indexOfLast { it.start <= currentOffset } + 1).coerceAtLeast(1))
    val sleepListState = rememberScalingLazyListState()
    val activeState = when {
        sleepOptions -> sleepListState
        contents -> chapterState
        else -> listState
    }
    BackHandler {
        when {
            sleepOptions -> sleepOptions = false
            contents -> contents = false
            else -> onClose()
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().background(background),
        positionIndicator = { PositionIndicator(scalingLazyListState = activeState) },
    ) {
        ScalingLazyColumn(state = activeState, modifier = Modifier.fillMaxSize()) {
            if (sleepOptions) {
                item { Text(stringResource(R.string.sleep_timer_title), color = textColor, fontSize = 14.sp) }
                item {
                    Text(
                        if (sleepSeconds != null) stringResource(R.string.sleep_timer_countdown, sleepSeconds / 60, sleepSeconds % 60)
                        else stringResource(R.string.sleep_timer_off),
                        color = textColor, fontSize = 12.sp,
                    )
                }
                items(listOf(0) + SleepTimer.MINUTES) { minutes ->
                    val selected = if (minutes == 0) sleepTimer == null else sleepTimer?.minutes == minutes
                    val name = if (minutes == 0) stringResource(R.string.sleep_timer_off) else stringResource(R.string.sleep_timer_minutes, minutes)
                    Chip(
                        onClick = { onSleepTimer(minutes); sleepOptions = false },
                        enabled = minutes == 0 || (ttsHere && ttsState == TtsState.PLAYING),
                        // The dot shows the choice in force; TalkBack says "selected" instead.
                        label = { Text((if (selected) "● " else "") + name, modifier = Modifier.clearAndSetSemantics { contentDescription = name }) },
                        colors = ChipDefaults.chipColors(backgroundColor = controlBg, contentColor = textColor),
                        modifier = Modifier.fillMaxWidth(0.84f).semantics { this.selected = selected },
                    )
                }
                item {
                    Text(stringResource(R.string.sleep_timer_rule), color = textColor, fontSize = 12.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.8f))
                }
                if (!ttsHere || ttsState != TtsState.PLAYING) item {
                    Text(stringResource(R.string.sleep_timer_play_first), color = textColor, fontSize = 12.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.8f))
                }
                item {
                    Chip(onClick = { sleepOptions = false }, label = { Text(stringResource(R.string.reader_controls)) },
                        colors = ChipDefaults.chipColors(backgroundColor = controlBg, contentColor = textColor),
                        modifier = Modifier.fillMaxWidth(0.84f))
                }
            } else if (contents) {
                item { Text(stringResource(R.string.reader_contents), color = textColor, fontSize = 14.sp) }
                if (chapters.isEmpty()) item {
                    Text(stringResource(R.string.reader_no_chapters), color = textColor, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.8f))
                }
                items(chapters) { chapter ->
                    val current = chapter == currentChapter
                    Chip(
                        onClick = { onChapter(chapter) },
                        label = {
                            Text(
                                (if (current) "● " else "") + chapter.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clearAndSetSemantics { contentDescription = chapter.title },
                            )
                        },
                        secondaryLabel = if (current) ({ Text(stringResource(R.string.reader_current_chapter)) }) else null,
                        colors = ChipDefaults.chipColors(backgroundColor = controlBg, contentColor = textColor),
                        modifier = Modifier.fillMaxWidth(0.84f).semantics { selected = current },
                    )
                }
                item { Chip(onClick = { contents = false }, label = { Text(stringResource(R.string.reader_controls)) }, colors = ChipDefaults.chipColors(backgroundColor = controlBg, contentColor = textColor), modifier = Modifier.fillMaxWidth(0.84f)) }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        val playing = ttsHere && ttsState == TtsState.PLAYING
                        TransportButton(
                            shape = if (playing) Transport.PAUSE else Transport.PLAY,
                            color = textColor,
                            label = stringResource(if (playing) R.string.reader_pause else R.string.reader_play),
                            onClick = onPlayPause,
                            enabled = !(ttsHere && ttsState == TtsState.LOADING),
                        )
                        if (ttsHere) {
                            TransportButton(shape = Transport.STOP, color = textColor, label = stringResource(R.string.reader_stop), onClick = onStop)
                        }
                    }
                }
                item {
                    val fetching by TtsPlayback.fetching.collectAsState()
                    val waitingFor = fetching
                    Text(
                        if (ttsHere && ttsState == TtsState.PLAYING && waitingFor != null) {
                            stringResource(R.string.tts_fetching, TtsLanguages.label(waitingFor))
                        } else stringResource(when {
                            ttsHere && ttsState == TtsState.LOADING -> R.string.tts_loading
                            ttsHere && ttsState == TtsState.PLAYING -> R.string.tts_reading_aloud
                            ttsHere && ttsState == TtsState.PAUSED -> R.string.tts_paused
                            else -> R.string.reader_play
                        }),
                        color = textColor, fontSize = 12.sp,
                    )
                }
                if (ttsHere) item {
                    Chip(
                        onClick = { sleepOptions = true },
                        label = { Text(stringResource(R.string.sleep_timer_title)) },
                        secondaryLabel = {
                            Text(
                                if (sleepSeconds != null) stringResource(R.string.sleep_timer_countdown, sleepSeconds / 60, sleepSeconds % 60)
                                else stringResource(R.string.sleep_timer_off),
                            )
                        },
                        colors = ChipDefaults.chipColors(backgroundColor = controlBg, contentColor = textColor),
                        modifier = Modifier.fillMaxWidth(0.84f),
                    )
                }
                item {
                    ToggleChip(
                        checked = autoTurn,
                        onCheckedChange = onAutoTurnChange,
                        label = { Text(stringResource(R.string.settings_auto_turn), fontSize = 14.sp) },
                        toggleControl = { Switch(checked = autoTurn) },
                        colors = ToggleChipDefaults.toggleChipColors(
                            checkedStartBackgroundColor = controlBg,
                            checkedEndBackgroundColor = controlBg,
                            uncheckedStartBackgroundColor = controlBg,
                            uncheckedEndBackgroundColor = controlBg,
                        ),
                        modifier = Modifier.fillMaxWidth(0.84f),
                    )
                }
                item {
                    Chip(
                        onClick = onBookmarks,
                        label = { Text(stringResource(R.string.reader_bookmarks)) },
                        colors = ChipDefaults.chipColors(backgroundColor = controlBg, contentColor = textColor),
                        modifier = Modifier.fillMaxWidth(0.84f),
                    )
                }
                item {
                    Chip(
                        onClick = { contents = true },
                        label = { Text(stringResource(R.string.reader_contents)) },
                        colors = ChipDefaults.chipColors(backgroundColor = controlBg, contentColor = textColor),
                        modifier = Modifier.fillMaxWidth(0.84f),
                    )
                }
                item {
                    // The appearance settings, without going back to the library for them; Back
                    // returns to this page, laid out again from the same place.
                    Chip(
                        onClick = onAppearance,
                        label = { Text(stringResource(R.string.reader_text_layout)) },
                        colors = ChipDefaults.chipColors(backgroundColor = controlBg, contentColor = textColor),
                        modifier = Modifier.fillMaxWidth(0.84f),
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.reader_jump) + "  " + stringResource(R.string.reader_percent, (draftFraction * 100).toInt()),
                        color = textColor,
                        fontSize = 12.sp,
                    )
                }
                item {
                    // One notch is one percent, as the guide says, and the position starts from
                    // the percentage shown on the page, truncated the same way.
                    InlineSlider(
                        value = (draftFraction * 100).toInt().toFloat(),
                        onValueChange = { editedFraction = it / 100f; jumpEdited = true },
                        valueRange = 0f..100f,
                        steps = 99,
                        increaseIcon = { Text("+", color = textColor, fontSize = 16.sp) },
                        decreaseIcon = { Text("–", color = textColor, fontSize = 16.sp) },
                        colors = InlineSliderDefaults.colors(),
                        modifier = Modifier.fillMaxWidth(0.8f),
                    )
                }
                if (targetChapter != null) item {
                    Text(targetChapter.title, color = textColor, fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.8f))
                }
                item {
                    Chip(onClick = { onJump(draftFraction); jumpEdited = false; onClose() }, enabled = jumpEdited,
                        label = { Text(stringResource(R.string.reader_confirm_jump)) },
                        colors = ChipDefaults.chipColors(backgroundColor = controlBg, contentColor = textColor),
                        modifier = Modifier.fillMaxWidth(0.84f))
                }
                if (canUndoJump) item {
                    Chip(onClick = onUndoJump, label = { Text(stringResource(R.string.reader_undo_jump)) },
                        colors = ChipDefaults.chipColors(backgroundColor = controlBg, contentColor = textColor),
                        modifier = Modifier.fillMaxWidth(0.84f))
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

/** Display refreshes stop off-screen. Expiry itself is exclusively owned by TtsService. */
@Composable
private fun sleepTimerSeconds(timer: SleepTimer.State?): Long? {
    val lifecycleOwner = LocalLifecycleOwner.current
    val seconds by produceState(timer?.remainingSeconds(SystemClock.elapsedRealtime()), timer, lifecycleOwner) {
        value = timer?.remainingSeconds(SystemClock.elapsedRealtime())
        if (timer != null) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                value = timer.remainingSeconds(now)
                val remaining = timer.deadlineMillis - now
                if (remaining <= 0) break
                // Refresh on the next displayed-second boundary, including after waking the screen.
                delay(((remaining - 1) % 1_000) + 1)
            }
        }
    }
    return if (timer == null) null else seconds
}

private enum class Transport { PLAY, PAUSE, STOP }

/**
 * The transport controls are drawn rather than typed. The glyphs for pause and stop are emoji on
 * some system fonts and plain marks on others, so a typed toolbar comes out in two different
 * styles on the same watch.
 */
@Composable
private fun TransportButton(shape: Transport, color: androidx.compose.ui.graphics.Color, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Canvas(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.22f))
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
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
