package com.aiapp.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aiapp.llm.ProviderId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(vm: CreateViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pickPhoto = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.attach(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !vm.video, onClick = { vm.setMode(false) }, label = { Text("Image") })
                FilterChip(selected = vm.video, onClick = { vm.setMode(true) }, label = { Text("Video") })
            }
            if (!vm.video) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = vm.imageProvider == ProviderId.GEMINI, onClick = { vm.setProvider(ProviderId.GEMINI) }, label = { Text("Gemini") })
                    FilterChip(selected = vm.imageProvider == ProviderId.OPENAI, onClick = { vm.setProvider(ProviderId.OPENAI) }, label = { Text("ChatGPT") })
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = vm.aspect == "16:9", onClick = { vm.aspect = "16:9" }, label = { Text("Landscape 16:9") })
                    FilterChip(selected = vm.aspect == "9:16", onClick = { vm.aspect = "9:16" }, label = { Text("Portrait 9:16") })
                }
            }
            Column {
                OutlinedButton(onClick = { modelMenu = true }, enabled = vm.models.isNotEmpty()) {
                    Text(vm.models.firstOrNull { it.id == vm.model }?.name ?: if (vm.models.isEmpty()) "Loading models…" else "Pick a model")
                }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    vm.models.forEach { m ->
                        DropdownMenuItem(text = { Text(m.name) }, onClick = {
                            vm.model = m.id
                            modelMenu = false
                        })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickPhoto.launch(arrayOf("image/*")) }) {
                    Text(if (vm.reference == null) "+ Add a photo" else "Change photo")
                }
                vm.reference?.let { ref ->
                    val bmp = remember(ref.path) { BitmapFactory.decodeFile(ref.path, BitmapFactory.Options().apply { inSampleSize = 4 }) }
                    bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp)) }
                    TextButton(onClick = { vm.reference = null }) { Text("Remove") }
                }
            }
            if (vm.reference != null) {
                Text(
                    if (vm.video) "The video will start from this photo." else "Describe how to change this photo.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(if (vm.video) "Describe the video (scene, camera, sound)" else "Describe the image") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (vm.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(vm.status.orEmpty(), modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                }
            } else {
                Button(onClick = { vm.generate(prompt) }, enabled = prompt.isNotBlank() && vm.model.isNotBlank()) {
                    Text(if (vm.video) "Create video" else "Create image")
                }
                vm.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            vm.error?.let {
                Row {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                }
            }
            vm.resultBytes?.let { bytes ->
                val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                bmp?.let {
                    Image(it.asImageBitmap(), contentDescription = "Generated image", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                }
            }
            vm.resultUri?.let { uri ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (vm.resultIsVideo) "video/mp4" else "image/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(view) }
                    }) { Text(if (vm.resultIsVideo) "Play video" else "Open") }
                    OutlinedButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).setType(if (vm.resultIsVideo) "video/mp4" else "image/*")
                            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(Intent.createChooser(share, "Share")) }
                    }) { Text("Share") }
                }
            }
        }
    }
}
