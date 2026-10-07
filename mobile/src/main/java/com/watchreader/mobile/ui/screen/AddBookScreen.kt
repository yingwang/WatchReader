package com.watchreader.mobile.ui.screen

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchreader.mobile.R
import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.mobile.ui.SharedIntent
import com.watchreader.mobile.ui.viewmodel.AddBookViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBookScreen(
    /** Bumped by the activity for every file shared in; a new share is taken even while this screen is up. */
    shareGeneration: Int,
    onBack: () -> Unit,
    onFreeBooks: () -> Unit,
    vm: AddBookViewModel = viewModel(),
) {
    val isLoading by vm.isLoading.collectAsState()
    val error by vm.error.collectAsState()
    val done by vm.done.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Saved across rotation, so turning the phone does not throw away the file just chosen.
    var url by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var selectedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var selectedFileName by rememberSaveable { mutableStateOf("") }
    // Shared text with no link in it: said once, on this screen, and gone as soon as anything is chosen.
    var needsFileOrLink by rememberSaveable { mutableStateOf(false) }

    fun take(uri: Uri) {
        selectedUri = uri
        selectedFileName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "book"
        needsFileOrLink = false
        vm.clearError()
        // The provider is asked for the file's proper name off the main thread: a cloud drive
        // can take a while to answer, and the path's last segment stands in until it does.
        scope.launch {
            val name = withContext(Dispatchers.IO) { displayName(context, uri) }
            if (name != null && selectedUri == uri) selectedFileName = name
        }
    }

    fun clearFile() {
        selectedUri = null
        selectedFileName = ""
        vm.clearError()
    }

    // A file shared from another app lands here already selected, and a shared link waits in the
    // link field. Keyed on the share, so a second one shared while this screen is already open is
    // taken as well instead of waiting in the wings.
    LaunchedEffect(shareGeneration) {
        when (val shared = SharedIntent.consume()) {
            is SharedIntent.Shared.File -> take(shared.uri)
            is SharedIntent.Shared.Link -> {
                clearFile()
                url = shared.url
                needsFileOrLink = false
            }
            SharedIntent.Shared.Unusable -> needsFileOrLink = true
            null -> {}
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) take(uri)
    }

    LaunchedEffect(done) {
        if (done) onBack()
    }

    val fallbackTitle = BookRepository.bareName(selectedFileName).ifBlank { selectedFileName }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_title), fontFamily = FontFamily.Serif) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.add_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))

            if (needsFileOrLink) {
                Text(
                    stringResource(R.string.add_share_needs_file_or_link),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.add_from_file), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                    Text(stringResource(R.string.add_file_formats), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                    if (selectedUri != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(selectedFileName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { clearFile() }, enabled = !isLoading) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.add_clear_file))
                            }
                        }
                        TextButton(
                            onClick = { filePicker.launch(BOOK_TYPES) },
                            enabled = !isLoading,
                        ) { Text(stringResource(R.string.add_change_file)) }
                    } else {
                        OutlinedButton(
                            onClick = { filePicker.launch(BOOK_TYPES) },
                            enabled = !isLoading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) { Text(stringResource(R.string.add_choose_file)) }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.add_or), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp))
                HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.add_from_link), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                    Text(stringResource(if (selectedUri != null) R.string.add_link_remove_file else R.string.add_link_hint),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; needsFileOrLink = false; vm.clearError() },
                        label = { Text(stringResource(R.string.add_url_label)) },
                        placeholder = { Text(stringResource(R.string.add_url_placeholder)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        enabled = selectedUri == null && !isLoading,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                enabled = !isLoading,
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.add_book_title_label)) },
                placeholder = { if (fallbackTitle.isNotBlank()) Text(fallbackTitle) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(24.dp))

            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp))
            }

            if (isLoading) {
                CircularProgressIndicator()
            } else {
                val fromUrl = selectedUri == null && url.isNotBlank()
                Button(
                    onClick = {
                        val uri = selectedUri
                        if (uri != null) vm.addFromUri(uri, title, fallbackTitle)
                        else if (url.isNotBlank()) vm.addFromUrl(url, title)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    enabled = selectedUri != null || url.isNotBlank(),
                ) {
                    Text(stringResource(if (fromUrl) R.string.add_submit_download else R.string.add_submit))
                }
            }

            // The free library is a way in of its own, below the two that start from a book in hand,
            // drawn as the same kind of card.
            Spacer(Modifier.height(32.dp))
            Card(
                onClick = onFreeBooks,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.add_free_books), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                        Text(
                            stringResource(R.string.add_free_books_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * What the file picker offers. Few providers know .fb2 by name: most call it octet-stream, some
 * XML, and a zipped one is just a ZIP, so the generic types are asked for alongside the proper
 * ones and the import tells the books apart by what is in them.
 */
private val BOOK_TYPES = arrayOf(
    "text/plain", "application/epub+zip", "application/octet-stream",
    "application/x-fictionbook+xml", "application/x-fictionbook", "application/x-zip-compressed-fb2",
    "text/xml", "application/xml", "application/zip", "application/x-zip-compressed",
)

/** The name the document provider gives the file, or null when it gives none. */
private fun displayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()?.takeIf { it.isNotBlank() }
