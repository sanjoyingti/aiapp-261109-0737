package com.aiapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiapp.ui.AppTheme
import com.aiapp.ui.BuildScreen
import com.aiapp.ui.BuildViewModel
import com.aiapp.ui.ChatScreen
import com.aiapp.ui.ChatViewModel
import com.aiapp.ui.CodespaceScreen
import com.aiapp.ui.CodespaceSessionManager
import com.aiapp.ui.CreateScreen
import com.aiapp.ui.CreateViewModel
import com.aiapp.ui.HistoryScreen
import com.aiapp.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val chatVm: ChatViewModel by viewModels()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Only drop image cache on UI hidden; keep the offline model loaded unless memory is critically low
        // and neither Chat nor Build Mode is actively running.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            com.aiapp.ui.ImageLoader.clear()
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            if (!com.aiapp.builder.BuildRunner.running && !chatVm.streaming) {
                com.aiapp.llm.Providers.offlineIfLoaded?.release()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        chatVm.handleOAuthIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatVm.handleOAuthIntent(intent)
        setContent {
            AppTheme {
                val vm: ChatViewModel = viewModel()
                var screen by rememberSaveable { mutableStateOf("chat") }
                var previousScreen by rememberSaveable { mutableStateOf("chat") }
                var codespaceInitialUrl by rememberSaveable { mutableStateOf(CodespaceSessionManager.URL_GITHUB_CODESPACES) }

                fun navigateToCodespace(url: String = CodespaceSessionManager.URL_GITHUB_CODESPACES) {
                    if (screen != "codespace") previousScreen = screen
                    codespaceInitialUrl = url
                    CodespaceSessionManager.lastLoadedUrl = null
                    screen = "codespace"
                }

                BackHandler(enabled = screen != "chat") {
                    screen = if (screen == "codespace" && previousScreen != "codespace") previousScreen else "chat"
                }
                val buildVm: BuildViewModel = viewModel()
                val createVm: CreateViewModel = viewModel()
                when (screen) {
                    "chat" -> ChatScreen(
                        vm,
                        onOpenSettings = { screen = "settings" },
                        onOpenBuild = { screen = "build" },
                        onOpenCodespace = { navigateToCodespace(CodespaceSessionManager.URL_GITHUB_CODESPACES) },
                        onOpenHistory = { screen = "history" },
                        onOpenCreate = { screen = "create" },
                    )
                    "history" -> HistoryScreen(vm, onBack = { screen = "chat" })
                    "create" -> CreateScreen(createVm, onBack = { screen = "chat" }, onOpenSettings = { screen = "settings" })
                    "build" -> BuildScreen(
                        vm,
                        buildVm,
                        onBack = { screen = "chat" },
                        onOpenSettings = { screen = "settings" },
                        onOpenCodespace = { url -> navigateToCodespace(url) },
                    )
                    "codespace" -> CodespaceScreen(
                        vm = vm,
                        initialUrl = codespaceInitialUrl,
                        onBack = {
                            screen = if (previousScreen != "codespace") previousScreen else "chat"
                        },
                    )
                    else -> SettingsScreen(
                        vm,
                        onBack = { screen = "chat" },
                        onOpenCodespace = { url -> navigateToCodespace(url) },
                    )
                }
            }
        }
    }
}
