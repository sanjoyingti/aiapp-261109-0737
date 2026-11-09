package com.aiapp.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiapp.builder.BuildEvent
import com.aiapp.builder.BuildRequest
import com.aiapp.builder.BuildRunner
import com.aiapp.builder.TimelineItem
import com.aiapp.llm.ProviderId
import kotlinx.coroutines.launch

private val resumeWords = Regex("^\\s*(retry|resume|continue|try again|go on|carry on|build now|rebuild)\\b", RegexOption.IGNORE_CASE)

/** Starts a build that continues the open project from its last checkpoint. */
private fun resumeBuild(chat: ChatViewModel, vm: BuildViewModel, text: String = "Retry") {
    val project = vm.project ?: return
    vm.start(
        BuildRequest(
            description = text,
            appName = project.appName,
            provider = chat.provider,
            model = chat.model,
            apiKey = chat.key(chat.provider),
            effort = chat.effort,
            githubToken = chat.githubToken(),
            allowOtherSites = false,
            project = project,
            resume = true,
        ),
    )
}

/** Build Mode, laid out like an IDE agent chat: your requests, live steps, files and results. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildScreen(
    chat: ChatViewModel,
    vm: BuildViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCodespace: (String) -> Unit = {},
) {
    var showFiles by rememberSaveable { mutableStateOf(false) }
    var showProjects by rememberSaveable { mutableStateOf(false) }
    var openFile by rememberSaveable { mutableStateOf<String?>(null) }
    val project = vm.project

    BackHandler(enabled = showFiles || openFile != null || showProjects) {
        if (openFile != null) openFile = null else if (showFiles) showFiles = false else showProjects = false
    }

    if (showProjects) {
        ProjectList(vm, onBack = { showProjects = false })
        return
    }

    openFile?.let { path ->
        CodeViewer(path, project?.files?.get(path).orEmpty(), onBack = { openFile = null })
        return
    }
    if (showFiles && project != null) {
        FileList(project.files.keys.toList(), onOpen = { openFile = it }, onBack = { showFiles = false })
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(vm.timeline.size) {
        if (vm.timeline.isNotEmpty()) listState.animateScrollToItem(vm.timeline.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(project?.appName ?: "Build app")
                        Text(
                            "${chat.provider.label} · ${chat.models.firstOrNull { it.id == chat.model }?.name ?: chat.model} · ${chat.effort.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = { onOpenCodespace(CodespaceSessionManager.URL_GITHUB_CODESPACES) }) { Text("Codespace") }
                    TextButton(onClick = { showProjects = true }) { Text("Projects") }
                    if (project != null) {
                        TextButton(onClick = { showFiles = true }) { Text("Files") }
                    }
                    if (vm.timeline.isNotEmpty()) {
                        TextButton(onClick = vm::newProject, enabled = !vm.running) { Text("New") }
                    }
                },
            )
        },
        bottomBar = { BuildComposer(chat, vm, onOpenSettings, onOpenCodespace) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OfflineReadyBanner(chat)
            if (vm.timeline.isEmpty()) {
                EmptyState(chat, Modifier.weight(1f), onOpenCodespace)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(vm.timeline) { index, item ->
                        val isLast = index == vm.timeline.lastIndex
                        when (item) {
                            is TimelineItem.User -> UserBubble(item.text)
                            is TimelineItem.Event -> {
                                EventRow(item.event, active = isLast && vm.running)
                                val prog = BuildRunner.progress
                                if (isLast && vm.running && prog != null) {
                                    Column(Modifier.padding(start = 30.dp)) { UploadBar(BuildRunner.progressText.ifBlank { "Working…" }, prog) }
                                }
                            }
                            is TimelineItem.Result -> ResultCard(
                                item,
                                onRetry = if (!item.success && isLast && !vm.running && vm.project != null) {
                                    { resumeBuild(chat, vm) }
                                } else null,
                                onOpenCodespace = onOpenCodespace,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(chat: ChatViewModel, modifier: Modifier, onOpenCodespace: (String) -> Unit) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Describe an app and I'll build it", style = MaterialTheme.typography.titleLarge)
        Text(
            "I write the code, build the APK on your GitHub, fix any errors, and give you a download link. " +
                "Afterwards, ask for changes and I'll update the same app.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Builds keep running if you close the app; you'll get a notification when it's ready.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (chat.provider == ProviderId.OFFLINE) {
            val m = chat.selectedOfflineModel()
            Text(
                "Using offline model: ${m?.name ?: chat.model} (Free · No API key needed).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onOpenCodespace(CodespaceSessionManager.URL_GITHUB_CODESPACES) }) {
                Text("Open GitHub Codespaces")
            }
            OutlinedButton(onClick = { onOpenCodespace(CodespaceSessionManager.URL_CHATGPT_CODEX) }) {
                Text("Open ChatGPT Codex")
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Spacer(Modifier.weight(1f))
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) { Text(text, modifier = Modifier.padding(12.dp)) }
    }
}

private fun iconFor(kind: BuildEvent.Kind): ImageVector = when (kind) {
    BuildEvent.Kind.THINKING -> Icons.Filled.Create
    BuildEvent.Kind.WEB -> Icons.Filled.Search
    BuildEvent.Kind.FILE -> Icons.Filled.Edit
    BuildEvent.Kind.LIBRARY -> Icons.Filled.Add
    BuildEvent.Kind.DOWNLOAD -> Icons.Filled.KeyboardArrowDown
    BuildEvent.Kind.BUILD -> Icons.Filled.Build
    BuildEvent.Kind.ERROR -> Icons.Filled.Warning
    BuildEvent.Kind.DONE -> Icons.Filled.Done
    BuildEvent.Kind.STEP -> Icons.Filled.CheckCircle
}

@Composable
private fun EventRow(e: BuildEvent, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (active) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            } else {
                Icon(
                    iconFor(e.kind),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (e.kind == BuildEvent.Kind.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            e.text + if (active) "…" else "",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (e.kind == BuildEvent.Kind.FILE) FontFamily.Monospace else null,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultCard(
    r: TimelineItem.Result,
    onRetry: (() -> Unit)? = null,
    onOpenCodespace: (String) -> Unit = {},
) {
    val uri = LocalUriHandler.current
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                r.message,
                style = MaterialTheme.typography.titleMedium,
                color = if (r.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            r.apkUrl?.let { url -> ApkDownload(url) }
            if (onRetry != null) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry from where it stopped") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.apkUrl?.let { url -> TextButton(onClick = { uri.openUri(url) }) { Text("Open in browser") } }
                r.apkUrl?.let { url ->
                    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                    TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(url)) }) { Text("Copy link") }
                }
                r.repoUrl?.let { url ->
                    TextButton(onClick = { uri.openUri(url) }) { Text("GitHub") }
                    TextButton(onClick = { onOpenCodespace(url.replace("github.com", "github.dev")) }) { Text("Codespace") }
                }
            }
            if (r.success) {
                Text(
                    "Ask for changes below — the new version installs over this one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BuildComposer(
    chat: ChatViewModel,
    vm: BuildViewModel,
    onOpenSettings: () -> Unit,
    onOpenCodespace: (String) -> Unit,
) {
    val project = vm.project
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var allowOther by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var unpacking by rememberSaveable { mutableStateOf(false) }

    val attached = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<com.aiapp.llm.Attachment>() }
    var attaching by rememberSaveable { mutableStateOf(false) }
    var attachPct by remember { mutableStateOf(0f) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        attaching = true
        scope.launch {
            uris.forEach { u ->
                try {
                    attachPct = 0f
                    attached += com.aiapp.data.AttachmentStore.import(context, u) { v -> scope.launch { attachPct = v } }
                } catch (e: Exception) {
                    problem = e.message ?: "Couldn't attach that file."
                }
            }
            attaching = false
        }
    }

    fun ready(): String? = when {
        chat.githubToken().isBlank() -> "Sign in with GitHub in Settings (or tap Sign in below) first."
        else -> null
    }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        problem = ready()
        if (problem != null) return@rememberLauncherForActivityResult
        unpacking = true
        scope.launch {
            try {
                attachPct = 0f
                val a = com.aiapp.data.AttachmentStore.import(context, uri) { v -> scope.launch { attachPct = v } }
                if (!a.name.endsWith(".zip", true)) {
                    problem = "Pick a .zip of an Android Studio project."
                } else {
                    if (android.os.Build.VERSION.SDK_INT >= 33) notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    vm.newProject()
                    vm.start(
                        BuildRequest(
                            description = "Compile ${a.name} to an APK",
                            appName = a.name.removeSuffix(".zip"),
                            provider = chat.provider,
                            model = chat.model,
                            apiKey = chat.key(chat.provider),
                            effort = chat.effort,
                            githubToken = chat.githubToken(),
                            allowOtherSites = allowOther,
                            zipPath = a.path,
                            zipName = a.name,
                        ),
                    )
                }
            } catch (e: Exception) {
                problem = e.message ?: "Couldn't read that file."
            } finally {
                unpacking = false
            }
        }
    }

    Surface(tonalElevation = 2.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            problem?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (chat.githubToken().isBlank()) {
                        TextButton(onClick = {
                            problem = null
                            chat.signInGithub { link -> onOpenCodespace(link) }
                        }) { Text("Sign in") }
                    }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                }
            }
            if (vm.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Working…", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = vm::stop) { Text("Stop") }
                }
                return@Column
            }
            if (project == null) {
                OutlinedButton(
                    onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    enabled = !unpacking,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (unpacking) "Reading project…" else "Upload Android Studio project (.zip) → APK") }
                if (unpacking) UploadBar("Copying your project…", attachPct)
                Text(
                    "…or describe a new app:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("App name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (attaching) UploadBar("Attaching…", attachPct)
            if (attached.isNotEmpty() && !attaching) {
                Text(
                    "✓ ${attached.size} file${if (attached.size == 1) "" else "s"} ready",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (attached.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    attached.toList().forEach { a ->
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                                Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                TextButton(onClick = { attached.remove(a) }) { Text("✕") }
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Attach files")
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(if (project == null) "Describe the app…" else "Ask for a change…") },
                    maxLines = 6,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val zip = attached.firstOrNull { it.isZip }
                    if (project == null && zip != null) {
                        problem = ready()
                        if (problem == null) {
                            if (android.os.Build.VERSION.SDK_INT >= 33) notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            vm.newProject()
                            vm.start(
                                BuildRequest(
                                    description = text.trim().ifBlank { "Compile ${zip.name} to an APK" },
                                    appName = zip.name.removeSuffix(".zip"),
                                    provider = chat.provider,
                                    model = chat.model,
                                    apiKey = chat.key(chat.provider),
                                    effort = chat.effort,
                                    githubToken = chat.githubToken(),
                                    allowOtherSites = allowOther,
                                    zipPath = zip.path,
                                    zipName = zip.name,
                                ),
                            )
                            attached.clear()
                            text = ""
                        }
                        return@Button
                    }
                    if (project != null && attached.isEmpty() && resumeWords.containsMatchIn(text)) {
                        problem = ready()
                        if (problem == null) {
                            resumeBuild(chat, vm, text.trim())
                            text = ""
                        }
                        return@Button
                    }
                    problem = when {
                        text.isBlank() && attached.isEmpty() -> "Describe what you want."
                        project == null && name.isBlank() -> "Give the app a name."
                        chat.githubToken().isBlank() -> "Sign in with GitHub first."
                        chat.provider.needsKey && chat.key(chat.provider).isBlank() -> "Add your ${chat.provider.label} key in Settings (or switch to Offline for free models)."
                        chat.model.isBlank() -> "Pick a model on the chat screen first."
                        else -> null
                    }
                    if (problem == null) {
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        vm.start(
                            BuildRequest(
                                description = text.trim(),
                                appName = name.trim(),
                                provider = chat.provider,
                                model = chat.model,
                                apiKey = chat.key(chat.provider),
                                effort = chat.effort,
                                githubToken = chat.githubToken(),
                                allowOtherSites = allowOther,
                                project = project,
                                attachments = attached.toList(),
                            ),
                        )
                        text = ""
                        attached.clear()
                    }
                }) { Text(if (project == null) "Build" else "Send") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Allow downloads from any site",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = allowOther, onCheckedChange = { allowOther = it })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileList(paths: List<String>, onOpen: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Files") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            itemsIndexed(paths.sorted()) { _, path ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(path) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(path.substringAfterLast('/'), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    Text(
                        path.removePrefix("app/src/main/").substringBeforeLast('/', ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodeViewer(path: String, content: String, onBack: () -> Unit) {
    val lines = content.lines()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(path.substringAfterLast('/'), fontFamily = FontFamily.Monospace) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(padding).fillMaxSize()) {
            SelectionContainer {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    lines.forEachIndexed { i, line ->
                        Row {
                            Text(
                                "${i + 1}".padStart(4),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectList(vm: BuildViewModel, onBack: () -> Unit) {
    var version by remember { mutableStateOf(0) }
    val projects = remember(version) { vm.projects() }
    val fmt = remember { java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    TextButton(onClick = {
                        vm.newProject()
                        onBack()
                    }, enabled = !vm.running) { Text("New project") }
                },
            )
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Text("No projects yet. Describe an app or upload a .zip to start one.", modifier = Modifier.padding(padding).padding(24.dp))
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            itemsIndexed(projects) { _, p ->
                val current = p.id == BuildRunner.projectId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !vm.running || current) {
                            if (vm.openProject(p.id) || current) onBack()
                        }
                        .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title + if (current) "  (open)" else "", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        val status = when {
                            current && vm.running -> "Building now…"
                            p.lastResult?.success == true -> "APK ready"
                            p.lastResult != null -> "Last build failed"
                            else -> "In progress"
                        }
                        Text(
                            "${fmt.format(java.util.Date(p.updated))} · $status" + (if (p.project?.imported == true) " · uploaded" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!(current && vm.running)) {
                        TextButton(onClick = {
                            vm.deleteProject(p.id)
                            version++
                        }) { Text("Delete") }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/** Downloads the APK in the app with a progress bar, checks it, then opens Android's installer. */
@Composable
private fun ApkDownload(url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var size by remember(url) { mutableStateOf<Long?>(null) }
    var progress by remember(url) { mutableStateOf<Float?>(null) }
    var file by remember(url) { mutableStateOf<java.io.File?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(url) { size = com.aiapp.data.ApkInstaller.check(url) }

    fun install(f: java.io.File) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + context.packageName),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            error = "Allow \"Install unknown apps\" for AI App, come back, and tap Install."
            return
        }
        runCatching { com.aiapp.data.ApkInstaller.install(context, f) }.onFailure { error = it.message }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val f = file
        val p = progress
        when {
            f != null -> {
                Text("✓ Downloaded ${f.name} — also saved to Downloads/AIApp", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Button(onClick = { install(f) }, modifier = Modifier.fillMaxWidth()) { Text("Install app") }
            }
            p != null -> UploadBar("Downloading APK…", p)
            else -> Button(
                onClick = {
                    error = null
                    progress = 0f
                    scope.launch {
                        try {
                            val downloaded = com.aiapp.data.ApkInstaller.download(context, url) { v ->
                                scope.launch { progress = v }
                            }
                            file = downloaded
                            install(downloaded)
                        } catch (e: Exception) {
                            error = e.message ?: "Download failed."
                        } finally {
                            progress = null
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                val s = size
                Text("Download & install APK" + if (s != null) " (" + (if (s < 1048576) "${s / 1024} KB" else "%.1f MB".format(s / 1048576.0)) + ")" else "")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
