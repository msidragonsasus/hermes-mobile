package com.m57.hermescontrol.ui.chat

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.navigation.ShellTab
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.chat.components.ConversationMenuSheet
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
    private fun JapaneseTheme(content: @Composable () -> Unit) {
        val context = LocalContext.current
        val current = LocalConfiguration.current
        val configuration = remember(current) { Configuration(current).apply { setLocale(Locale.JAPAN) } }
        val localized = remember(context, configuration) { context.createConfigurationContext(configuration) }
        CompositionLocalProvider(
            LocalConfiguration provides configuration,
            LocalContext provides localized,
            LocalActivityResultRegistryOwner provides compose.activity,
        ) {
            HermesControlTheme(themePreference = ThemePreference.DARK, content = content)
        }
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
    fun captureComposerPickerToolsAndConnectionHistory() {
        val messages =
            listOf(
                ChatMessage(id = "fixture-user", role = MessageRole.USER, content = "表示確認用の会話です。"),
                ChatMessage(id = "fixture-answer", role = MessageRole.ASSISTANT, content = "確認結果です。詳細は必要なときに開けます。"),
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
        val state =
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
        every { model.uiState } returns MutableStateFlow(state)
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
        // Idle composer is one line: model / thinking controls appear only while composing.
        compose.onNodeWithTag("model_chip").assertDoesNotExist()
        compose.onNodeWithTag("reasoning_chip").assertDoesNotExist()
        capture("01-composer-tools-collapsed")
        compose.onNodeWithTag("chat_attach_button").performClick()
        compose.onNodeWithTag("chat_attachment_menu").assertIsDisplayed()
        capture("02-google-symbols-attachment-picker")
        Espresso.pressBack()
        compose.onAllNodesWithTag("chat_tool_toggle")[0].performClick()
        capture("03-tool-detail-expanded")
        compose.onAllNodesWithTag("chat_tool_toggle")[0].performClick()
        compose.onNodeWithTag("chat_connection_events").performClick()
        capture("04-connection-history-expanded")
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
