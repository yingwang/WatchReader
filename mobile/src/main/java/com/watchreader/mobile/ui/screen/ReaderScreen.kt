package com.watchreader.mobile.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.drawText
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.ui.theme.PageTheme
import com.watchreader.mobile.ui.theme.ReadingTheme
import com.watchreader.mobile.ui.viewmodel.BookListViewModel
import com.watchreader.mobile.ui.viewmodel.ReaderHighlightsViewModel
import com.watchreader.mobile.ui.viewmodel.ReaderUiState
import com.watchreader.mobile.ui.viewmodel.ReaderViewModel
import com.watchreader.mobile.ui.viewmodel.UiEvent
import com.watchreader.shared.reader.LineMeasurer
import com.watchreader.shared.bookmark.BookmarkError
import com.watchreader.shared.reader.PageGeometry
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    listVm: BookListViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ReaderViewModel = viewModel(
        key = bookId,
        factory = ReaderViewModel.Factory(context.applicationContext as android.app.Application, bookId),
    )
    val highlightsVm: ReaderHighlightsViewModel = viewModel(
        key = "highlights:$bookId",
        factory = ReaderHighlightsViewModel.Factory(context.applicationContext as android.app.Application, bookId),
    )
    val marks = rememberReaderMarks(highlightsVm)
    // While words are selected the page stays put, whatever would turn it.
    LaunchedEffect(marks) { snapshotFlow { marks.active }.collect { vm.pageTurnsHeld = it } }
    val state by vm.state.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    val search by vm.search.collectAsState()
    val returnTo by vm.returnTo.collectAsState()
    val watchPlace by vm.watchPlace.collectAsState()
    val books by listVm.books.collectAsState()
    val book = books?.firstOrNull { it.id == bookId }
    val snackbar = remember { SnackbarHostState() }
    var showContents by rememberSaveable { mutableStateOf(false) }
    var showBookmarks by rememberSaveable(bookId) { mutableStateOf(false) }
    var showSearch by rememberSaveable(bookId) { mutableStateOf(false) }
    var showHighlights by rememberSaveable(bookId) { mutableStateOf(false) }
    // Fully open, as the contents list is scrolled to the current chapter: near the end of a long
    // book that chapter sits at the bottom of the list, below what a half-open sheet shows.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val prefs = remember { context.getSharedPreferences("reader_appearance", android.content.Context.MODE_PRIVATE) }
    var fontSize by remember { mutableStateOf(prefs.getInt("font_size", 18).coerceIn(MIN_FONT_SP, MAX_FONT_SP)) }
    var lineSpacing by remember { mutableStateOf(LineSpacing.fromKey(prefs.getString("line_spacing", null))) }
    var pageMargin by remember { mutableStateOf(PageMargin.fromKey(prefs.getString("page_margin", null))) }
    var font by remember { mutableStateOf(ReaderTypefaces.byKey(prefs.getString("font", null))) }
    val applySettings: (TextSettings) -> Unit = { settings ->
        fontSize = settings.fontSize
        lineSpacing = settings.spacing
        pageMargin = settings.margin
        prefs.edit()
            .putInt("font_size", settings.fontSize)
            .putString("line_spacing", settings.spacing.key)
            .putString("page_margin", settings.margin.key)
            .apply()
    }
    var pageTheme by remember { mutableStateOf(PageTheme.fromKey(prefs.getString("page_theme", null))) }
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var toolbarVisible by rememberSaveable(bookId) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    // Reading aloud and auto page turn; both live in their own files (ReaderListen, ReaderAutoTurn).
    val listen = rememberReaderListen(bookId, vm, snackbar)
    val autoTurn = rememberAutoTurnState()

    LaunchedEffect(bookmarks.error) {
        if (bookmarks.error == BookmarkError.SAVE) {
            snackbar.showSnackbar(context.getString(R.string.reader_bookmark_save_error))
            vm.clearBookmarkError()
        }
    }

    // Reading time runs while this screen is in front. On the way to the background (Home, the
    // lock button) the watch hears about the last pages turned; closing with Back is not the only
    // way out of this screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.onScreenResumed()
            if (event == Lifecycle.Event.ON_PAUSE) vm.onScreenPaused()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        listVm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbar.showSnackbar(
                    if (event.arg == null) context.getString(event.text) else context.getString(event.text, event.arg),
                )
                is UiEvent.OfferInstall -> {
                    // As in the library: closable and timed, so later messages are not held back.
                    val result = snackbar.showSnackbar(
                        message = context.getString(R.string.msg_watch_without_app, event.watchName),
                        actionLabel = context.getString(R.string.msg_install_action),
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) listVm.openPlayOnWatch(event.nodeId)
                }
            }
        }
    }

    val chapters = (state as? ReaderUiState.Ready)?.chapters.orEmpty()

    AutoTurnEffect(
        auto = autoTurn,
        ready = state as? ReaderUiState.Ready,
        paused = toolbarVisible || showMenu || showAppearance || showContents || showBookmarks || showSearch || showHighlights ||
            marks.active || marks.opened != null || marks.noteEditor != null || watchPlace != null,
        onTurn = vm::nextPage,
        onEnd = { scope.launch { snackbar.showSnackbar(context.getString(R.string.auto_turn_end)) } },
    )
    // The voice turns the pages itself, so starting it switches auto page turn off, as on the watch.
    LaunchedEffect(listen.playing) { if (listen.playing) autoTurn.stop() }

    // The page colours are chosen on this screen, so the theme is applied here rather than around
    // it: the page, the bars and the open dialog all change the moment a colour is picked.
    ReadingTheme(pageTheme) {
        Scaffold(
            topBar = {
                if (toolbarVisible) {
                    TopAppBar(
                        title = {
                            // After a look-up the way back takes the title's place, where the eye
                            // goes and where it covers none of the page.
                            val ready = state as? ReaderUiState.Ready
                            val back = returnTo
                            if (back != null && ready != null && ready.totalChars > 0) {
                                InputChip(
                                    selected = false,
                                    onClick = vm::goBack,
                                    label = { Text(stringResource(R.string.reader_back_to, (back * 100L / ready.totalChars).toInt()), maxLines = 1) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, Modifier.size(InputChipDefaults.IconSize)) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.reader_back_forget),
                                            modifier = Modifier.size(InputChipDefaults.IconSize).clickable(onClick = vm::forgetReturnPoint),
                                        )
                                    },
                                )
                            } else {
                                Text(book?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.add_back))
                            }
                        },
                        actions = {
                            IconButton(onClick = { showAppearance = true }) {
                                Icon(Icons.Filled.TextFields, contentDescription = stringResource(R.string.reader_appearance))
                            }
                            // Shown for every book: the sheet also jumps to a percentage, which a book
                            // without chapters needs most.
                            if (state is ReaderUiState.Ready) {
                                IconButton(onClick = { showContents = true }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.List,
                                        contentDescription = stringResource(R.string.reader_contents),
                                    )
                                }
                            }
                            val onWatch = book?.syncStatus == SyncStatus.SENT
                            if (onWatch) {
                                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.reader_on_watch), modifier = Modifier.padding(12.dp))
                            } else if (book?.syncStatus == SyncStatus.QUEUED) {
                                val cancelLabel = stringResource(R.string.reader_cancel_queued_send)
                                TextButton(
                                    onClick = { book?.let { listVm.cancelQueuedSend(it) } },
                                    modifier = Modifier.semantics { contentDescription = cancelLabel },
                                ) { Text(stringResource(R.string.reader_send_waiting)) }
                            } else if (book?.syncStatus == SyncStatus.SENDING) {
                                CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                            } else IconButton(onClick = { book?.let { listVm.sendToWatch(it) } }, enabled = book != null) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(if (book?.syncStatus == SyncStatus.FAILED) R.string.reader_retry else R.string.reader_send),
                                )
                            }
                            Box {
                                IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.reader_options)) }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    val ready = state as? ReaderUiState.Ready
                                    val hasBookmark = ready != null && bookmarks.onPage(ready.page.start, ready.page.end) != null
                                    DropdownMenuItem(
                                        text = { Text(stringResource(if (hasBookmark) R.string.reader_bookmark_remove else R.string.reader_bookmark_add)) },
                                        enabled = ready != null && ready.page.start < ready.totalChars && !bookmarks.loading && !bookmarks.saving && bookmarks.error != BookmarkError.LOAD,
                                        onClick = { showMenu = false; vm.toggleBookmark() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.reader_bookmarks)) },
                                        enabled = ready != null,
                                        onClick = { showMenu = false; showBookmarks = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.highlights_title)) },
                                        enabled = ready != null,
                                        onClick = { showMenu = false; showHighlights = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.reader_search)) },
                                        enabled = ready != null,
                                        onClick = { showMenu = false; showSearch = true },
                                    )
                                    ReaderPlaybackMenuItems(listen, autoTurn, ready?.page?.start, onDismiss = { showMenu = false })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.reader_hide_controls)) }, onClick = {
                                        showMenu = false
                                        toolbarVisible = false
                                        scope.launch { snackbar.showSnackbar(context.getString(R.string.reader_show_controls_hint)) }
                                    })
                                    if (onWatch) DropdownMenuItem(text = { Text(stringResource(R.string.reader_resend)) }, onClick = {
                                        showMenu = false; book?.let { listVm.sendToWatch(it) }
                                    })
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground,
                            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                            actionIconContentColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            },
            bottomBar = { ReaderBottomBar(listen, autoTurn, controlsVisible = toolbarVisible, hasChapters = chapters.isNotEmpty(), hideControls = { toolbarVisible = false }) },
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val onBackground = MaterialTheme.colorScheme.onBackground
            // Colour is left out of the style the pages are measured with and given when drawing, so
            // picking another page colour repaints the page without laying the book out again.
            val textStyle = remember(fontSize, lineSpacing, font) {
                TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize * lineSpacing.factor).sp, fontFamily = font.family)
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .pointerInput(Unit) {
                        // The outer thirds turn pages, the same way round as on the watch. The middle
                        // shows or hides the title bar, so a hidden bar needs no button of its own to
                        // come back, and nothing is left sitting over the page.
                        detectTapGestures(
                            onTap = { offset ->
                                // Selected words, or a highlight under the finger, take the tap first.
                                if (marks.onTap(offset)) return@detectTapGestures
                                when {
                                    offset.x < size.width / 3f -> vm.prevPage()
                                    offset.x > size.width * 2f / 3f -> vm.nextPage()
                                    else -> toolbarVisible = !toolbarVisible
                                }
                            },
                            onLongPress = { offset -> if (!marks.onLongPress(offset)) toolbarVisible = true },
                        )
                    },
            ) {
                val widthPx = with(density) { maxWidth.roundToPx() }
                val heightPx = with(density) { maxHeight.roundToPx() }
                val lineHeightPx = with(density) { (fontSize * lineSpacing.factor).sp.toPx() }
                val marginPx = with(density) { pageMargin.dp.dp.toPx() }
                val geometry = remember(widthPx, heightPx, lineHeightPx, marginPx) {
                    PageGeometry.rect(widthPx, heightPx, marginPx, lineHeightPx)
                }
                val lineMeasurer = remember(measurer, textStyle) {
                    LineMeasurer { text, start, end, widthLimit ->
                        measurer.measure(
                            text = AnnotatedString(text.substring(start, end)),
                            style = textStyle,
                            overflow = TextOverflow.Clip,
                            maxLines = 1,
                            constraints = Constraints(maxWidth = widthLimit),
                        ).getLineEnd(0, visibleEnd = false)
                    }
                }
                LaunchedEffect(geometry, lineMeasurer) { vm.attachLayout(geometry, lineMeasurer) }

                when (val s = state) {
                    ReaderUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    ReaderUiState.Missing -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(stringResource(R.string.reader_missing), color = onBackground)
                    }
                    is ReaderUiState.Ready -> {
                        val page = s.page
                        val previousLabel = stringResource(R.string.reader_previous_page)
                        val nextLabel = stringResource(R.string.reader_next_page)
                        val controlsLabel = stringResource(if (toolbarVisible) R.string.reader_hide_controls else R.string.reader_show_controls)
                        ReaderMarksUnderlay(marks, page, geometry, measurer, textStyle)
                        SpokenSentenceTint(page, geometry, measurer, textStyle, { listen.spoken }, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                        // TalkBack cannot tap a third of the page, so the page offers the same three
                        // things as actions, as the watch's page does. The page is read from its own
                        // text: joining the drawn lines put a space at every line break, in the middle
                        // of Chinese words.
                        Canvas(modifier = Modifier.fillMaxSize().semantics {
                            contentDescription = page.chars
                            customActions = listOf(
                                CustomAccessibilityAction(previousLabel) { vm.prevPage(); true },
                                CustomAccessibilityAction(nextLabel) { vm.nextPage(); true },
                                CustomAccessibilityAction(controlsLabel) { toolbarVisible = !toolbarVisible; true },
                            )
                        }) {
                            for (line in page.lines) {
                                if (line.end <= line.start) continue
                                val slot = geometry.slots.getOrNull(line.slot) ?: continue
                                val layout = measurer.measure(
                                    text = AnnotatedString(page.text(line)),
                                    style = textStyle,
                                    overflow = TextOverflow.Clip,
                                    maxLines = 1,
                                    constraints = Constraints(maxWidth = slot.width),
                                )
                                drawText(layout, color = onBackground, topLeft = Offset(slot.left, slot.top))
                            }
                        }
                        // While the pages turn by themselves, the line under the page says so beside the percentage.
                        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            AutoTurnIndicator(autoTurn, Modifier.padding(end = 8.dp))
                            Text(
                                text = stringResource(R.string.reader_percent, (s.fraction * 100).toInt()),
                                color = onBackground.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        ReaderMarksOverlay(marks, book?.title, snackbar)
                    }
                }
            }
        }

        val ready = state as? ReaderUiState.Ready
        AutoTurnSheet(autoTurn, listen, hideControls = { toolbarVisible = false })
        if (showBookmarks && ready != null) ReaderBookmarkSheet(
            state = bookmarks,
            pageStart = ready.page.start,
            pageEnd = ready.page.end,
            totalChars = ready.totalChars,
            onToggle = vm::toggleBookmark,
            onJump = { vm.jumpToBookmark(it); showBookmarks = false },
            onDelete = vm::removeBookmark,
            onRetry = vm::retryBookmarks,
            onDismiss = { showBookmarks = false },
        )
        if (showSearch && ready != null) ReaderSearchSheet(
            state = search,
            totalChars = ready.totalChars,
            onQuery = vm::searchBody,
            onResult = { generation, hit -> vm.jumpToSearchResult(generation, hit); showSearch = false; vm.clearSearch() },
            onDismiss = { showSearch = false; vm.clearSearch() },
        )
        if (showHighlights && ready != null) ReaderHighlightsSheet(
            state = highlightsVm.state.collectAsState().value,
            chapters = chapters,
            totalChars = ready.totalChars,
            bookTitle = book?.title.orEmpty(),
            author = book?.author,
            onJump = { vm.jumpTo(it.start); showHighlights = false },
            onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
            onDismiss = { showHighlights = false },
        )
        if (showContents && ready != null) {
            ModalBottomSheet(onDismissRequest = { showContents = false }, sheetState = sheetState) {
                val pageStart = ready.page.start
                val currentIndex = chapters.indexOfLast { it.start <= pageStart }
                // The slider stays above the list rather than scrolling with it, so it is there
                // however far down the current chapter is.
                // It starts at the percentage the page shows, truncated the same way.
                var target by remember { mutableFloatStateOf(ready.fraction) }
                // Whether a finger is on the slider, rather than TalkBack or a keyboard moving it.
                var touched by remember { mutableStateOf(false) }
                val goTo = stringResource(R.string.reader_go_to, (target * 100).toInt())
                val go = {
                    vm.jumpToFraction(target)
                    showContents = false
                }
                Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            goTo,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            // Said by the button beside it.
                            modifier = Modifier.weight(1f).clearAndSetSemantics { },
                        )
                        TextButton(onClick = go, modifier = Modifier.semantics { contentDescription = goTo }) {
                            Text(stringResource(R.string.reader_go))
                        }
                    }
                    Slider(
                        value = target,
                        onValueChange = { target = it },
                        // Only the release moves the book: every step of a drag is not a place read.
                        // A step taken without a finger, as TalkBack and a keyboard take them one at
                        // a time, only moves the mark and leaves the sheet open for the next; Go
                        // makes the jump.
                        onValueChangeFinished = {
                            if (touched) {
                                touched = false
                                go()
                            }
                        },
                        modifier = Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                touched = true
                            }
                        },
                    )
                }
                if (chapters.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    // Opens with the current chapter in view and a couple of chapters above it, for
                    // a book of hundreds of chapters that would otherwise open at the first.
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                        itemsIndexed(chapters) { index, chapter ->
                            val current = index == currentIndex
                            Text(
                                text = (if (current) "● " else "") + chapter.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable { vm.jumpTo(chapter.start); showContents = false }
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        }
                    }
                }
            }
        }
        if (showAppearance) AlertDialog(
            onDismissRequest = { showAppearance = false },
            title = { Text(stringResource(R.string.reader_appearance)) },
            text = {
                // No shade over the page: the page behind the dialog takes each colour and is laid out
                // again at every size, and that is the truest preview there is.
                val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
                val dialogView = LocalView.current
                // While the dialog has focus its own window decides the colour of the status bar
                // icons, so it follows the page as well: dark icons on a light page.
                SideEffect {
                    dialogWindow?.setDimAmount(0f)
                    dialogWindow?.let { WindowCompat.getInsetsController(it, dialogView).isAppearanceLightStatusBars = pageTheme.isLight }
                }
                // Scrolls on a phone held sideways, where the largest sample and the slider do not fit.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val settings = TextSettings(fontSize, lineSpacing, pageMargin)
                    Text(
                        stringResource(R.string.reader_presets),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    ChoiceChips(
                        options = ReadingPreset.entries,
                        selected = ReadingPreset.matching(settings),
                        label = { it.label },
                        onSelect = { applySettings(it.settings) },
                    )
                    Spacer(Modifier.height(16.dp))
                    PageThemeChoice(selected = pageTheme, onSelect = {
                        pageTheme = it
                        prefs.edit().putString("page_theme", it.key).apply()
                    })
                    Text(
                        stringResource(R.string.reader_font_size),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                    )
                    // The sample keeps the height of its largest size, so the slider stays under the
                    // finger while the sample grows and wraps.
                    Text(
                        stringResource(R.string.reader_font_preview),
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * lineSpacing.factor).sp,
                        fontFamily = font.family,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.height(with(LocalDensity.current) { (MAX_FONT_SP * LineSpacing.LOOSE.factor * 2).sp.toDp() }),
                    )
                    Text("${fontSize} sp")
                    Slider(value = fontSize.toFloat(), onValueChange = {
                        applySettings(settings.copy(fontSize = it.roundToInt()))
                    }, valueRange = MIN_FONT_SP.toFloat()..MAX_FONT_SP.toFloat(), steps = MAX_FONT_SP - MIN_FONT_SP - 1)
                    Text(
                        stringResource(R.string.reader_font),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    ChoiceChips(ReaderTypefaces.faces, font, { it.label }, { it.family }) {
                        font = it
                        prefs.edit().putString("font", it.key).apply()
                    }
                    Text(
                        stringResource(R.string.reader_line_spacing),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    ChoiceChips(LineSpacing.entries, lineSpacing, { it.label }) { applySettings(settings.copy(spacing = it)) }
                    Text(
                        stringResource(R.string.reader_margins),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    ChoiceChips(PageMargin.entries, pageMargin, { it.label }) { applySettings(settings.copy(margin = it)) }
                }
            },
            confirmButton = { TextButton(onClick = { showAppearance = false }) { Text(stringResource(R.string.reader_done)) } },
        )
        val offered = watchPlace
        if (offered != null && ready != null && ready.totalChars > 0) {
            val percent = (offered * 100L / ready.totalChars).toInt()
            val chapter = chapters.lastOrNull { it.start <= offered }?.title
            AlertDialog(
                onDismissRequest = vm::dismissWatchPlace,
                title = { Text(stringResource(R.string.reader_watch_place_title)) },
                text = {
                    Text(
                        if (chapter != null) stringResource(R.string.reader_watch_place_body_chapter, percent, chapter)
                        else stringResource(R.string.reader_watch_place_body, percent),
                    )
                },
                confirmButton = { TextButton(onClick = vm::goToWatchPlace) { Text(stringResource(R.string.reader_watch_place_go)) } },
                dismissButton = { TextButton(onClick = vm::stayHere) { Text(stringResource(R.string.reader_watch_place_stay)) } },
            )
        }
    }
}

/**
 * Dark, Light and Sepia as three small pages, each in its own colours, so the choice shows what it
 * gives. They are a radio group to TalkBack.
 */
@Composable
private fun PageThemeChoice(selected: PageTheme, onSelect: (PageTheme) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (theme in PageTheme.entries) {
            val isSelected = theme == selected
            val shape = RoundedCornerShape(12.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(shape)
                    .background(theme.colors.background)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = shape,
                    )
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(theme) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(
                        when (theme) {
                            PageTheme.DARK -> R.string.reader_page_dark
                            PageTheme.LIGHT -> R.string.reader_page_light
                            PageTheme.SEPIA -> R.string.reader_page_sepia
                        },
                    ),
                    color = theme.colors.onBackground,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private const val MIN_FONT_SP = 14
private const val MAX_FONT_SP = 30
