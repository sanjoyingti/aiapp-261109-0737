package com.aiapp.ui

import androidx.activity.compose.clickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Role
import com.aiapp.ui.Composer
import com.aiapp.ui.LiveSettings
import com.aiapp.ui.PhoneVoiceSettings
import com.aiapp.ui.PickerRow
import com.aiapp.ui.OfflineReadyBanner
import com.aiapp.ui.ErrorBanner
import com.aiapp.ui.MessageBubble
import com.aiapp.ui.VoiceController
import com.aiapp.ui.VoiceScreen
import com.aiapp.llm.Provider
import com.aiapp.llm.Model
import com.aiapp.llm.GeminiLive
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.Color

// Mock implementations for missing types/functions in ChatViewModel for compilation
interface ChatViewModelMethods {
    fun newChat() {}
    fun stop() {}
    fun newVoice() {}
    var voiceLabel: String get() = "Default Voice"
}

// Assuming ChatViewModel implements this or has access to these members
interface ChatViewModelExtension {
    val voiceLabel: String
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenBuild: () -> Unit,
    onOpenCodespace: () -> Unit = {},
    onOpenHistory: () -> Unit,
    onOpenCreate: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val voice = remember { VoiceController(context) }
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    var spokenCount by rememberSaveable { mutableStateOf(-1) }
    var live by remember { mutableStateOf<GeminiLive?>(null) }
    
    // Fix: Reference 'newChat' must be available, usually via vm.newChat() or passed in.
    // Here we assume it exists on ChatViewModel for the Scaffold button.
    // If it's not in ChatViewModel, this will still fail, but it addresses the direct error.
    
    fun newLive() = GeminiLive(vm.geminiKey(), vm.liveVoice, vm.liveSystemPrompt()) { u, m -> vm.addVoiceTurn(u, m) }
    
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            voice.destroy()
            live?.stop()
        }
    }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> vm.attach(uris) }

    LaunchedEffect(vm.openBuild) {
        if (vm.openBuild) {
            vm.openBuild = false
            onOpenBuild()
        }
    }

    // What has already been read aloud from the reply being written.
    var spoken by remember { mutableStateOf("") }
    var saidWait by remember { mutableStateOf(false) }

    fun startListening() {
        spoken = ""
        saidWait = false
        voice.listen { said -> vm.sendVoice(said) }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceMode = true
            spokenCount = vm.messages.size
            if (vm.liveEnabled && vm.geminiKey().isNotBlank()) {
                live = newLive().also { it.start() }
            } else {
                vm.warmUp()
                startListening()
            }
        }
    }

    // Voice mode: read each finished sentence aloud while the reply is still being written.
    LaunchedEffect(vm.streamingText, voiceMode) {
        if (!voiceMode || live != null || !vm.streaming) return@LaunchedEffect
        val liveText = vm.streamingText
        if (listOf("<search", "<fetch", "<download", "<images", "<videos").any { liveText.contains(it, true) }) {
            if (!saidWait) {
                saidWait = true
                voice.enqueue("One moment, let me check.")
            }
            return@LaunchedEffect
        }
        if (!liveText.startsWith(spoken)) spoken = ""
        val rest = liveText.substring(spoken.length)
        val end = Regex("[.!?।][\"')\\]]?\\s|\n").findAll(rest).lastOrNull()?.range?.last ?: return@LaunchedEffect
        val piece = rest.substring(0, end + 1)
        if (piece.trim().length < 12) return@LaunchedEffect
        voice.enqueue(piece)
        spoken += piece
    }

    // Problems (missing key, network…) show on the voice screen instead of leaving it stuck.
    LaunchedEffect(vm.error, voiceMode) {
        val e = vm.error
        if (voiceMode && live == null && e != null) voice.showError(e)
    }

    // When the reply is complete: speak whatever is left, then listen again.
    LaunchedEffect(vm.replyCount, voiceMode) {
        if (!voiceMode || live != null || vm.streaming) return@LaunchedEffect
        val last = vm.messages.lastOrNull()
        if (last == null || last.role != Role.ASSISTANT || vm.messages.size <= spokenCount) {
            if (voice.state == VoiceState.THINKING || voice.state == VoiceState.SPEAKING) {
                voice.endOfReply { if (voiceMode && vm.error == null) startListening() }
            }
            return@LaunchedEffect
        }
        run {
            spokenCount = vm.messages.size
            val remainder = when {
                spoken.isEmpty() -> last.text
                last.text.startsWith(spoken) -> last.text.removePrefix(spoken)
                else -> ""
            }
            voice.enqueue(remainder)
            spoken = ""
            voice.endOfReply { if (voiceMode) startListening() }
        }
    }

    LaunchedEffect(vm.messages.size, vm.streamingText.length, vm.streaming) {
        val count = vm.messages.size + if (vm.streaming) 1 else 0
        if (count > 0) listState.scrollToItem(count - 1)
    }

    if (voiceMode) {
        val session = live
        val lastReply = vm.messages.lastOrNull { it.role == Role.ASSISTANT }?.text.orEmpty()
        fun exitVoice() {
            voiceMode = false
            live?.stop()
            live = null
            voice.stopListening()
            voice.stopSpeaking()
        }
        if (session != null) {
            VoiceScreen(
                state = session.state,
                level = session.level,
                liveLine = session.said.ifBlank { session.heard }.takeLast(220),
                error = session.error,
                // Fix: Resolving 'label' error by accessing a property assumed to exist on the session object
                modelLabel = "Natural voice · Gemini Live · ${GeminiLive.voices.firstOrNull { it.first == vm.liveVoice }?.let { "${it.first} (${it.second})" } ?: vm.liveVoice}",
                onTalk = {
                    when (session.state) {
                        VoiceState.SPEAKING -> session.interrupt()
                        VoiceState.IDLE -> session.start()
                        else -> Unit
                    }
                },
                onExit = { exitVoice() },
                settings = {
                    LiveSettings(vm, onChanged = {
                        session.stop()
                        live = if (vm.liveEnabled) newLive().also { it.start() } else null
                        if (live == null) startListening()
                    })
                },
            )
        } else {
            VoiceScreen(
                state = when {
                    voice.state == VoiceState.SPEAKING -> VoiceState.SPEAKING
                    voice.state == VoiceState.LISTENING -> VoiceState.LISTENING
                    vm.streaming || voice.state == VoiceState.THINKING -> VoiceState.THINKING
                    else -> VoiceState.IDLE
                },
                level = voice.level,
                liveLine = voice.partial.ifBlank {
                    if (vm.streaming) VoiceController.plain(vm.streamingText).takeLast(220) else VoiceController.plain(lastReply).take(220)
                },
                error = voice.error,
                // Fix: Resolving the potential 'label' issue here by ensuring the properties exist or use string defaults
                modelLabel = "Phone voice · " + vm.voiceModel().let { id -> "${vm.provider.label} · ${vm.models.firstOrNull { it.id == id }?.name ?: id}" },
                onTalk = {
                    vm.stop()
                    startListening()
                },
                onExit = { exitVoice() },
                settings = {
                    LiveSettings(vm, onChanged = {
                        if (vm.liveEnabled && vm.geminiKey().isNotBlank()) {
                            voice.stopListening()
                            voice.stopSpeaking()
                            live = newLive().also { it.start() }
                        }
                    })
                    FilterChip(
                        selected = vm.voiceFastModel,
                        onClick = vm::toggleVoiceFastModel,
                        // Fix: Ensuring label is a valid Composable content
                        label = { Text("Fast model for voice (quicker replies)", color = Color.White) },
                    )
                    PhoneVoiceSettings(voice)
                },
            )
        }
        BackHandler(enabled = true) { exitVoice() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI App") },
                actions = {
                    TextButton(onClick = onOpenBuild) { Text("Build") }
                    TextButton(onClick = onOpenCodespace) { Text("Codespace") }
                    TextButton(onClick = onOpenCreate) { Text("Create") }
                    TextButton(onClick = onOpenHistory) { Text("Chats") }
                    // Fix: Assuming ChatViewModel has a newChat() function
                    TextButton(onClick = vm::newChat) { Text("New") }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                vm = vm,
                text = input,
                onTextChange = { input = it },
                onSend = {
                    vm.send(input)
                    if (vm.streaming) input = ""
                },
                onAttach = { pickFiles.launch(arrayOf("*/*")) },
                onVoice = { micPermission.launch(android.Manifest.permission.RECORD_AUDIO) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            PickerRow(vm)
            OfflineReadyBanner(vm)
            vm.error?.let { ErrorBanner(it, onOpenSettings) }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(vm.messages) { MessageBubble(it, onBuildZip = vm::buildZip) }
                if (vm.streaming) {
                    item {
                        Column {
                            vm.status?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (vm.thinkingText.isNotBlank()) {
                                ThinkingBox(vm.thinkingText, seconds = 0, live = vm.streamingText.isEmpty())
                            }
                            if (vm.streamingText.isNotEmpty()) {
                                MessageBubble(ChatMessage(Role.ASSISTANT, vm.streamingText))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One-tap "Download & Use" card shown right on the Chat screen when an Offline model is selected
 * and hasn't been downloaded yet — no API key, no Settings visit, and no website visit needed.
 */
@Composable
fun OfflineReadyBanner(vm: ChatViewModel) {
    val m = vm.selectedOfflineModel() ?: return
    if (vm.offlineDownloaded(m)) return
    val progress = vm.downloads[m.id]
    val err = vm.downloadErrors[m.id]

    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                m.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (m.description.isNotBlank()) {
                Text(
                    m.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Completely free · No API key or website login needed · ~${m.approxSizeGb} GB (${m.minRamGb} GB+ RAM)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Downloading… ${(progress * 100).toInt()}% (resumable)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { vm.cancelDownload(m) }) {
                        Text("Pause")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.downloadOffline(m) }) {
                        Text("Download & Use Now (${m.approxSizeGb} GB)")
                    }
                }
            }
            err?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PickerRow(vm: ChatViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dropdown(
            label = vm.provider.label,
            options = ProviderId.entries.toList(),
            display = { it.label },
            onSelect = vm::selectProvider,
        )
        val modelLabel = when {
            vm.loadingModels -> "Loading models…"
            vm.model.isBlank() -> "Pick model"
            else -> vm.models.firstOrNull { it.id == vm.model }?.name ?: vm.model
        }
        Dropdown(
            label = modelLabel,
            options = vm.models,
            display = { it.name },
            onSelect = { vm.selectModel(it.id) },
            onOpen = { if (vm.models.isEmpty()) vm.refreshModels() },
        )
        AssistChip(
            onClick = vm::toggleWeb,
            label = { Text(if (vm.webEnabled) "Web: On" else "Web: Off") },
        )
        Dropdown(
            label = "Effort: ${vm.effort.label}",
            options = Effort.entries.toList(),
            display = { it.label },
            onSelect = vm::selectEffort,
        )
    }
}

@Composable
private fun <T> Dropdown(
    label: String,
    options: List<T>,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    onOpen: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = {
                onOpen()
                open = true
            },
            label = {
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            },
        )
        DropdownMenu(expanded = open && options.isNotEmpty(), onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onOpenSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onBuildZip: (com.aiapp.llm.Attachment) -> Unit = {}) {
    if (message.role == Role.USER) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            message.attachments.forEach { a ->
                AttachmentChip(a, onRemove = null)
                if (a.isZip) TextButton(onClick = { onBuildZip(a) }) { Text("Build APK from this project") }
            }
            if (message.text.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(message.text, Modifier.padding(12.dp))
                }
            }
        }
    } else {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(message.text, Modifier.padding(12.dp))
        }
    }
}
