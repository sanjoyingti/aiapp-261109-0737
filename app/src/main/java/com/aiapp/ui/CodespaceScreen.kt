package com.aiapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aiapp.builder.GitHubClient
import com.aiapp.builder.GitHubLogin
import kotlinx.coroutines.launch

/**
 * Retains the active Codespace / ChatGPT Codex WebView instance and its session state across
 * configuration changes, screen switches, and app backgrounding so the WebView never reloads or
 * drops its WebSocket/fetch connection when backgrounded.
 */
object CodespaceSessionManager {
    const val URL_GITHUB_CODESPACES = "https://github.com/codespaces"
    const val URL_CHATGPT_CODEX = "https://chatgpt.com/codex"
    const val URL_GITHUB_DEVICE = "https://github.com/login/device"

    /** Clean Chrome Android User-Agent without `; wv` so Google, GitHub, and OpenAI OAuth never block sign-in. */
    const val AUTH_SAFE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    val savedState = Bundle()
    var retainedWebView: WebView? = null
    var lastLoadedUrl: String? = null
    var hasMainFrameError: Boolean = false

    fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }

    /**
     * Injects a lightweight keep-alive and reconnection helper into the page so brief backgrounding
     * doesn't trigger visibility-based socket teardown, and returning to the foreground automatically
     * resumes any paused WebSocket/fetch requests without reloading the page.
     */
    fun injectKeepAliveAndReconnect(webView: WebView, userCodeToAutoFill: String? = null) {
        val codeEscaped = userCodeToAutoFill?.replace("'", "")?.trim().orEmpty()
        val js = """
            (function() {
                try {
                    // 1. Dispatch online & focus events so VS Code Web / Codespaces / Codex reconnects sockets smoothly
                    window.dispatchEvent(new Event('online'));
                    window.dispatchEvent(new Event('focus'));

                    // 2. Prevent brief backgrounding from triggering aggressive visibilitychange disconnects
                    if (!window.__aiAppKeepAliveInstalled) {
                        window.__aiAppKeepAliveInstalled = true;
                        document.addEventListener('visibilitychange', function(e) {
                            if (document.visibilityState === 'hidden') {
                                e.stopImmediatePropagation();
                            }
                        }, true);
                    }

                    // 3. Auto-fill GitHub Device Flow user_code if on github.com/login/device
                    var code = '$codeEscaped';
                    if (code && window.location.hostname === 'github.com' && window.location.pathname.indexOf('/login/device') === 0) {
                        var singleInput = document.querySelector('input#user-code, input[name="user_code"]');
                        if (singleInput && !singleInput.value) {
                            singleInput.value = code;
                            singleInput.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                        var chars = code.replace(/[^A-Za-z0-9]/g, '');
                        for (var i = 0; i < chars.length; i++) {
                            var box = document.querySelector('input#user-code-' + i);
                            if (box && !box.value) {
                                box.value = chars[i];
                                box.dispatchEvent(new Event('input', { bubbles: true }));
                            }
                        }
                    }

                    // 4. If a transient "Reload" / "Reconnect" dialog is shown inside VS Code Web, click it non-destructively
                    var buttons = document.querySelectorAll('a.monaco-button, button');
                    for (var b = 0; b < buttons.length; b++) {
                        var txt = (buttons[b].innerText || '').trim().toLowerCase();
                        if (txt === 'reconnect' || txt === 'try again' || txt === 'retry') {
                            buttons[b].click();
                            break;
                        }
                    }
                } catch (err) {}
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }
}

/**
 * Interactive Codespace & ChatGPT Codex screen with:
 * 1. Full OAuth / Device Flow / Cookie / Popup support for GitHub Codespaces and ChatGPT Codex.
 * 2. Persistent WebView lifecycle management (`saveState` / `restoreState`, background socket preservation,
 *    and automatic network reconnection) so backgrounding never causes reloads or "Failed to fetch" errors.
 * 3. A visible, always-accessible Back button in the top toolbar plus proper system back navigation.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodespaceScreen(
    vm: ChatViewModel,
    initialUrl: String = CodespaceSessionManager.URL_GITHUB_CODESPACES,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var currentUrl by rememberSaveable { mutableStateOf(CodespaceSessionManager.lastLoadedUrl ?: initialUrl) }
    var pageTitle by rememberSaveable { mutableStateOf("Codespace & Codex") }
    var loadingProgress by remember { mutableStateOf<Float?>(null) }
    var canGoBackInWeb by remember { mutableStateOf(false) }
    var networkError by remember { mutableStateOf<String?>(null) }
    var showCodespacePicker by rememberSaveable { mutableStateOf(false) }
    var codespaces by remember { mutableStateOf<List<GitHubClient.Codespace>>(emptyList()) }
    var repos by remember { mutableStateOf<List<GitHubClient.Repo>>(emptyList()) }
    var loadingList by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }

    // OAuth popup WebView support (for Google / Microsoft / GitHub / OpenAI login popups)
    var popupMessage by remember { mutableStateOf<Message?>(null) }
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileChooserCallback?.onReceiveValue(uris.toTypedArray())
        fileChooserCallback = null
    }

    val webView = remember {
        CodespaceSessionManager.retainedWebView?.also { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
        } ?: WebView(context.applicationContext).also { created ->
            CodespaceSessionManager.retainedWebView = created
        }
    }

    fun navigateTo(url: String) {
        networkError = null
        CodespaceSessionManager.hasMainFrameError = false
        currentUrl = url
        CodespaceSessionManager.lastLoadedUrl = url
        webView.loadUrl(url)
    }

    fun refreshCodespaceList() {
        val token = vm.githubToken()
        if (token.isBlank()) {
            listError = "Sign in with GitHub first to list or create Codespaces via API."
            return
        }
        loadingList = true
        listError = null
        scope.launch {
            try {
                val gh = GitHubClient(token)
                codespaces = gh.listCodespaces()
                repos = gh.listRepos()
            } catch (e: Throwable) {
                listError = e.message ?: "Couldn't load Codespaces."
            } finally {
                loadingList = false
            }
        }
    }

    // Handle external URL changes requested by the caller (e.g. "Approve on GitHub" button in Settings)
    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank() && initialUrl != CodespaceSessionManager.lastLoadedUrl && CodespaceSessionManager.lastLoadedUrl == null) {
            navigateTo(initialUrl)
        }
    }

    // Preserve WebView state across pause/resume and auto-reconnect on network changes without reloading
    DisposableEffect(lifecycleOwner, webView) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                webView.post {
                    CodespaceSessionManager.flushCookies()
                    if (CodespaceSessionManager.hasMainFrameError || networkError != null) {
                        networkError = null
                        CodespaceSessionManager.hasMainFrameError = false
                        webView.reload()
                    } else {
                        CodespaceSessionManager.injectKeepAliveAndReconnect(webView, vm.githubCode?.userCode)
                    }
                }
            }
        }
        runCatching {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm?.registerNetworkCallback(req, netCallback)
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    // Save WebView state and flush session cookies, but DO NOT pauseTimers() or stopLoading(),
                    // so active Codespace WebSockets and fetch streams survive backgrounding.
                    runCatching { webView.saveState(CodespaceSessionManager.savedState) }
                    CodespaceSessionManager.flushCookies()
                }
                Lifecycle.Event.ON_RESUME -> {
                    runCatching {
                        webView.onResume()
                        webView.resumeTimers()
                    }
                    CodespaceSessionManager.flushCookies()
                    if (CodespaceSessionManager.hasMainFrameError) {
                        CodespaceSessionManager.hasMainFrameError = false
                        networkError = null
                        webView.reload()
                    } else {
                        CodespaceSessionManager.injectKeepAliveAndReconnect(webView, vm.githubCode?.userCode)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { cm?.unregisterNetworkCallback(netCallback) }
            runCatching { webView.saveState(CodespaceSessionManager.savedState) }
            CodespaceSessionManager.flushCookies()
            (webView.parent as? ViewGroup)?.removeView(webView)
        }
    }

    // System back button handling: close OAuth popup first, or step back in WebView history, or exit to previous screen.
    // Note: The visible TopAppBar Back button ALWAYS exits immediately via onBack() so the user is never stuck.
    BackHandler {
        when {
            popupMessage != null -> popupMessage = null
            showCodespacePicker -> showCodespacePicker = false
            webView.canGoBack() -> webView.goBack()
            else -> onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            pageTitle.ifBlank { "Codespace & Codex" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            currentUrl.removePrefix("https://").removePrefix("http://"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    // Visible, dedicated Back button that always returns to the previous app screen
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to app")
                    }
                },
                actions = {
                    if (canGoBackInWeb) {
                        TextButton(onClick = { if (webView.canGoBack()) webView.goBack() }) {
                            Text("◄ Page")
                        }
                    }
                    IconButton(onClick = {
                        networkError = null
                        CodespaceSessionManager.hasMainFrameError = false
                        webView.reload()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                    TextButton(onClick = onBack) {
                        Text("Exit", fontWeight = FontWeight.Bold)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Mode & quick-action bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = currentUrl.contains("github.com/codespaces") || currentUrl.contains(".github.dev"),
                    onClick = { navigateTo(CodespaceSessionManager.URL_GITHUB_CODESPACES) },
                    label = { Text("GitHub Codespaces") },
                )
                FilterChip(
                    selected = currentUrl.contains("chatgpt.com") || currentUrl.contains("openai.com"),
                    onClick = { navigateTo(CodespaceSessionManager.URL_CHATGPT_CODEX) },
                    label = { Text("ChatGPT Codex") },
                )
                FilterChip(
                    selected = showCodespacePicker,
                    onClick = {
                        showCodespacePicker = !showCodespacePicker
                        if (showCodespacePicker) refreshCodespaceList()
                    },
                    label = { Text("My Codespaces API") },
                )
                FilterChip(
                    selected = currentUrl.contains("github.com/login/device"),
                    onClick = {
                        if (vm.githubCode == null && vm.githubToken().isBlank()) {
                            vm.signInGithub { link -> navigateTo(link) }
                        } else {
                            navigateTo(CodespaceSessionManager.URL_GITHUB_DEVICE)
                        }
                    },
                    label = {
                        Text(
                            if (vm.githubToken().isNotBlank()) "✓ GitHub (${vm.githubUser.ifBlank { "Signed in" }})"
                            else "GitHub Sign-In"
                        )
                    },
                )
            }

            // Active GitHub Device Code helper banner (auto-fills and lets user copy with one tap)
            vm.githubCode?.let { code ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "GitHub Code: ${code.userCode}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Approve below in this WebView — the app captures your token automatically.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = {
                            clipboard.setText(AnnotatedString(code.userCode))
                            CodespaceSessionManager.injectKeepAliveAndReconnect(webView, code.userCode)
                        }) {
                            Text("Paste Code")
                        }
                    }
                }
            }

            // Optional native Codespace launcher panel (lists & starts Codespaces directly via GitHub REST API)
            if (showCodespacePicker) {
                CodespaceApiPanel(
                    vm = vm,
                    codespaces = codespaces,
                    repos = repos,
                    loading = loadingList,
                    error = listError,
                    onRefresh = ::refreshCodespaceList,
                    onOpenUrl = { url ->
                        showCodespacePicker = false
                        navigateTo(url)
                    },
                    onCreateForRepo = { repo ->
                        scope.launch {
                            loadingList = true
                            listError = null
                            try {
                                val cs = GitHubClient(vm.githubToken()).createCodespace(repo.owner, repo.name, repo.branch)
                                showCodespacePicker = false
                                navigateTo(cs.webUrl)
                            } catch (e: Throwable) {
                                listError = e.message ?: "Couldn't create Codespace."
                            } finally {
                                loadingList = false
                            }
                        }
                    },
                )
            }

            loadingProgress?.let { p ->
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Non-destructive reconnection banner if a network drop occurred
            networkError?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            networkError = null
                            CodespaceSessionManager.hasMainFrameError = false
                            CodespaceSessionManager.injectKeepAliveAndReconnect(webView, vm.githubCode?.userCode)
                            webView.reload()
                        }) {
                            Text("Reconnect")
                        }
                    }
                }
            }

            // Main persistent WebView
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = {
                        webView.apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                javaScriptCanOpenWindowsAutomatically = true
                                setSupportMultipleWindows(true)
                                loadsImagesAutomatically = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                cacheMode = WebSettings.LOAD_DEFAULT
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                mediaPlaybackRequiresUserGesture = false
                                userAgentString = CodespaceSessionManager.AUTH_SAFE_UA
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val uri = request.url ?: return false
                                    val scheme = uri.scheme?.lowercase().orEmpty()
                                    val full = uri.toString()

                                    // Capture OAuth callback redirects (aiapp://oauth/github, aiapp://callback, etc.)
                                    if (scheme == "aiapp") {
                                        GitHubLogin.extractTokenFromUri(uri)?.let { token ->
                                            vm.saveGithubToken(token)
                                        }
                                        uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }?.let {
                                            // If device or OAuth flow finished, refresh user state
                                            CodespaceSessionManager.flushCookies()
                                        }
                                        return true
                                    }

                                    // Keep http/https inside the persistent WebView (including github.com, *.github.dev, auth.openai.com, chatgpt.com)
                                    if (scheme == "http" || scheme == "https") {
                                        return false
                                    }

                                    // Ignore or safely handle vscode:// / intent:// deep links without crashing WebView
                                    if (scheme == "vscode" || scheme == "vscode-insiders" || scheme == "github-codespaces") {
                                        return true
                                    }
                                    if (scheme == "intent") {
                                        runCatching {
                                            val parsed = Intent.parseUri(full, Intent.URI_INTENT_SCHEME)
                                            val fallback = parsed.getStringExtra("browser_fallback_url")
                                            if (!fallback.isNullOrBlank()) {
                                                view.loadUrl(fallback)
                                            }
                                        }
                                        return true
                                    }
                                    return true
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                        CodespaceSessionManager.lastLoadedUrl = url
                                    }
                                    canGoBackInWeb = view.canGoBack()
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                        CodespaceSessionManager.lastLoadedUrl = url
                                    }
                                    canGoBackInWeb = view.canGoBack()
                                    loadingProgress = null
                                    CodespaceSessionManager.flushCookies()
                                    CodespaceSessionManager.injectKeepAliveAndReconnect(view, vm.githubCode?.userCode)
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    // Only surface errors for the main frame; subresource/socket hiccups when backgrounded
                                    // are handled non-destructively so the Codespace never wipes out the user's open editor.
                                    if (request.isForMainFrame) {
                                        CodespaceSessionManager.hasMainFrameError = true
                                        networkError = "Connection interrupted (${error.description}). Tap Reconnect or wait for network."
                                    } else {
                                        CodespaceSessionManager.injectKeepAliveAndReconnect(view, vm.githubCode?.userCode)
                                    }
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    loadingProgress = if (newProgress in 1..99) newProgress / 100f else null
                                }

                                override fun onReceivedTitle(view: WebView, title: String?) {
                                    if (!title.isNullOrBlank()) pageTitle = title
                                }

                                override fun onShowFileChooser(
                                    webView: WebView,
                                    filePathCallback: ValueCallback<Array<Uri>>,
                                    fileChooserParams: FileChooserParams,
                                ): Boolean {
                                    fileChooserCallback?.onReceiveValue(null)
                                    fileChooserCallback = filePathCallback
                                    runCatching { filePicker.launch(arrayOf("*/*")) }.onFailure {
                                        fileChooserCallback?.onReceiveValue(null)
                                        fileChooserCallback = null
                                    }
                                    return true
                                }

                                // Handle OAuth login popups (e.g., Sign in with Google / GitHub / Microsoft on ChatGPT Codex or Codespaces)
                                override fun onCreateWindow(
                                    view: WebView,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message?,
                                ): Boolean {
                                    if (resultMsg != null) {
                                        popupMessage = resultMsg
                                        return true
                                    }
                                    return false
                                }

                                override fun onCloseWindow(window: WebView?) {
                                    popupMessage = null
                                }
                            }

                            // Only load initialUrl if the WebView doesn't already have an active page loaded
                            if (url.isNullOrBlank()) {
                                if (!CodespaceSessionManager.savedState.isEmpty) {
                                    restoreState(CodespaceSessionManager.savedState)
                                }
                                if (url.isNullOrBlank()) {
                                    loadUrl(currentUrl)
                                }
                            }
                        }
                    },
                    update = { view ->
                        canGoBackInWeb = view.canGoBack()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // OAuth Popup Dialog so multi-window OAuth flows complete smoothly inside the app
    popupMessage?.let { msg ->
        OAuthPopupDialog(
            resultMessage = msg,
            onDismiss = {
                popupMessage = null
                CodespaceSessionManager.flushCookies()
                CodespaceSessionManager.injectKeepAliveAndReconnect(webView, vm.githubCode?.userCode)
            },
            onTokenCaptured = { token ->
                vm.saveGithubToken(token)
                popupMessage = null
            },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OAuthPopupDialog(
    resultMessage: Message,
    onDismiss: () -> Unit,
    onTokenCaptured: (String) -> Unit,
) {
    var popupTitle by remember { mutableStateOf("Sign in") }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        popupTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close sign-in window")
                    }
                }
                HorizontalDivider()
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                javaScriptCanOpenWindowsAutomatically = true
                                setSupportMultipleWindows(true)
                                userAgentString = CodespaceSessionManager.AUTH_SAFE_UA
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val u = request.url ?: return false
                                    if (u.scheme?.lowercase() == "aiapp") {
                                        GitHubLogin.extractTokenFromUri(u)?.let(onTokenCaptured)
                                        onDismiss()
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    CodespaceSessionManager.flushCookies()
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onReceivedTitle(view: WebView, title: String?) {
                                    if (!title.isNullOrBlank()) popupTitle = title
                                }

                                override fun onCloseWindow(window: WebView?) {
                                    CodespaceSessionManager.flushCookies()
                                    onDismiss()
                                }
                            }
                            val transport = resultMessage.obj as? WebView.WebViewTransport
                            transport?.webView = this
                            resultMessage.sendToTarget()
                        }
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun CodespaceApiPanel(
    vm: ChatViewModel,
    codespaces: List<GitHubClient.Codespace>,
    repos: List<GitHubClient.Repo>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCreateForRepo: (GitHubClient.Repo) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Card(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Your GitHub Codespaces",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (loading) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") }
            }
            if (vm.githubToken().isBlank()) {
                Text(
                    "Sign in with GitHub to view or launch your Codespaces directly.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = {
                    vm.signInGithub { link -> onOpenUrl(link) }
                }) {
                    Text("Sign in with GitHub")
                }
                return@Column
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (codespaces.isNotEmpty()) {
                    items(codespaces, key = { it.name }) { cs ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        runCatching {
                                            if (cs.state != "Available") GitHubClient(vm.githubToken()).startCodespace(cs.name)
                                        }
                                        onOpenUrl(cs.webUrl)
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(cs.displayName, fontWeight = FontWeight.Medium)
                                Text(
                                    "${cs.repoFullName} (${cs.branch}) · ${cs.state}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(onClick = { onOpenUrl(cs.webUrl) }) {
                                Text("Open")
                            }
                        }
                        HorizontalDivider()
                    }
                }
                if (repos.isNotEmpty()) {
                    item {
                        Text(
                            "Create new Codespace from repo:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(repos.take(10), key = { "${it.owner}/${it.name}" }) { r ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${r.owner}/${r.name}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onCreateForRepo(r) }, enabled = !loading) {
                                Text("+ Launch Codespace")
                            }
                        }
                    }
                }
            }
        }
    }
}
