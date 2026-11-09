package com.aiapp.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiapp.builder.GitHubLogin
import com.aiapp.data.ApiKeys
import com.aiapp.data.ChatStore
import com.aiapp.data.Conversation
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.ModelInfo
import com.aiapp.llm.OfflineModel
import com.aiapp.llm.ProviderException
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Providers
import com.aiapp.llm.Role
import com.aiapp.llm.WebTools
import com.aiapp.llm.alternativeModel
import com.aiapp.llm.resilient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val keys = ApiKeys(app)

    init {
        Providers.init(app)
        WebTools.downloader = { url -> com.aiapp.data.Downloads.save(app, url) }
        Providers.offline.hfToken = keys.getExtra("HF")
    }
    private val prefs = app.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)

    val messages = mutableStateListOf<ChatMessage>()

    private val store = ChatStore(app)
    private var conversationId: String = prefs.getString("current_chat", null) ?: UUID.randomUUID().toString()

    /** Files picked for the next message. */
    val pendingAttachments = mutableStateListOf<com.aiapp.llm.Attachment>()
    var attaching by mutableStateOf(false)
        private set

    /** Files being attached right now: name → progress 0..1 (-1 = size unknown). */
    val attachProgress = mutableStateMapOf<String, Float>()

    fun attach(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        attaching = true
        viewModelScope.launch {
            uris.forEachIndexed { i, uri ->
                val label = "file ${i + 1}"
                attachProgress[label] = 0f
                try {
                    val a = com.aiapp.data.AttachmentStore.import(getApplication(), uri) { p ->
                        viewModelScope.launch { if (label in attachProgress) attachProgress[label] = p }
                    }
                    pendingAttachments += a
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    error = e.message ?: "Couldn't attach that file."
                } finally {
                    attachProgress.remove(label)
                }
            }
            attaching = false
        }
    }

    fun removeAttachment(a: com.aiapp.llm.Attachment) {
        pendingAttachments.remove(a)
    }

    /** Saved chats for the Chats screen, newest first. */
    var history by mutableStateOf<List<Conversation>>(emptyList())
        private set

    var streamingText by mutableStateOf("")

    private val textBuf = StringBuilder()
    private val thinkBuf = StringBuilder()
    private var lastFlush = 0L

    private fun flushStream() {
        if (textBuf.isNotEmpty()) {
            streamingText += textBuf
            textBuf.clear()
        }
        if (thinkBuf.isNotEmpty()) {
            thinkingText += thinkBuf
            thinkBuf.clear()
        }
    }

    /** The model's reasoning for the reply being written (live). */
    var thinkingText by mutableStateOf("")
        private set
    private var thinkingStart = 0L
    private var thoughtSeconds = 0
        private set
    var streaming by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /**
     * Defaults to FREE_CLOUD (100% free remote Qwen 2.5 Coder 32B / DeepSeek R1 / Uncensored Pro —
     * no API key, no download, and no browser visit needed) when no paid API keys are set.
     */
    private val defaultProvider: ProviderId = run {
        val hasAnyCloudKey = ProviderId.entries.filter { it.needsKey }.any { keys.get(it).isNotBlank() }
        if (hasAnyCloudKey) ProviderId.CLAUDE else ProviderId.FREE_CLOUD
    }

    var provider by mutableStateOf(
        enumValueOrDefault(prefs.getString("provider", null), defaultProvider),
    )
        private set
    var models by mutableStateOf<List<ModelInfo>>(emptyList())
        private set
    var loadingModels by mutableStateOf(false)
        private set
    var model by mutableStateOf(
        prefs.getString("model_${provider.name}", "").orEmpty().ifBlank {
            when (provider) {
                ProviderId.FREE_CLOUD -> "qwen-coder"
                ProviderId.OFFLINE -> Providers.offline.recommended().id
                else -> ""
            }
        },
    )
        private set
    var effort by mutableStateOf(
        enumValueOrDefault(prefs.getString("effort", null), Effort.AUTO),
    )
        private set

    /** Lets the model search the web and read pages (works for offline and free cloud models too). */
    var webEnabled by mutableStateOf(prefs.getBoolean("web", true))
        private set

    fun toggleWeb() {
        webEnabled = !webEnabled
        prefs.edit().putBoolean("web", webEnabled).apply()
    }

    private var job: Job? = null

    /** Download progress (0..1) for offline models being downloaded, by model id. */
    val downloads = mutableStateMapOf<String, Float>()
    val downloadErrors = mutableStateMapOf<String, String>()
    private val downloadJobs = mutableMapOf<String, Job>()

    /** Bumped when an offline model is downloaded or deleted, so the UI re-reads file state. */
    var offlineVersion by mutableStateOf(0)
        private set

    fun deviceRamGb(): Int = Providers.offline.deviceRamGb()

    fun recommendedOffline(): OfflineModel = Providers.offline.recommended()

    fun offlineDownloaded(m: OfflineModel): Boolean {
        offlineVersion // read so Compose recomposes after changes
        return Providers.offline.isDownloaded(m)
    }

    fun selectedOfflineModel(): OfflineModel? {
        if (provider != ProviderId.OFFLINE) return null
        offlineVersion
        return Providers.offline.byId(model) ?: Providers.offline.recommended()
    }

    fun downloadOffline(m: OfflineModel) {
        if (downloadJobs[m.id]?.isActive == true) return
        downloadErrors.remove(m.id)
        downloads[m.id] = 0f
        downloadJobs[m.id] = viewModelScope.launch {
            try {
                Providers.offline.download(m) { p -> viewModelScope.launch { if (m.id in downloads) downloads[m.id] = p } }
                offlineVersion++
                if (provider == ProviderId.OFFLINE) refreshModels()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                downloadErrors[m.id] = friendly(e)
            } finally {
                downloads.remove(m.id)
                downloadJobs.remove(m.id)
            }
        }
    }

    fun cancelDownload(m: OfflineModel) {
        downloadJobs[m.id]?.cancel()
    }

    fun deleteOffline(m: OfflineModel) {
        viewModelScope.launch {
            Providers.offline.delete(m)
            if (m.id.startsWith("hf-")) Providers.offline.removeCustom(m)
            offlineVersion++
            if (provider == ProviderId.OFFLINE) refreshModels()
        }
    }

    /** Switches the chat to this offline model (and starts downloading it if not yet downloaded). */
    fun useOffline(m: OfflineModel) {
        selectProvider(ProviderId.OFFLINE)
        selectModel(m.id)
        if (!Providers.offline.isDownloaded(m)) {
            downloadOffline(m)
        }
    }

    init {
        store.load(conversationId)?.let { messages.addAll(it.messages) }
        if (githubToken().isNotBlank() && githubUser.isBlank()) {
            refreshGithubUser()
        }
        refreshModels()
    }

    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = store.list()
            launch(Dispatchers.Main) { history = list }
        }
    }

    fun openChat(id: String) {
        stop()
        val c = store.load(id) ?: return
        conversationId = c.id
        prefs.edit().putString("current_chat", c.id).apply()
        messages.clear()
        messages.addAll(c.messages)
        error = null
    }

    fun deleteChat(id: String) {
        store.delete(id)
        if (id == conversationId) newChat()
        refreshHistory()
    }

    private fun persist() {
        if (messages.isEmpty()) return
        val title = messages.firstOrNull { it.role == Role.USER }?.text?.lines()?.firstOrNull()?.take(60) ?: "Chat"
        val c = Conversation(
            id = conversationId,
            title = title,
            updated = System.currentTimeMillis(),
            provider = provider.label,
            model = model,
            messages = messages.toList(),
        )
        prefs.edit().putString("current_chat", conversationId).apply()
        viewModelScope.launch(Dispatchers.IO) { store.save(c) }
    }

    fun key(p: ProviderId): String = keys.get(p)

    fun githubToken(): String = keys.getExtra("GITHUB")

    fun hfToken(): String = keys.getExtra("HF")

    fun saveHfToken(value: String) {
        keys.setExtra("HF", value)
        Providers.offline.hfToken = value.trim()
    }

    /** Built-in and user-added offline models. */
    fun offlineModels(): List<OfflineModel> {
        offlineVersion // recompose when the list changes
        return Providers.offline.allModels()
    }

    /** Adds an offline model from a Hugging Face link. Returns an error message, or null on success. */
    fun addOfflineModel(link: String): String? {
        val m = Providers.offline.addCustom(link) ?: return "Paste a Hugging Face link like huggingface.co/litert-community/Qwen2.5-Coder-3B-Instruct"
        offlineVersion++
        if (provider == ProviderId.OFFLINE) refreshModels()
        downloadOffline(m)
        return null
    }

    fun saveGithubToken(value: String) {
        keys.setExtra("GITHUB", value)
        githubUser = if (value.isBlank()) "" else githubUser
        if (value.isNotBlank()) refreshGithubUser()
    }

    // ---- Sign in with GitHub (Device Flow + OAuth Redirect URI capture) ----

    var githubUser by mutableStateOf(keys.getExtra("GITHUB_USER"))
        private set
    var githubCode by mutableStateOf<GitHubLogin.DeviceCode?>(null)
        private set
    var githubLoginError by mutableStateOf<String?>(null)
        private set
    private var loginJob: Job? = null

    fun githubClientId(): String = keys.getExtra("GITHUB_CLIENT_ID").ifBlank { GitHubLogin.DEFAULT_CLIENT_ID }

    fun saveGithubClientId(value: String) = keys.setExtra("GITHUB_CLIENT_ID", value)

    /** Handles incoming `aiapp://` OAuth redirect intents from external browsers or WebViews. */
    fun handleOAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme?.lowercase() != "aiapp") return
        GitHubLogin.extractTokenFromUri(uri)?.let { token ->
            saveGithubToken(token)
            githubLoginError = null
            githubCode = null
            loginJob?.cancel()
        }
    }

    /**
     * Starts GitHub sign-in, automatically copies the one-time user code to the clipboard,
     * and opens [openBrowser] (either the in-app Codespace WebView or external browser).
     */
    fun signInGithub(openBrowser: (String) -> Unit) {
        val clientId = githubClientId()
        if (clientId.isBlank()) {
            githubLoginError = "Add the GitHub Client ID first (see the steps below)."
            return
        }
        loginJob?.cancel()
        githubLoginError = null
        loginJob = viewModelScope.launch {
            try {
                val code = GitHubLogin.start(clientId)
                githubCode = code
                runCatching {
                    val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("GitHub Device Code", code.userCode))
                }
                openBrowser(code.verificationUriComplete ?: code.verificationUri)
                val token = GitHubLogin.waitForToken(clientId, code)
                keys.setExtra("GITHUB", token)
                refreshGithubUser()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                githubLoginError = e.message ?: "Sign-in failed."
            } finally {
                githubCode = null
            }
        }
    }

    fun signOutGithub() {
        loginJob?.cancel()
        keys.setExtra("GITHUB", "")
        keys.setExtra("GITHUB_USER", "")
        githubUser = ""
    }

    private fun refreshGithubUser() {
        val token = keys.getExtra("GITHUB")
        viewModelScope.launch {
            var result: Result<String> = Result.failure(Exception())
            for (attempt in 0 until 3) {
                result = runCatching { com.aiapp.builder.GitHubClient(token).login() }
                if (result.isSuccess) break
                kotlinx.coroutines.delay(3000)
            }
            result.onSuccess {
                githubUser = it
                keys.setExtra("GITHUB_USER", it)
            }
        }
    }

    fun saveKey(p: ProviderId, value: String) {
        keys.set(p, value)
        if (p == provider) refreshModels()
    }

    fun selectProvider(p: ProviderId) {
        if (p == provider) return
        provider = p
        prefs.edit().putString("provider", p.name).apply()
        model = prefs.getString("model_${p.name}", "").orEmpty()
        models = emptyList()
        refreshModels()
    }

    fun selectModel(id: String) {
        model = id
        prefs.edit().putString("model_${provider.name}", id).apply()
    }

    fun selectEffort(e: Effort) {
        effort = e
        prefs.edit().putString("effort", e.name).apply()
    }

    fun refreshModels() {
        val p = provider
        val k = keys.get(p)
        if (p.needsKey && k.isBlank()) {
            error = "Add your ${p.label} API key in Settings, or switch Provider to Free Cloud (No Key) or Offline."
            return
        }
        loadingModels = true
        viewModelScope.launch {
            try {
                val list = Providers.rankedModels(p, k)
                if (p == provider) {
                    models = list
                    error = null
                    if (list.none { it.id == model }) {
                        val defaultId = when (p) {
                            ProviderId.FREE_CLOUD -> "qwen-coder"
                            ProviderId.OFFLINE -> Providers.offline.recommended().id
                            else -> list.firstOrNull()?.id
                        }
                        defaultId?.let { selectModel(it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (p == provider) error = friendly(e)
            } finally {
                loadingModels = false
            }
        }
    }

    /** Set when chat starts a build, so the screen can switch to Build. */
    var openBuild by mutableStateOf(false)

    private val buildWords = Regex("\\b(apk|build|compile|compil)", RegexOption.IGNORE_CASE)

    /** Compiles an uploaded Android Studio project (.zip) into an APK on GitHub. */
    fun buildZip(zip: com.aiapp.llm.Attachment) {
        val token = githubToken()
        if (token.isBlank()) {
            error = "Sign in with GitHub in Settings first, then try again."
            return
        }
        if (com.aiapp.builder.BuildRunner.running) {
            error = "A build is already running. Open Build to see it."
            return
        }
        val app = getApplication<Application>()
        com.aiapp.builder.BuildRunner.newProject(app)
        com.aiapp.builder.BuildService.start(
            app,
            com.aiapp.builder.BuildRequest(
                description = "Compile ${zip.name} to an APK",
                appName = zip.name.removeSuffix(".zip"),
                provider = provider,
                model = model,
                apiKey = keys.get(provider),
                effort = effort,
                githubToken = token,
                allowOtherSites = false,
                zipPath = zip.path,
                zipName = zip.name,
            ),
        )
        messages += ChatMessage(
            Role.ASSISTANT,
            "Building **${zip.name}** into an APK on your GitHub. If the build hits errors, ${provider.label} will try to fix them. " +
                "Follow the progress on the Build screen; you'll also get a notification when it's ready.",
        )
        persist()
        openBuild = true
    }

    // ---- Natural voice (Gemini Live) ----

    /** Use Gemini Live's human-like voice when a Gemini key is set (on by default). */
    var liveEnabled by mutableStateOf(prefs.getBoolean("live", true))
        private set
    var liveVoice by mutableStateOf(prefs.getString("live_voice", "Puck") ?: "Puck")
        private set

    fun toggleLive() {
        liveEnabled = !liveEnabled
        prefs.edit().putBoolean("live", liveEnabled).apply()
    }

    fun chooseLiveVoice(name: String) {
        liveVoice = name
        prefs.edit().putString("live_voice", name).apply()
    }

    fun geminiKey(): String = keys.get(ProviderId.GEMINI)

    /** Instructions for Gemini Live, including the recent conversation so it can pick up where you left off. */
    fun liveSystemPrompt(): String = buildString {
        append(
            "You are a warm, witty, quick-thinking voice assistant, like a smart friend on a phone call. " +
                "Speak naturally and expressively: vary your tone, react with genuine emotion, and laugh when " +
                "something is funny. Keep answers short and conversational unless asked for detail; never read " +
                "out lists, links or code. Answer in the language the user speaks. Use Google Search for anything current.",
        )
        val recent = messages.takeLast(12).filter { it.text.isNotBlank() }
        if (recent.isNotEmpty()) {
            append("\n\nThe conversation so far (continue from here):\n")
            recent.forEach { m ->
                append(if (m.role == Role.USER) "User: " else "You: ")
                append(m.text.take(600).replace("\n", " "))
                append("\n")
            }
        }
    }

    /** Saves a spoken exchange to the chat. */
    fun addVoiceTurn(userSaid: String, assistantSaid: String) {
        if (userSaid.isNotBlank()) messages += ChatMessage(Role.USER, userSaid)
        if (assistantSaid.isNotBlank()) messages += ChatMessage(Role.ASSISTANT, assistantSaid)
        persist()
    }

    /** Use the provider's fastest smart model for voice (on by default). */
    var voiceFastModel by mutableStateOf(prefs.getBoolean("voice_fast", true))
        private set

    fun toggleVoiceFastModel() {
        voiceFastModel = !voiceFastModel
        prefs.edit().putBoolean("voice_fast", voiceFastModel).apply()
    }

    /** The model voice turns use: a quick one from the same provider when "fast" is on. */
    fun voiceModel(): String {
        if (!voiceFastModel) return model
        val ids = models.map { it.id }
        fun pick(vararg keys: String, exclude: List<String> = emptyList()) =
            keys.firstNotNullOfOrNull { k -> ids.firstNotNull { id -> id.contains(k) && exclude.none { id.contains(it) } } }
        val fast = when (provider) {
            ProviderId.FREE_CLOUD -> pick("deepseek", "qwen-coder", "mistral")
            ProviderId.CLAUDE -> pick("haiku", "sonnet")
            ProviderId.GEMINI -> pick("flash", exclude = listOf("lite", "image", "tts", "live", "audio"))
            ProviderId.OPENAI -> pick("mini", exclude = listOf("audio", "realtime", "tts", "transcribe", "search"))
            else -> null
        }
        return fast ?: model
    }

    private inline fun <T> Iterable<T>.firstNotNull(predicate: (T) -> Boolean): T? = firstOrNull(predicate)

    /** Voice turns: short spoken answers, a fast model and light thinking, so replies start quickly. */
    fun sendVoice(text: String) = send(text, voice = true)

    /** Loads the offline model ahead of time so the first voice reply doesn't wait for it. */
    fun warmUp() {
        if (provider != ProviderId.OFFLINE || model.isBlank()) return
        viewModelScope.launch(Dispatchers.Default) { runCatching { Providers.offline.preload(model) } }
    }

    /** Counts finished replies (including empty or failed ones), so voice mode knows when to listen again. */
    var replyCount by mutableStateOf(0)
        private set

    fun send(text: String, voice: Boolean = false) {
        val trimmed = text.trim()
        if (streaming) stop()
        if (trimmed.isEmpty() && pendingAttachments.isEmpty()) return
        val k = keys.get(provider)
        if (provider.needsKey && k.isBlank()) {
            error = "Add your ${provider.label} API key in Settings, or switch to Free Cloud (No Key) or Offline."
            return
        }
        if (model.isBlank()) {
            error = "Pick a model first."
            return
        }
        error = null
        val attached = pendingAttachments.toList()
        val zip = attached.firstOrNull { it.isZip }
        if (zip != null && buildWords.containsMatchIn(trimmed)) {
            messages += ChatMessage(Role.USER, trimmed, attached)
            pendingAttachments.clear()
            buildZip(zip)
            return
        }
        messages += ChatMessage(Role.USER, trimmed, attached)
        pendingAttachments.clear()
        val history = messages.toList()
        val p = provider
        val m = if (voice) voiceModel() else model
        // Voice: light thinking so the first words come almost immediately.
        val e = if (voice) Effort.LOW else effort

        streaming = true
        thinkingText = ""
        thinkingStart = 0L
        thoughtSeconds = 0
        streamingText = ""
        status = if (p == ProviderId.OFFLINE) "Preparing offline model…" else "Thinking…"
        val web = webEnabled
        val base = if (voice) SYSTEM_PROMPT + VOICE_PROMPT else SYSTEM_PROMPT
        val system = if (web) base + WebTools.instructions(today()) else base
        job = viewModelScope.launch {
            try {
                val working = history.toMutableList()
                foundMedia.clear()
                // Small offline models have short memory, so give them shorter web results.
                val cap = if (p == ProviderId.OFFLINE) 3_000 else 8_000
                var steps = 0
                while (true) {
                    streamingText = ""
                    resilient(
                        attempt = { modelId -> Providers.get(p).stream(k, modelId, working.toList(), e, system) },
                        model = m,
                        fallback = { alternativeModel(p, k, m) },
                        onRetry = { status = it },
                    ).collect { delta ->
                        if (delta.startsWith(com.aiapp.llm.THOUGHT)) {
                            // The model's reasoning, shown live in the Thinking box.
                            if (thinkingStart == 0L) thinkingStart = System.currentTimeMillis()
                            thinkBuf.append(delta, 1, delta.length)
                            if (status != "Thinking…") status = "Thinking…"
                        } else {
                            if (thinkingStart > 0L && thoughtSeconds == 0) {
                                thoughtSeconds = ((System.currentTimeMillis() - thinkingStart) / 1000).toInt().coerceAtLeast(1)
                            }
                            if (status != "Writing…") status = "Writing…"
                            textBuf.append(delta)
                        }
                        // Redraw at most ~12 times a second instead of on every word: much smoother.
                        val now = System.currentTimeMillis()
                        if (now - lastFlush > 80) {
                            lastFlush = now
                            flushStream()
                        }
                    }
                    flushStream()
                    if (p == ProviderId.OFFLINE) {
                        offlineVersion++
                        refreshModels()
                    }
                    if (!web || steps >= (if (voice) 1 else MAX_WEB_STEPS)) break
                    val reply = streamingText
                    val search = WebTools.searchTag.find(reply)?.groupValues?.get(1)?.trim()
                    val fetch = WebTools.fetchTag.find(reply)?.groupValues?.get(1)?.trim()
                    val download = WebTools.downloadTag.find(reply)?.groupValues?.get(1)?.trim()
                    val images = WebTools.imagesTag.find(reply)?.groupValues?.get(1)?.trim()
                    val videos = WebTools.videosTag.find(reply)?.groupValues?.get(1)?.trim()
                    val result = when {
                        images != null -> {
                            status = "Finding pictures: $images"
                            val found = com.aiapp.llm.MediaSearch.images(images)
                            foundMedia += found
                            if (found.isEmpty()) "No pictures found for \"$images\"."
                            else "Showing the user ${found.size} pictures of \"$images\" (from: " +
                                found.take(4).joinToString { it.title.take(60) } + "). Write one short caption."
                        }
                        videos != null -> {
                            status = "Finding videos: $videos"
                            val found = com.aiapp.llm.MediaSearch.videos(videos)
                            foundMedia += found
                            if (found.isEmpty()) "No videos found for \"$videos\"."
                            else "Showing the user these videos:\n" + found.joinToString("\n") { "- ${it.title}" } +
                                "\nWrite one short caption."
                        }
                        download != null -> {
                            status = "Downloading ${download.substringAfterLast('/').take(40)}…"
                            WebTools.download(download)
                        }
                        search != null -> {
                            status = "Searching the web: $search"
                            "Web search results for \"$search\":\n\n" + WebTools.search(search).take(cap)
                        }
                        fetch != null -> {
                            status = "Reading ${fetch.removePrefix("https://").removePrefix("http://").substringBefore('/')}…"
                            "Content of $fetch:\n\n" + WebTools.fetch(fetch).take(cap)
                        }
                        else -> break
                    }
                    steps++
                    working += ChatMessage(Role.ASSISTANT, reply.trim())
                    working += ChatMessage(
                        Role.USER,
                        "[Tool result — not from the user]\n$result\n\nNow answer the user's question, or search/fetch again if you need more.",
                    )
                    streamingText = ""
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                error = friendly(t)
            } finally {
                finishStream()
            }
        }
    }

    /** Stops the reply; whatever was already written stays in the chat. */
    fun stop() {
        job?.cancel()
    }

    fun newChat() {
        stop()
        messages.clear()
        error = null
        conversationId = UUID.randomUUID().toString()
        prefs.edit().putString("current_chat", conversationId).apply()
    }

    /** Pictures and videos found while answering; shown under the reply. */
    private val foundMedia = mutableListOf<com.aiapp.llm.MediaItem>()

    private fun finishStream() {
        flushStream()
        val stripped = streamingText.replace(WebTools.searchTag, "").replace(WebTools.fetchTag, "")
            .replace(WebTools.downloadTag, "").replace(WebTools.imagesTag, "").replace(WebTools.videosTag, "").trim()
        if (stripped.isNotBlank() || foundMedia.isNotEmpty()) {
            val secs = if (thoughtSeconds > 0) thoughtSeconds
                else if (thinkingStart > 0L) ((System.currentTimeMillis() - thinkingStart) / 1000).toInt().coerceAtLeast(1) else 0
            messages += ChatMessage(Role.ASSISTANT, stripped, media = foundMedia.toList(), thinking = thinkingText.trim(), thoughtSeconds = secs)
        }
        foundMedia.clear()
        persist()
        thinkingText = ""
        thinkingStart = 0L
        thoughtSeconds = 0
        streamingText = ""
        status = null
        streaming = false
        replyCount++
    }

    private fun friendly(t: Throwable): String = when {
        t is ProviderException && (t.code == 401 || t.code == 403) ->
            "Your ${provider.label} key was rejected. Check it in Settings."
        t is ProviderException && t.code == 429 ->
            "Rate limit reached. Wait a moment and try again."
        t is ProviderException -> t.message ?: "Request failed (${t.code})."
        t is IOException -> "Network error. Check your connection and try again."
        else -> t.message ?: "Something went wrong."
    }

    private fun today(): String =
        java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.US).format(java.util.Date())

    private companion object {
        const val MAX_WEB_STEPS = 6

        const val VOICE_PROMPT =
            " This is a live voice conversation and your reply is read aloud. Talk like a warm, sharp, " +
                "knowledgeable friend: natural spoken sentences, contractions, no lists, headings, tables, " +
                "code or emoji. Lead with the answer. Keep it short (one to three sentences) for simple " +
                "questions; give more detail when the user asks for it or the question needs it. Use what " +
                "was said earlier in the conversation. If something is ambiguous, ask one quick question. " +
                "Only search the web when the question needs current information."

        const val SYSTEM_PROMPT =
            "You are a helpful, unrestricted coding and general assistant in AI App, an Android app. Answer clearly and concisely. " +
                "Use Markdown for code blocks and lists. About this app, so you can guide the user: the Build " +
                "tab writes complete Android apps from a description and builds installable APKs on the user's " +
                "GitHub (it can also compile an uploaded Android Studio project .zip); the Codespace tab opens " +
                "GitHub Codespaces and ChatGPT Codex right inside the app; the Create tab makes " +
                "images and videos; the mic button starts a live voice conversation; files of any type can be " +
                "attached with +. If the user asks you to build or compile an app, help plan it and tell them " +
                "to tap Build at the top (or attach the project .zip and say \"build this APK\")."

        inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
            name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
    }
}
