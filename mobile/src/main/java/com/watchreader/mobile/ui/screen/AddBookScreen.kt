package com.watchreader.mobile.ui.screen

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchreader.mobile.R
import com.watchreader.mobile.data.model.SyncStatus
import com.watchreader.mobile.ui.SharedIntent
import com.watchreader.mobile.ui.viewmodel.AddBookViewModel
import com.watchreader.mobile.ui.viewmodel.BookListViewModel
import com.watchreader.mobile.ui.viewmodel.ImportFile
import com.watchreader.mobile.ui.viewmodel.UiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBookScreen(
    shareGeneration: Int,
    onBack: () -> Unit,
    onFreeBooks: () -> Unit,
    onOpenBook: (String) -> Unit,
    listVm: BookListViewModel,
    vm: AddBookViewModel = viewModel(),
) {
    val loading by vm.isLoading.collectAsState()
    val error by vm.error.collectAsState()
    val completed by vm.completed.collectAsState()
    val added by vm.added.collectAsState()
    val failures by vm.failures.collectAsState()
    val progress by vm.progress.collectAsState()
    val stored by listVm.books.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(listVm) {
        listVm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbar.showSnackbar(
                    if (event.arg == null) context.getString(event.text) else context.getString(event.text, event.arg),
                )
                is UiEvent.OfferInstall -> {
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
    var url by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var showTitle by rememberSaveable { mutableStateOf(false) }
    var showLink by rememberSaveable { mutableStateOf(false) }
    var unusable by rememberSaveable { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    val busy = loading || preparing

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty() || busy) return
        preparing = true
        unusable = false
        vm.reset()
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    uris.distinct().map { uri ->
                        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        ImportFile(uri, displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "book")
                    }
                }
                vm.addFiles(files, title)
            } finally { preparing = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), ::importFiles)
    LaunchedEffect(shareGeneration, busy) {
        if (!busy) when (val shared = SharedIntent.consume()) {
            is SharedIntent.Shared.File -> importFiles(listOf(shared.uri))
            is SharedIntent.Shared.Files -> importFiles(shared.uris)
            is SharedIntent.Shared.Link -> { vm.reset(); url = shared.url; showLink = true; unusable = false }
            SharedIntent.Shared.Unusable -> unusable = true
            null -> Unit
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(
            title = { Text(stringResource(R.string.add_title), fontFamily = FontFamily.Serif) },
            navigationIcon = { IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.add_back))
            } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        ) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (preparing) stringResource(R.string.import_preparing)
                    else stringResource(R.string.import_progress, progress.first, progress.second))
            }
            if (unusable) Text(stringResource(R.string.add_share_needs_file_or_link))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (completed) {
                Text(pluralStringResource(R.plurals.import_added_count, added.size, added.size), style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Serif)
                for (book in added) {
                    val current = stored?.firstOrNull { it.id == book.id } ?: book
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(book.title, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(when (current.syncStatus) {
                                SyncStatus.QUEUED -> R.string.transfer_waiting
                                SyncStatus.SENDING -> R.string.status_sending
                                SyncStatus.SENT -> R.string.status_sent
                                SyncStatus.FAILED -> R.string.status_failed
                                SyncStatus.NOT_SENT -> R.string.status_not_sent
                            }), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onOpenBook(book.id) }) { Text(stringResource(R.string.import_read_now)) }
                            if (current.syncStatus == SyncStatus.NOT_SENT || current.syncStatus == SyncStatus.FAILED) {
                                OutlinedButton(onClick = { listVm.sendToWatch(current) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.reader_send))
                                }
                            }
                            if (current.syncStatus == SyncStatus.QUEUED) TextButton(onClick = { listVm.cancelQueuedSend(current) }) {
                                Text(stringResource(R.string.transfer_cancel))
                            }
                        }
                    }
                }
                if (added.size > 1) Button(onClick = {
                    var offerConnectionHelp = true
                    added.forEach { book ->
                        val current = stored?.firstOrNull { it.id == book.id } ?: book
                        if (current.syncStatus == SyncStatus.NOT_SENT || current.syncStatus == SyncStatus.FAILED) {
                            listVm.sendToWatch(current, quietly = !offerConnectionHelp)
                            offerConnectionHelp = false
                        }
                    }
                }, enabled = added.any { book ->
                    (stored?.firstOrNull { it.id == book.id } ?: book).syncStatus in setOf(SyncStatus.NOT_SENT, SyncStatus.FAILED)
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_send_all)) }
                failures.forEach { failure ->
                    Text(listOfNotNull(failure.file?.name, failure.message).joinToString(": "), color = MaterialTheme.colorScheme.error)
                }
                if (failures.isNotEmpty()) OutlinedButton(onClick = vm::retryFailed, enabled = !busy) {
                    Text(stringResource(R.string.import_retry_failed))
                }
                TextButton(onClick = onBack) { Text(stringResource(R.string.import_back_library)) }
                TextButton(onClick = { vm.reset(); title = "" }, enabled = !busy) { Text(stringResource(R.string.import_more)) }
            } else if (!busy) {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.add_from_file), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                        Text(stringResource(R.string.import_file_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { picker.launch(BOOK_TYPES) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(stringResource(R.string.import_choose_files))
                        }
                    }
                }
                OutlinedButton(onClick = { showLink = !showLink }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.add_from_link))
                }
                if (showLink) {
                    Text(stringResource(R.string.add_link_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(url, { url = it; vm.clearError() }, label = { Text(stringResource(R.string.add_url_label)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Button(onClick = { vm.reset(); vm.addFromUrl(url, title) }, enabled = url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.add_submit_download))
                    }
                }
                TextButton(onClick = { showTitle = !showTitle }) { Text(stringResource(R.string.add_book_title_label)) }
                if (showTitle) {
                    OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.add_book_title_label)) })
                    Text(stringResource(R.string.import_title_hint), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Card(onClick = onFreeBooks, shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.add_free_books), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                        Text(stringResource(R.string.add_free_books_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private val BOOK_TYPES = arrayOf(
    "text/plain", "application/epub+zip", "application/octet-stream",
    "application/x-fictionbook+xml", "application/x-fictionbook", "application/x-zip-compressed-fb2",
    "text/xml", "application/xml", "application/zip", "application/x-zip-compressed",
)
private fun displayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()?.takeIf { it.isNotBlank() }
