package com.watchreader.mobile.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
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

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            // Extract filename from URI
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex("_display_name")
                    if (nameIndex >= 0) {
                        selectedFileName = it.getString(nameIndex)
                        if (title.isBlank()) {
                            title = selectedFileName.substringBeforeLast(".")
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(done) {
        if (done) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Book") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("<", fontSize = 20.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))

            // File picker
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("text/plain", "application/epub+zip")) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    if (selectedUri != null) selectedFileName else "Select .txt or .epub",
                    fontSize = 16.sp,
                )
            }

            Spacer(Modifier.height(24.dp))

            // Divider
            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f))
            Text(
                "or",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 12.dp),
            )

            // URL input
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL") },
                placeholder = { Text("https://...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))

            // Title input
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(24.dp))

            // Error
            if (error != null) {
                Text(error!!, color = Color(0xFFEF5350), modifier = Modifier.padding(bottom = 12.dp))
            }

            // Submit
            if (isLoading) {
                CircularProgressIndicator()
            } else {
                Button(
                    onClick = {
                        when {
                            selectedUri != null -> vm.addFromUri(
                                selectedUri!!,
                                title.ifBlank { selectedFileName.substringBeforeLast(".") },
                            )
                            url.isNotBlank() -> vm.addFromUrl(url, title)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = selectedUri != null || url.isNotBlank(),
                ) {
                    Text(if (url.isNotBlank() && selectedUri == null) "Download & Add" else "Add Book")
                }
            }
        }
    }
}
