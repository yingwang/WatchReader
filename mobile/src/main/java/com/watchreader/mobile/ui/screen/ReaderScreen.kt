package com.watchreader.mobile.ui.screen

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.setValue
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

private val FONT_SIZE = 18.sp
private val LINE_HEIGHT = 30.sp

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
                    IconButton(
                        onClick = { book?.let { listVm.sendToWatch(it) } },
                        // A book the watch already has can still be sent again: the phone hears
                        // about a copy the watch deleted, not about one lost to a reinstall.
                        enabled = book != null && book.syncStatus != SyncStatus.SENDING,
                    ) {
                        Icon(
                            if (onWatch) Icons.Filled.Check else Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(
                                if (onWatch) R.string.reader_on_watch else R.string.reader_send,
                            ),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val onBackground = MaterialTheme.colorScheme.onBackground
        val textStyle = remember(onBackground) {
            TextStyle(color = onBackground, fontSize = FONT_SIZE, lineHeight = LINE_HEIGHT)
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(Unit) {
                    // The two halves turn pages, the same way round as on the watch.
                    detectTapGestures(
                        onTap = { offset -> if (offset.x < size.width / 2f) vm.prevPage() else vm.nextPage() },
                    )
                },
        ) {
            val widthPx = with(density) { maxWidth.roundToPx() }
            val heightPx = with(density) { maxHeight.roundToPx() }
            val lineHeightPx = with(density) { LINE_HEIGHT.toPx() }
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
                    Canvas(modifier = Modifier.fillMaxSize()) {
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
                        color = onBackground.copy(alpha = 0.45f),
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
                    Text(
                        text = chapter.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(chapter.start) {
                                detectTapGestures(
                                    onTap = {
                                        vm.jumpTo(chapter.start)
                                        showContents = false
                                    },
                                )
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
            }
        }
    }
}
