package com.watchreader.mobile.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.SyncStatus
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
    val book = books.firstOrNull { it.id == bookId }
    val snackbar = remember { SnackbarHostState() }
    var showContents by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val prefs = remember { context.getSharedPreferences("reader_appearance", android.content.Context.MODE_PRIVATE) }
    var fontSize by remember { mutableStateOf(prefs.getInt("font_size", 18).coerceIn(MIN_FONT_SP, MAX_FONT_SP)) }
    var showAppearance by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var toolbarVisible by rememberSaveable(bookId) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        listVm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbar.showSnackbar(
                    if (event.arg == null) context.getString(event.text) else context.getString(event.text, event.arg),
                )
                is UiEvent.OfferInstall -> {
                    val result = snackbar.showSnackbar(
                        message = context.getString(R.string.msg_watch_without_app, event.watchName),
                        actionLabel = context.getString(R.string.msg_install_action),
                    )
                    if (result == SnackbarResult.ActionPerformed) listVm.openPlayOnWatch(event.nodeId)
                }
            }
        }
    }

    val chapters = (state as? ReaderUiState.Ready)?.chapters.orEmpty()

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
                    if (chapters.isNotEmpty()) {
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
                            DropdownMenuItem(text = { Text(stringResource(R.string.reader_font_size)) }, onClick = { showMenu = false; showAppearance = true })
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
        val textStyle = remember(onBackground, fontSize) {
            TextStyle(color = onBackground, fontSize = fontSize.sp, lineHeight = (fontSize * 1.65f).sp)
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
                    Canvas(modifier = Modifier.fillMaxSize().semantics { contentDescription = page.lines.joinToString(" ") { page.text(it) } }) {
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
                            drawText(layout, topLeft = Offset(slot.left, slot.top))
                        }
                    }
                    Text(
                        text = stringResource(R.string.reader_percent, (s.fraction * 100).roundToInt()),
                        color = onBackground.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    )
                }
            }
        }
    }

    if (showContents) {
        ModalBottomSheet(onDismissRequest = { showContents = false }, sheetState = sheetState) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(chapters) { chapter ->
                    val current = chapters.lastOrNull { it.start <= ((state as? ReaderUiState.Ready)?.page?.start ?: 0) } == chapter
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
    if (showAppearance) AlertDialog(
        onDismissRequest = { showAppearance = false },
        title = { Text(stringResource(R.string.reader_font_size)) },
        text = {
            // No shade over the page: the text behind the dialog is laid out again at every step,
            // and that is the truest preview there is.
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { dialogWindow?.setDimAmount(0f) }
            Column {
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

private const val MIN_FONT_SP = 14
private const val MAX_FONT_SP = 30
