package com.m57.hermescontrol.ui.chat

import android.Manifest
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.navigation.ShellTab
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.chat.components.ConversationMenuSheet
import com.m57.hermescontrol.ui.chat.fullbleed.MAIN_CHAT_WAITING_KEY
import com.m57.hermescontrol.ui.common.ActionProgressController
import com.m57.hermescontrol.ui.common.ShellBottomBar
import com.m57.hermescontrol.ui.sessions.SessionsUiState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/** Actual production composables, synthetic data only. No request, account, device or server interaction. */
class ChatUiEvidenceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun JapaneseTheme(
        preference: ThemePreference = ThemePreference.DARK,
        content: @Composable () -> Unit,
    ) {
        val context = LocalContext.current
        val current = LocalConfiguration.current
        val configuration = remember(current) { Configuration(current).apply { setLocale(Locale.JAPAN) } }
        val localized = remember(context, configuration) { context.createConfigurationContext(configuration) }
        CompositionLocalProvider(
            LocalConfiguration provides configuration,
            LocalContext provides localized,
            LocalActivityResultRegistryOwner provides compose.activity,
        ) {
            HermesControlTheme(themePreference = preference, content = content)
        }
    }

    @Test
    fun mainHeaderAndStatusSheetRemainReachableWithLargeText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        val model = mockk<ChatViewModel>(relaxed = true)
        every { model.specialistChat } returns
            mockk {
                every { state } returns MutableStateFlow(SpecialistChatState())
            }
        every { model.uiState } returns
            MutableStateFlow(
                ChatUiState(
                    connectionStatus = ConnectionStatus.CONNECTED,
                    currentSessionModel = "test-model",
                    reasoningLevel = "high",
                    usedContextTokens = 500,
                    fullContextTokens = 8000,
                ),
            )
        every { model.streamingState } returns MutableStateFlow(StreamingState())
        every { model.searchState } returns ChatSearchState()
        every { model.actionProgress } returns ActionProgressController(CoroutineScope(Dispatchers.Main))
        val preference = mutableStateOf(ThemePreference.DARK)
        val fontScale = mutableStateOf(1f)
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent {
            JapaneseTheme(preference.value) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                    Scaffold(
                        contentWindowInsets = WindowInsets.navigationBars,
                        bottomBar = { if (!imeVisible) ShellBottomBar(ShellTab.AGENT, museMinimal = true) },
                    ) { padding ->
                        ChatScreen(
                            viewModel = model,
                            onOpenDrawer = {},
                            modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize(),
                        )
                    }
                }
            }
        }
        capture("05-main-chat-dark")
        compose.onNodeWithTag("menu_button", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_header_avatar", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("新規").assertDoesNotExist()
        compose.onNodeWithTag("model_chip").assertDoesNotExist()
        compose.onNodeWithTag("reasoning_chip").assertDoesNotExist()
        compose.onNodeWithTag("chat_settings_button").assertDoesNotExist()
        compose.onNodeWithTag("shell_tab_agent").assertIsDisplayed()
        compose.onNodeWithTag("chat_header_pill").performClick()
        compose.onNodeWithTag("header_sheet_model_row").assertIsDisplayed()
        compose.onNodeWithTag("header_sheet_reasoning_card").assertIsDisplayed()
        compose.onNodeWithTag("header_sheet_context_row").assertIsDisplayed()
        capture("06-main-chat-status-sheet")
        Espresso.pressBack()
        compose.runOnIdle {
            preference.value = ThemePreference.LIGHT
            fontScale.value = 1.6f
        }
        compose.onNodeWithTag("chat_header_pill").assertIsDisplayed()
        capture("07-main-chat-light-large-text")
        compose.onNodeWithTag("chat_input").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("shell_tab_agent").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("chat_header_pill").assertIsDisplayed()
        compose.onNodeWithTag("model_chip").assertIsDisplayed()
        compose.onNodeWithTag("reasoning_chip").assertIsDisplayed()
        capture("08-main-chat-light-keyboard")
        Espresso.pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("shell_tab_agent").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("model_chip").assertDoesNotExist()
        compose.onNodeWithTag("reasoning_chip").assertDoesNotExist()
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder =
            File(
                instrumentation.targetContext.getExternalFilesDir(null),
                "chat-ui-v11-evidence",
            ).apply { mkdirs() }
        File(folder, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test
    fun streamingReplacesWaitingDotsWithTheAnswerBubble() {
        val transcript =
            List(500) { index ->
                ChatMessage(
                    id = "history-$index",
                    role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                    content = "Previous message $index",
                )
            } + ChatMessage(id = "current", role = MessageRole.USER, content = "Current prompt")
        val model = mockk<ChatViewModel>(relaxed = true)
        val streaming = MutableStateFlow(StreamingState())
        every { model.specialistChat } returns
            mockk {
                every { state } returns MutableStateFlow(SpecialistChatState())
            }
        every { model.uiState } returns
            MutableStateFlow(
                ChatUiState(
                    messages = transcript,
                    currentSessionId = "fixture-stream-session",
                    connectionStatus = ConnectionStatus.CONNECTED,
                    isAgentTyping = true,
                ),
            )
        every { model.streamingState } returns streaming
        every { model.searchState } returns ChatSearchState()
        every { model.actionProgress } returns ActionProgressController(CoroutineScope(Dispatchers.Main))
        compose.setContent { JapaneseTheme { ChatScreen(viewModel = model, onOpenDrawer = {}) } }
        compose.onNodeWithTag(MAIN_CHAT_WAITING_KEY).assertIsDisplayed()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName

        fun graphicsStats(suffix: String = ""): String =
            ParcelFileDescriptor
                .AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand("dumpsys gfxinfo $packageName$suffix"),
                ).bufferedReader()
                .use { it.readText() }

        graphicsStats(" reset")
        val cpuBefore = compose.runOnIdle { Debug.threadCpuTimeNanos() }
        val runtime = Runtime.getRuntime()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()
        val wallBefore = SystemClock.elapsedRealtime()
        val reply = ChatMessage(id = "stream", role = MessageRole.ASSISTANT, content = "Streaming", isStreaming = true)
        var text = reply.content
        compose.mainClock.autoAdvance = false
        try {
            repeat(120) {
                text += " delta"
                val update = StreamingState(streamingMessage = reply.copy(content = text), isWritingText = true)
                compose.runOnUiThread { streaming.value = update }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithTag(MAIN_CHAT_WAITING_KEY).assertDoesNotExist()
        val elapsed = SystemClock.elapsedRealtime() - wallBefore
        val cpuNanos = compose.runOnIdle { Debug.threadCpuTimeNanos() } - cpuBefore
        val heapAfter = runtime.totalMemory() - runtime.freeMemory()
        File(instrumentation.targetContext.getExternalFilesDir(null), "chat-streaming-metrics.txt").writeText(
            "historyRows=501 updates=120 elapsedMs=$elapsed mainThreadCpuNs=$cpuNanos\n" +
                "javaHeapUsedBefore=$heapBefore javaHeapUsedAfter=$heapAfter\n" + graphicsStats(),
        )
    }

    @Test
    fun mainChatShowsMessagesAndPreservesTheAttachmentPicker() {
        val messages =
            listOf(
                ChatMessage(id = "fixture-user", role = MessageRole.USER, content = "表示確認用の会話です。"),
                ChatMessage(
                    id = "fixture-answer",
                    role = MessageRole.ASSISTANT,
                    content = "確認結果です。詳細は必要なときに開けます。",
                    reasoningText = "非表示にする推論テキスト",
                ),
                ChatMessage(
                    id = "fixture-tool-1",
                    role = MessageRole.TOOL,
                    content = "{\"stdout\":\"Synthetic success\",\"exit_code\":0}",
                    toolName = "terminal",
                    toolStatus = ToolStatus.COMPLETED,
                ),
                ChatMessage(
                    id = "fixture-tool-2",
                    role = MessageRole.TOOL,
                    content = "{\"stdout\":\"表示確認用の結果\",\"exit_code\":0}",
                    toolName = "terminal",
                    toolStatus = ToolStatus.COMPLETED,
                ),
                ChatMessage(
                    id = "fixture-error",
                    role = MessageRole.TOOL,
                    content = "{\"error\":\"Synthetic failure; no tool executed\",\"exit_code\":1}",
                    toolName = "terminal",
                    toolStatus = ToolStatus.FAILED,
                ),
                ChatMessage(id = "fixture-connect-1", role = MessageRole.SYSTEM, content = "Connected to Hermes"),
                ChatMessage(id = "fixture-connect-2", role = MessageRole.SYSTEM, content = "Session resumed"),
                ChatMessage(id = "fixture-connect-3", role = MessageRole.SYSTEM, content = "Connected to Hermes"),
                ChatMessage(id = "fixture-connect-4", role = MessageRole.SYSTEM, content = "Session resumed"),
            )
        val fixtureState =
            ChatUiState(
                messages = messages,
                connectionStatus = ConnectionStatus.CONNECTED,
                chatTitle = "表示確認（合成データ）",
                currentSessionModel = "test-model",
                reasoningLevel = "high",
                pendingPrefillText = "未送信の表示確認用下書き",
                typingEffectEnabled = false,
            )
        val model = mockk<ChatViewModel>(relaxed = true)
        every { model.specialistChat } returns
            mockk {
                every { state } returns MutableStateFlow(SpecialistChatState())
            }
        every { model.uiState } returns MutableStateFlow(fixtureState)
        every { model.streamingState } returns MutableStateFlow(StreamingState())
        every { model.searchState } returns ChatSearchState()
        every { model.actionProgress } returns ActionProgressController(CoroutineScope(Dispatchers.Main))
        compose.setContent {
            JapaneseTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { ChatScreen(viewModel = model, onOpenDrawer = {}) }
                    ShellBottomBar(ShellTab.AGENT)
                }
            }
        }
        compose.onNodeWithTag("chat_user_text_surface").assertIsDisplayed()
        compose.onNodeWithTag("chat_assistant_bubble").assertIsDisplayed()
        compose.onNodeWithTag("fullbleed_agent_header").assertDoesNotExist()
        compose.onNodeWithTag("fullbleed_finish_time").assertDoesNotExist()
        compose.onNodeWithText("非表示にする推論テキスト").assertDoesNotExist()
        compose.onNodeWithTag("chat_tool_toggle").assertDoesNotExist()
        compose.onNodeWithTag("chat_connection_events").assertDoesNotExist()
        capture("01-main-chat-bubbles")
        compose.onNodeWithTag("chat_attach_button").performClick()
        compose.onNodeWithTag("chat_attachment_menu").assertIsDisplayed()
        capture("02-google-symbols-attachment-picker")
        Espresso.pressBack()
        compose.onNodeWithTag("chat_attachment_menu").assertDoesNotExist()
    }

    @Test
    fun captureReadableHistoryWithoutTransportDetails() {
        compose.setContent {
            JapaneseTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        ConversationMenuSheet(
                            "test-model",
                            {},
                            {},
                            {},
                            {},
                            historyState =
                                SessionsUiState(
                                    sessions =
                                        listOf(
                                            SessionInfo(
                                                "fixture-selected",
                                                "[Slack message from U123456789 in C123456789] ブラウザの修正を続けて",
                                            ),
                                            SessionInfo(
                                                "fixture-path",
                                                "/tmp/private/context.txt",
                                                preview = "履歴の表示を整理して",
                                            ),
                                            SessionInfo("fixture-normal", "モデルと推論の強さを確認したい"),
                                            SessionInfo("fixture-long", "長い会話の見出しは一行に収めて必要なところで省略し、どの会話も同じ高さにします"),
                                        ),
                                ),
                            currentSessionId = "fixture-selected",
                        )
                    }
                    ShellBottomBar(ShellTab.AGENT)
                }
            }
        }
        compose.onNodeWithTag("conversation_session_fixture-selected").assertIsDisplayed()
        capture("05-readable-history")
    }
}
