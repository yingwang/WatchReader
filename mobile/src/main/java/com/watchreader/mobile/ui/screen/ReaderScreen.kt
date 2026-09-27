package com.watchreader.mobile.ui.screen

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import com.watchreader.mobile.ui.viewmodel.ReaderUiState
import com.watchreader.mobile.ui.viewmodel.ReaderViewModel
import com.watchreader.mobile.ui.viewmodel.UiEvent
import com.watchreader.shared.reader.LineMeasurer
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
    val state by vm.state.collectAsState()
    val books by listVm.books.collectAsState()
    val book = books?.firstOrNull { it.id == bookId }
    val snackbar = remember { SnackbarHostState() }
    var showContents by rememberSaveable { mutableStateOf(false) }
    // Fully open, as the contents list is scrolled to the current chapter: near the end of a long
    // book that chapter sits at the bottom of the list, below what a half-open sheet shows.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val prefs = remember { context.getSharedPreferences("reader_appearance", android.content.Context.MODE_PRIVATE) }
    var fontSize by remember { mutableStateOf(prefs.getInt("font_size", 18).coerceIn(MIN_FONT_SP, MAX_FONT_SP)) }
    var pageTheme by remember { mutableStateOf(PageTheme.fromKey(prefs.getString("page_theme", null))) }
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var toolbarVisible by rememberSaveable(bookId) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

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

    // The page colours are chosen on this screen, so the theme is applied here rather than around
    // it: the page, the bars and the open dialog all change the moment a colour is picked.
    ReadingTheme(pageTheme) {
        Scaffold(
            topBar = {
                if (toolbarVisible) {
                    TopAppBar(
                        title = { Text(book?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.add_back))
                            }
                        },
                        actions = {
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
                                    DropdownMenuItem(text = { Text(stringResource(R.string.reader_appearance)) }, onClick = { showMenu = false; showAppearance = true })
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
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val onBackground = MaterialTheme.colorScheme.onBackground
            // Colour is left out of the style the pages are measured with and given when drawing, so
            // picking another page colour repaints the page without laying the book out again.
            val textStyle = remember(fontSize) {
                TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize * 1.65f).sp)
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
                                when {
                                    offset.x < size.width / 3f -> vm.prevPage()
                                    offset.x > size.width * 2f / 3f -> vm.nextPage()
                                    else -> toolbarVisible = !toolbarVisible
                                }
                            },
                            onLongPress = { toolbarVisible = true },
                        )
                    },
            ) {
                val widthPx = with(density) { maxWidth.roundToPx() }
                val heightPx = with(density) { maxHeight.roundToPx() }
                val lineHeightPx = with(density) { (fontSize * 1.65f).sp.toPx() }
                val marginPx = with(density) { 24.dp.toPx() }
                val geometry = remember(widthPx, heightPx, lineHeightPx) {
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
                        Text(
                            text = stringResource(R.string.reader_percent, (s.fraction * 100).toInt()),
                            color = onBackground.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                        )
                    }
                }
            }
        }

        val ready = state as? ReaderUiState.Ready
        if (showContents && ready != null) {
            ModalBottomSheet(onDismissRequest = { showContents = false }, sheetState = sheetState) {
                val pageStart = ready.page.start
                val currentIndex = chapters.indexOfLast { it.start <= pageStart }
                // The slider stays above the list rather than scrolling with it, so it is there
                // however far down the current chapter is.
                // It starts at the percentage the page shows, truncated the same way.
                var target by remember { mutableFloatStateOf(ready.fraction) }
                Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
                    Text(
                        stringResource(R.string.reader_go_to, (target * 100).toInt()),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Slider(
                        value = target,
                        onValueChange = { target = it },
                        // Only the release moves the book: every step of a drag is not a place read.
                        onValueChangeFinished = {
                            vm.jumpTo((target * ready.totalChars).roundToInt())
                            showContents = false
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
                        lineHeight = (fontSize * 1.65f).sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.height(with(LocalDensity.current) { (MAX_FONT_SP * 1.65f * 2).sp.toDp() }),
                    )
                    Text("${fontSize} sp")
                    Slider(value = fontSize.toFloat(), onValueChange = {
                        fontSize = it.roundToInt()
                        prefs.edit().putInt("font_size", fontSize).apply()
                    }, valueRange = MIN_FONT_SP.toFloat()..MAX_FONT_SP.toFloat(), steps = MAX_FONT_SP - MIN_FONT_SP - 1)
                }
            },
            confirmButton = { TextButton(onClick = { showAppearance = false }) { Text(stringResource(R.string.reader_done)) } },
        )
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
