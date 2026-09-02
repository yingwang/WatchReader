package com.watchreader.mobile.ui.screen

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchreader.mobile.R
import com.watchreader.mobile.ui.SharedIntent
import com.watchreader.mobile.ui.viewmodel.AddBookViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBookScreen(
    onBack: () -> Unit,
    vm: AddBookViewModel = viewModel(),
) {
    val isLoading by vm.isLoading.collectAsState()
    val error by vm.error.collectAsState()
    val done by vm.done.collectAsState()
    val context = LocalContext.current

    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }

    fun take(uri: Uri) {
        selectedUri = uri
        selectedFileName = displayName(context, uri)
        vm.clearError()
    }

    // A file shared from another app lands here already selected.
    LaunchedEffect(Unit) {
        SharedIntent.consume()?.let { take(it) }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) take(uri)
    }

    LaunchedEffect(done) {
        if (done) onBack()
    }

    val fallbackTitle = selectedFileName.substringBeforeLast(".").ifBlank { selectedFileName }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_title)) },
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

            OutlinedButton(
                onClick = {
                    filePicker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream"))
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    if (selectedUri != null) selectedFileName else stringResource(R.string.add_pick_file),
                    fontSize = 16.sp,
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f))
            Text(
                stringResource(R.string.add_or),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 12.dp),
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it; vm.clearError() },
                label = { Text(stringResource(R.string.add_url_label)) },
                placeholder = { Text(stringResource(R.string.add_url_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = selectedUri == null,
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.add_book_title_label)) },
                placeholder = { if (fallbackTitle.isNotBlank()) Text(fallbackTitle) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(24.dp))

            if (error != null) {
                Text(error!!, color = Color(0xFFEF5350), modifier = Modifier.padding(bottom = 12.dp))
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
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = selectedUri != null || url.isNotBlank(),
                ) {
                    Text(stringResource(if (fromUrl) R.string.add_submit_download else R.string.add_submit))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    return fromProvider?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "book"
}
