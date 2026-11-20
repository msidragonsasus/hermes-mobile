package com.m57.hermescontrol.ui.chat

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.agents.BotConversations
import com.m57.hermescontrol.agents.BotSettingsKey
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SpecialistAppConnection
import com.m57.hermescontrol.data.model.SpecialistCapabilitiesResponse
import com.m57.hermescontrol.data.model.SpecialistPendingTurn
import com.m57.hermescontrol.data.model.SpecialistSetupDraft
import com.m57.hermescontrol.data.model.SpecialistSetupMessage
import com.m57.hermescontrol.data.model.SpecialistSetupSnapshot
import com.m57.hermescontrol.data.model.SpecialistSetupTurn
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.navigation.ShellTab
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.bots.BotsUiState
import com.m57.hermescontrol.ui.bots.BotsViewModel
import com.m57.hermescontrol.ui.chat.components.ConversationMenuSheet
import com.m57.hermescontrol.ui.chat.fullbleed.MAIN_CHAT_WAITING_KEY
import com.m57.hermescontrol.ui.common.ActionProgressController
import com.m57.hermescontrol.ui.common.ShellBottomBar
import com.m57.hermescontrol.ui.sessions.SessionsUiState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        capture("08-main-chat-light-keyboard")
        compose.onNodeWithTag("reasoning_chip").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reasoning_panel").assertIsDisplayed()
        listOf("Low", "Medium", "High", "Max").forEach { label ->
            compose.onNodeWithTag("reasoning_level_${label.lowercase()}").assertTextEquals(label).assertIsDisplayed()
        }
        capture("09-main-chat-light-keyboard-reasoning")
        compose.onNodeWithTag("reasoning_chip").performClick()
        compose.onNodeWithTag("reasoning_panel").assertDoesNotExist()
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
        val latestFab = compose.onNodeWithContentDescription("最新のメッセージへ移動")
        latestFab.assertDoesNotExist()

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
        compose.runOnIdle {
            streaming.value = StreamingState(streamingMessage = reply, isWritingText = true)
        }
        val firstReply =
            compose
                .onNodeWithText(text)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val prompt = compose.onNodeWithText("Current prompt").fetchSemanticsNode().boundsInRoot
        assertTrue(prompt.bottom <= firstReply.top)

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
        val grownReply =
            compose
                .onNodeWithText(text)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(firstReply.bottom, grownReply.bottom, 1f)
        latestFab.assertDoesNotExist()
        compose.onNodeWithTag(MAIN_CHAT_WAITING_KEY).assertDoesNotExist()
        val elapsed = SystemClock.elapsedRealtime() - wallBefore
        val cpuNanos = compose.runOnIdle { Debug.threadCpuTimeNanos() } - cpuBefore
        val heapAfter = runtime.totalMemory() - runtime.freeMemory()
        File(instrumentation.targetContext.getExternalFilesDir(null), "chat-streaming-metrics.txt").writeText(
            "historyRows=501 updates=120 elapsedMs=$elapsed mainThreadCpuNs=$cpuNanos\n" +
                "javaHeapUsedBefore=$heapBefore javaHeapUsedAfter=$heapAfter\n" + graphicsStats(),
        )

        compose.onNodeWithTag("main_chat_timeline").performScrollToIndex(12)
        val anchor = compose.onNodeWithText("Previous message 489")
        val beforeGrowth = anchor.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        latestFab.assertIsDisplayed()
        text += " more streaming output".repeat(80)
        compose.runOnIdle {
            streaming.value = StreamingState(streamingMessage = reply.copy(content = text), isWritingText = true)
        }
        assertEquals(beforeGrowth, anchor.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        latestFab.performClick()
        compose.onNodeWithText(text).assertIsDisplayed()
        latestFab.assertDoesNotExist()
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
    fun mainChatReturnsToLatestAndKeepsTheHistoryAnchorWhenPaging() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        val messages =
            (1..60).map { index ->
                ChatMessage(id = "fixture-$index", role = MessageRole.USER, content = "Transcript message $index")
            }
        val fixture =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "fixture-session",
                    messages = messages,
                    hasOlderMessages = true,
                    connectionStatus = ConnectionStatus.CONNECTED,
                ),
            )
        val model = mockk<ChatViewModel>(relaxed = true)
        every { model.specialistChat } returns
            mockk { every { state } returns MutableStateFlow(SpecialistChatState()) }
        every { model.uiState } returns fixture
        every { model.streamingState } returns MutableStateFlow(StreamingState())
        val search = ChatSearchState()
        every { model.searchState } returns search
        every { model.actionProgress } returns ActionProgressController(CoroutineScope(Dispatchers.Main))
        every { model.loadOlderMessages() } answers { fixture.value = fixture.value.copy(isLoadingOlder = true) }
        val inChat = mutableStateOf(true)
        val latest = compose.onNodeWithText("Transcript message 60", useUnmergedTree = true)
        val latestFab = compose.onNodeWithContentDescription("最新のメッセージへ移動")
        // Read the initial layout before the Compose clock can run a post-layout snap.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            JapaneseTheme {
                val stateHolder = rememberSaveableStateHolder()
                if (inChat.value) {
                    stateHolder.SaveableStateProvider("chat") { ChatScreen(viewModel = model, onOpenDrawer = {}) }
                } else {
                    Text("Other screen")
                }
            }
        }

        try {
            latest.assertIsDisplayed()
            compose.onNodeWithText("Transcript message 1", useUnmergedTree = true).assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        latestFab.assertDoesNotExist()
        compose.runOnIdle { inChat.value = false }
        latest.assertDoesNotExist()
        val updatedMessages =
            messages + ChatMessage(id = "fixture-61", role = MessageRole.USER, content = "Transcript message 61")
        compose.runOnIdle {
            fixture.value = fixture.value.copy(messages = updatedMessages)
            inChat.value = true
        }
        compose.onNodeWithText("Transcript message 61", useUnmergedTree = true).assertIsDisplayed()
        verify(exactly = 0) { model.loadOlderMessages() }

        val timeline = compose.onNodeWithTag("main_chat_timeline")
        timeline.performScrollToIndex(58)
        val anchor = compose.onNodeWithText("Transcript message 3", useUnmergedTree = true)

        fun anchorTop(): Float =
            anchor
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot.top
        val beforePaging = anchorTop()
        verify(exactly = 1) { model.loadOlderMessages() }
        compose.runOnIdle {
            val older =
                (1..20).map { index ->
                    ChatMessage(id = "older-$index", role = MessageRole.USER, content = "Older message $index")
                }
            fixture.value =
                fixture.value.copy(
                    messages = older + updatedMessages,
                    isLoadingOlder = false,
                    hasOlderMessages = false,
                )
        }
        assertEquals(beforePaging, anchorTop(), 1f)
        val afterPaging = fixture.value
        val latestArrival = ChatMessage(id = "fixture-62", role = MessageRole.USER, content = "Transcript message 62")
        compose.runOnIdle { inChat.value = false }
        anchor.assertDoesNotExist()
        compose.runOnIdle {
            fixture.value = afterPaging.copy(messages = afterPaging.messages + latestArrival)
            inChat.value = true
        }
        assertEquals(beforePaging, anchorTop(), 1f)
        latestFab.assertIsDisplayed()

        val otherMessages = messages.map { it.copy(content = "Other ${it.content}") }
        compose.runOnIdle {
            fixture.value = afterPaging.copy(currentSessionId = "another-session", messages = otherMessages)
        }
        compose.onNodeWithText("Other Transcript message 60").assertIsDisplayed()
        latestFab.assertDoesNotExist()
        compose.runOnIdle { fixture.value = afterPaging.copy(messages = afterPaging.messages + latestArrival) }
        assertEquals(beforePaging, anchorTop(), 1f)

        latestFab.performClick()
        compose.onNodeWithText(latestArrival.content).assertIsDisplayed()
        latestFab.assertDoesNotExist()
        val newMessage = latestArrival.copy(id = "fixture-63", content = "Transcript message 63")
        compose.runOnIdle { fixture.value = fixture.value.copy(messages = fixture.value.messages + newMessage) }
        compose.onNodeWithText(newMessage.content).assertIsDisplayed()
        latestFab.assertDoesNotExist()

        val heldAnchor = compose.onNodeWithText(newMessage.content)
        val heldPosition = heldAnchor.fetchSemanticsNode().boundsInRoot
        val arrivingMessage = newMessage.copy(id = "fixture-64", content = "Transcript message 64")
        timeline.performTouchInput { down(center) }
        try {
            compose.runOnIdle {
                fixture.value =
                    fixture.value.copy(
                        messages = fixture.value.messages + arrivingMessage,
                    )
            }
            assertEquals(heldPosition, heldAnchor.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
            latestFab.assertIsDisplayed()
        } finally {
            timeline.performTouchInput { up() }
        }

        compose.runOnIdle {
            search.isActive = true
            search.query = "message 20"
            search.matchOffsets = listOf(11)
            search.currentIndex = 0
            search.matchedIds = setOf("fixture-20")
            search.currentMatchId = "fixture-20"
        }
        compose.onNodeWithText("Transcript message 20", useUnmergedTree = true).assertIsDisplayed()
        latestFab.assertIsDisplayed()
    }

    @Test
    fun mainTimelineRespondsToTouchWhileIdleWaitingAndStreaming() = assertTimelineRespondsToTouch("default")

    @Test
    fun specialistTimelineRespondsToTouchWithDraftAndFooter() = assertTimelineRespondsToTouch("bot-31b01926d35d41da")

    private fun assertTimelineRespondsToTouch(profile: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        val scope = "touch-test"
        val epoch = 20L
        val session = "touch-$profile"
        val isSpecialist = profile != "default"
        val messages =
            List(100) { index ->
                ChatMessage(id = "history-$index", role = MessageRole.USER, content = "History message $index")
            }
        val setup =
            if (isSpecialist) {
                SpecialistChatState(
                    target = SpecialistChatTarget(scope, epoch, profile),
                    sessionId = session,
                    capabilities = SpecialistCapabilitiesResponse(setup_conversation = true, setup_apply = true),
                    error = "unavailable",
                    snapshot =
                        SpecialistSetupSnapshot(
                            name = profile,
                            draft = SpecialistSetupDraft("touch-draft"),
                            conversation_turns =
                                messages.chunked(2).mapIndexed { index, pair ->
                                    SpecialistSetupTurn(
                                        request_id = "turn-$index",
                                        session_id = session,
                                        conversation_revision = index,
                                        revision = index,
                                        user = SpecialistSetupMessage(pair[0].id, "user", pair[0].content),
                                        assistant = SpecialistSetupMessage(pair[1].id, "assistant", pair[1].content),
                                    )
                                },
                        ),
                )
            } else {
                SpecialistChatState()
            }
        val fixture =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = session,
                    canonicalBotChatId = session,
                    canonicalBotChatEpoch = epoch,
                    messages = if (isSpecialist) emptyList() else messages,
                    connectionStatus = ConnectionStatus.CONNECTED,
                ),
            )
        val streaming = MutableStateFlow(StreamingState())
        val reply =
            ChatMessage(id = "touch-stream", role = MessageRole.ASSISTANT, content = "Live answer", isStreaming = true)
        val model = mockk<ChatViewModel>(relaxed = true)
        every { model.specialistChat } returns mockk { every { state } returns MutableStateFlow(setup) }
        every { model.uiState } returns fixture
        every { model.streamingState } returns streaming
        every { model.searchState } returns ChatSearchState()
        every { model.actionProgress } returns ActionProgressController(CoroutineScope(Dispatchers.Main))
        val roster = mockk<BotsViewModel>(relaxed = true)
        every { roster.getCloseable<AutoCloseable>(any()) } returns null
        every { roster.uiState } returns MutableStateFlow(BotsUiState())
        mockkObject(AuthManager)
        try {
            every { AuthManager.activeProfileId } returns MutableStateFlow<String?>(profile)
            every { AuthManager.connectionEpoch } returns MutableStateFlow(epoch)
            every { AuthManager.uiPreferenceScope() } returns scope
            compose.setContent {
                InstallTestRoster(roster)
                JapaneseTheme { ChatScreen(viewModel = model, onOpenDrawer = {}) }
            }
            val timeline = compose.onNodeWithTag("main_chat_timeline")
            val latest = compose.onNodeWithContentDescription("最新のメッセージへ移動")

            fun scrollOffset(): Float =
                timeline.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

            fun firstVisibleMessageNumber(): Int {
                // The latest-first list puts its first visible transcript message at the bottom.
                val node =
                    compose
                        .onAllNodes(hasText("History message ", substring = true), useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .filter { it.boundsInRoot.height > 0f }
                        .maxBy { it.boundsInRoot.bottom }
                return node.config[SemanticsProperties.Text]
                    .first()
                    .text
                    .substringAfterLast(' ')
                    .toInt()
            }

            val failedSwipes = mutableListOf<String>()
            for (phase in listOf("idle", "waiting", "streaming")) {
                compose.runOnIdle {
                    fixture.value = fixture.value.copy(isAgentTyping = phase != "idle")
                    if (phase == "streaming") {
                        streaming.value = StreamingState(streamingMessage = reply, isWritingText = true)
                    }
                }
                if (isSpecialist) {
                    compose.onNodeWithTag("specialist_chat_review").assertIsDisplayed()
                    compose.onNodeWithTag("specialist_chat_actions").assertIsDisplayed()
                }
                if (phase == "waiting") compose.onNodeWithTag(MAIN_CHAT_WAITING_KEY).assertIsDisplayed()
                if (phase == "streaming") compose.onNodeWithText(reply.content).assertIsDisplayed()
                if (phase == "streaming") {
                    val beforeDrag = scrollOffset()
                    timeline.performTouchInput {
                        down(percentOffset(0.5f, 0.35f))
                        moveTo(percentOffset(0.5f, 0.65f), delayMillis = 160)
                    }
                    val beforeUpdate = scrollOffset()
                    compose.runOnIdle {
                        streaming.value =
                            streaming.value.copy(streamingMessage = reply.copy(content = "Live answer with more text"))
                    }
                    val afterUpdate = scrollOffset()
                    timeline.performTouchInput { moveTo(percentOffset(0.5f, 0.88f), delayMillis = 160) }
                    val afterDrag = scrollOffset()
                    timeline.performTouchInput { up() }
                    if (beforeUpdate <= beforeDrag || afterDrag <= afterUpdate || afterUpdate < beforeUpdate) {
                        failedSwipes += "streaming drag ($beforeDrag → $beforeUpdate → $afterUpdate → $afterDrag)"
                    }
                }
                // A short drag can move within the draft card. A full swipe must reach older transcript rows.
                val before = firstVisibleMessageNumber()
                timeline.performTouchInput { swipeDown(startY = height * 0.35f, endY = height * 0.88f) }
                val after = firstVisibleMessageNumber()
                if (after >= before) {
                    failedSwipes += "$phase down ($before → $after)"
                } else {
                    timeline.performTouchInput { swipeUp(startY = height * 0.84f, endY = height * 0.35f) }
                    val returned = firstVisibleMessageNumber()
                    if (returned <= after) failedSwipes += "$phase up ($after → $returned)"
                }
                if (latest.isDisplayed()) latest.performClick()
            }
            assertTrue("Swipes that left the same item visible: $failedSwipes", failedSwipes.isEmpty())
        } finally {
            unmockkObject(AuthManager)
        }
    }

    @Composable
    private fun InstallTestRoster(roster: BotsViewModel) {
        val factory =
            remember(roster) {
                object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(roster)!!
                }
            }
        ViewModelProvider(checkNotNull(LocalViewModelStoreOwner.current), factory)[BotsViewModel::class.java]
    }

    @Test
    fun mainChatLongPressShowsNativeHandlesAndCopiesOnlyTheSelectedWord() =
        withTouchChat { _, _, _ -> assertNativeMessageSelection() }

    @Test
    fun setupChatLongPressShowsNativeHandlesAndCopiesOnlyTheSelectedWord() =
        withTouchChat(setupChat = true) { _, _, _ -> assertNativeMessageSelection() }

    private fun assertNativeMessageSelection() {
        val text = compose.onNodeWithText("Select this exact word", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val before = text.fetchSemanticsNode().boundsInRoot
        text.performTouchInput { longClick(layouts.single().getBoundingBox(13).center) }
        val handles =
            SemanticsMatcher("Compose selection handle") { node ->
                node.config.any { it.key.name == "SelectionHandleInfo" }
            }
        compose.onAllNodes(handles, useUnmergedTree = true).assertCountEquals(2)
        assertEquals(before, text.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("message_action_menu").assertDoesNotExist()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val service = automation.serviceInfo
        val originalFlags = service.flags
        try {
            service.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = service
            val copy = compose.activity.getString(android.R.string.copy)
            var copyBounds: Rect? = null
            compose.waitUntil(5_000) {
                copyBounds =
                    automation.windows
                        .asSequence()
                        .flatMap {
                            it.root
                                ?.findAccessibilityNodeInfosByText(copy)
                                .orEmpty()
                                .asSequence()
                        }.firstOrNull {
                            it.isVisibleToUser &&
                                (it.text?.toString() == copy || it.contentDescription?.toString() == copy)
                        }?.let { node -> Rect().also(node::getBoundsInScreen) }
                copyBounds != null
            }
            val bounds = checkNotNull(copyBounds)
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event =
                    MotionEvent.obtain(
                        downTime,
                        SystemClock.uptimeMillis(),
                        action,
                        bounds.exactCenterX(),
                        bounds.exactCenterY(),
                        0,
                    )
                try {
                    assertTrue(automation.injectInputEvent(event, true))
                } finally {
                    event.recycle()
                }
            }
            compose.waitUntil(
                5_000,
            ) { compose.onAllNodes(handles, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle {
                val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals(
                    "exact",
                    clipboard.primaryClip
                        ?.getItemAt(0)
                        ?.text
                        ?.toString(),
                )
            }
        } finally {
            service.flags = originalFlags
            automation.serviceInfo = service
        }
    }

    @Test
    fun mainChatReplyPreviewCancelsCollapsesReopensAndSendsMarkdownQuote() =
        withTouchChat { model, _, _ ->
            val input = compose.onNodeWithTag("chat_input")
            val draft = "A long draft first line that overflows the collapsed pill\nThis second line must survive"
            val quote = "Previous topic\nFollow-up detail"
            val composer = compose.onNodeWithTag("chat_composer_surface")
            val collapsedHeight = composer.fetchSemanticsNode().boundsInRoot.height

            fun reply() {
                compose.onNodeWithTag("message_actions_topic").performTouchInput { click() }
                compose.onNodeWithTag("message_action_reply").performTouchInput { click() }
                waitForChatTag("chat_reply_preview")
            }

            reply()
            input.assertTextEquals("").performTextInput(draft)
            compose.onNodeWithTag("chat_reply_cancel").performTouchInput { click() }
            compose.onNodeWithTag("chat_reply_preview").assertDoesNotExist()
            input.assertTextEquals(draft)
            reply()
            input.assertTextEquals(draft)
            compose
                .onNode(
                    hasText(quote) and hasAnyAncestor(hasTestTag("chat_reply_preview")),
                    useUnmergedTree = true,
                ).assertIsDisplayed()
            compose.onNodeWithTag("model_chip").assertIsDisplayed()
            assertTrue(composer.fetchSemanticsNode().boundsInRoot.height > collapsedHeight)
            capture("reply-preview-expanded")

            Espresso.pressBack()
            waitForChatTag("chat_composer_toolbar", present = false)
            compose.onNodeWithTag("chat_reply_preview").assertDoesNotExist()
            compose.onNodeWithTag("model_chip").assertDoesNotExist()
            assertEquals(collapsedHeight, composer.fetchSemanticsNode().boundsInRoot.height, 1f)
            capture("reply-preview-collapsed")
            input.assertTextEquals(draft).performTouchInput { click() }
            waitForChatTag("chat_reply_preview")
            input.assertTextEquals(draft)
            compose.onNodeWithTag("send_button").performTouchInput { click() }
            verify(exactly = 1) { model.sendMessage("> Previous topic\n> Follow-up detail\n\n$draft", steer = false) }
            compose.onNodeWithTag("chat_reply_preview").assertDoesNotExist()
            input.assertTextEquals("")
        }

    @Test
    fun mainChatBusyShowsOneSendForDraftAndStopWhenEmpty() = assertBusyComposer(specialist = false)

    @Test
    fun specialistChatBusyShowsOneSendForDraftAndStopWhenEmpty() = assertBusyComposer(specialist = true)

    private fun assertBusyComposer(specialist: Boolean) =
        withTouchChat(specialist = specialist) { model, fixture, _ ->
            compose.runOnIdle { fixture.value = fixture.value.copy(isAgentTyping = true) }
            compose.onNodeWithTag(MAIN_CHAT_WAITING_KEY).assertIsDisplayed()
            val input = compose.onNodeWithTag("chat_input")
            val stop = compose.onNodeWithTag("chat_stop_button")
            stop.assertIsDisplayed()
            input.performTouchInput { click() }
            waitForChatTag("chat_composer_toolbar")
            input.performTextInput("Send during the active turn")
            val sendButton = compose.onNodeWithTag("send_button")
            compose.onAllNodesWithTag("send_button").assertCountEquals(1)
            compose.onNodeWithTag("chat_steer_button").assertDoesNotExist()
            stop.assertDoesNotExist()
            compose
                .onNodeWithContentDescription(
                    compose.activity.getString(R.string.draft_add_queue),
                ).assertDoesNotExist()
            sendButton.performTouchInput { click() }
            verify(exactly = 1) { model.sendMessage("Send during the active turn", steer = specialist) }
            stop.performTouchInput { click() }
            verify(exactly = 1) { model.interruptSession() }
        }

    @Test
    fun setupChatTouchReplySubmitsQuotedTextAndDisplaysTheSharedDots() =
        withTouchChat(setupChat = true) { _, _, controller ->
            compose.onNodeWithTag(MAIN_CHAT_WAITING_KEY).assertDoesNotExist()
            compose.onNodeWithTag("message_actions_specialist:answer").performTouchInput { click() }
            compose.onNodeWithTag("message_action_reply").performTouchInput { click() }
            waitForChatTag("chat_reply_preview")
            val input = compose.onNodeWithTag("chat_input")
            input.assertTextEquals("").performTextInput("Setup reply")
            compose.onNodeWithTag("send_button").performTouchInput { click() }
            verify(exactly = 1) { controller.submit("> Select this exact word\n\nSetup reply") }
            compose.onNodeWithTag(MAIN_CHAT_WAITING_KEY).assertIsDisplayed()
            compose.onNodeWithTag("specialist_chat_actions").assertDoesNotExist()
            compose.onNodeWithText("返信を考え中…").assertDoesNotExist()
            compose.onNodeWithTag("chat_reply_preview").assertDoesNotExist()
            capture("specialist-setup-waiting")
        }

    private fun waitForChatTag(
        tag: String,
        present: Boolean = true,
    ) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() == present }
    }

    private fun withTouchChat(
        specialist: Boolean = false,
        setupChat: Boolean = false,
        test: (ChatViewModel, MutableStateFlow<ChatUiState>, SpecialistChatController) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val profile = if (specialist || setupChat) "bot-touch" else "default"
        val scope = "touch-chat-controls"
        val session = "chat-controls-$profile"
        val epoch = 20L
        val topic = ChatMessage(id = "topic", role = MessageRole.USER, content = "Previous topic\nFollow-up detail")
        val answer = ChatMessage(id = "answer", role = MessageRole.ASSISTANT, content = "Select this exact word")
        val setup =
            SpecialistChatState(
                target = SpecialistChatTarget(scope, epoch, profile),
                sessionId = session,
                capabilities = SpecialistCapabilitiesResponse(setup_conversation = true, setup_apply = true),
                snapshot =
                    SpecialistSetupSnapshot(
                        name = profile,
                        setup_completed = !setupChat,
                        conversation_turns =
                            if (setupChat) {
                                listOf(
                                    SpecialistSetupTurn(
                                        request_id = "prior-turn",
                                        session_id = session,
                                        conversation_revision = 1,
                                        revision = 1,
                                        user = SpecialistSetupMessage(topic.id, "user", topic.content),
                                        assistant = SpecialistSetupMessage(answer.id, "assistant", answer.content),
                                    ),
                                )
                            } else {
                                emptyList()
                            },
                    ),
            )
        val setupState = MutableStateFlow(setup)
        val controller = mockk<SpecialistChatController>(relaxed = true)
        every { controller.state } returns setupState
        every { controller.submit(any()) } answers {
            setupState.value =
                setup.copy(
                    snapshot =
                        setup.snapshot?.copy(
                            pending_turn = SpecialistPendingTurn("submitted", firstArg(), Double.MAX_VALUE),
                        ),
                )
            true
        }
        val fixture =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = session,
                    canonicalBotChatId = session,
                    canonicalBotChatEpoch = epoch,
                    messages = if (setupChat) emptyList() else listOf(topic, answer),
                    connectionStatus = ConnectionStatus.CONNECTED,
                    currentSessionModel = "gpt-6.2-astra",
                    reasoningLevel = "medium",
                ),
            )
        val model = mockk<ChatViewModel>(relaxed = true)
        every { model.specialistChat } returns controller
        every { model.uiState } returns fixture
        every { model.streamingState } returns MutableStateFlow(StreamingState())
        every { model.searchState } returns ChatSearchState()
        every { model.actionProgress } returns ActionProgressController(CoroutineScope(Dispatchers.Main))
        val roster = mockk<BotsViewModel>(relaxed = true)
        every { roster.getCloseable<AutoCloseable>(any()) } returns null
        every { roster.uiState } returns MutableStateFlow(BotsUiState())
        mockkObject(AuthManager)
        try {
            every { AuthManager.activeProfileId } returns MutableStateFlow<String?>(profile)
            every { AuthManager.connectionEpoch } returns MutableStateFlow(epoch)
            every { AuthManager.uiPreferenceScope() } returns scope
            compose.setContent {
                InstallTestRoster(roster)
                JapaneseTheme(if (setupChat) ThemePreference.LIGHT else ThemePreference.DARK) {
                    val density = LocalDensity.current
                    val fontScale = if (setupChat) 1.4f else density.fontScale
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        ChatScreen(viewModel = model, onOpenDrawer = {})
                    }
                }
            }
            test(model, fixture, controller)
        } finally {
            unmockkObject(AuthManager)
        }
    }

    @Test
    fun specialistUsesSharedTimelineAndKeepsSetupAndBotSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        val profile = "bot-31b01926d35d41da"
        val botTitle = "Research bot"
        val epoch = 20L
        val scope = "synthetic-scope"
        val sessionId = "research-session"
        val setup =
            SpecialistChatState(
                target = SpecialistChatTarget(scope, epoch, profile),
                sessionId = sessionId,
                capabilities =
                    SpecialistCapabilitiesResponse(
                        setup_conversation = true,
                        setup_apply = true,
                        edit_conversation = true,
                    ),
                snapshot =
                    SpecialistSetupSnapshot(
                        name = profile,
                        revision = 7,
                        draft =
                            SpecialistSetupDraft(
                                "draft",
                                apps = listOf(SpecialistAppConnection("app", "Evidence app", "read")),
                            ),
                    ),
            )
        val setupState = MutableStateFlow(setup)
        val controller = mockk<SpecialistChatController>(relaxed = true)
        every { controller.state } returns setupState
        every { controller.setEditing(any()) } answers
            { setupState.value = setupState.value.copy(editing = firstArg()) }
        val fixture =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = sessionId,
                    canonicalBotChatId = sessionId,
                    canonicalBotChatEpoch = epoch,
                    connectionStatus = ConnectionStatus.CONNECTED,
                ),
            )
        val model = mockk<ChatViewModel>(relaxed = true)
        every { model.specialistChat } returns controller
        every { model.uiState } returns fixture
        every { model.streamingState } returns MutableStateFlow(StreamingState())
        every { model.searchState } returns ChatSearchState()
        every { model.actionProgress } returns ActionProgressController(CoroutineScope(Dispatchers.Main))
        val roster = mockk<BotsViewModel>(relaxed = true)
        every { roster.getCloseable<AutoCloseable>(any()) } returns null
        mockkObject(AuthManager, NavigationController)
        try {
            every { AuthManager.activeProfileId } returns MutableStateFlow<String?>(profile)
            every { AuthManager.connectionEpoch } returns MutableStateFlow(epoch)
            every { AuthManager.uiPreferenceScope() } returns scope
            every { NavigationController.navigateTo(any()) } returns Unit
            val rosterState = MutableStateFlow(BotsUiState(rosterScope = BotConversations.serverScope()))
            every { roster.uiState } returns rosterState
            val inChat = mutableStateOf(true)
            compose.setContent {
                InstallTestRoster(roster)
                JapaneseTheme {
                    val stateHolder = rememberSaveableStateHolder()
                    if (inChat.value) {
                        stateHolder.SaveableStateProvider(
                            "specialist",
                        ) { ChatScreen(viewModel = model, onOpenDrawer = {}) }
                    } else {
                        Text("Other screen")
                    }
                }
            }

            compose.onNodeWithText("専門ボット").assertIsDisplayed()
            compose.onNodeWithText(profile, useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithContentDescription(profile, useUnmergedTree = true).assertDoesNotExist()
            compose.runOnIdle {
                rosterState.value =
                    rosterState.value.copy(
                        profiles =
                            listOf(
                                ProfileInfo(
                                    name = profile,
                                    display_name = botTitle,
                                    ui_meta =
                                        mapOf(
                                            "hermes-bots" to
                                                JsonObject(
                                                    mapOf(
                                                        "avatar" to
                                                            JsonObject(mapOf("icon" to JsonPrimitive("science"))),
                                                    ),
                                                ),
                                        ),
                                ),
                            ),
                    )
            }

            compose.onNodeWithTag("menu_button", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithContentDescription(botTitle, useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("main_chat_timeline").assertIsDisplayed()
            compose.onNodeWithTag("specialist_chat_review").assertIsDisplayed()
            compose.onNodeWithText("Evidence app", substring = true).assertIsDisplayed()
            compose.onNodeWithText("この設定で確定").performClick()
            verify(exactly = 1) { controller.confirm("draft", 7) }

            compose.runOnIdle {
                setupState.value =
                    setup.copy(
                        snapshot = setup.snapshot?.copy(setup_completed = true, activation = "applied", draft = null),
                    )
            }
            compose.onNodeWithTag("specialist_chat_review").assertDoesNotExist()
            compose.onNodeWithTag("specialist_chat_actions").assertDoesNotExist()
            compose.onNodeWithTag("chat_header_pill").performClick()
            compose.onNodeWithTag("header_sheet_specialist_actions").assertIsDisplayed()
            compose.onNodeWithText("会話で設定を変更").performClick()
            verify(exactly = 1) { controller.setEditing(true) }
            compose.onNodeWithTag("specialist_chat_actions").assertIsDisplayed()
            compose.onNodeWithText("通常の会話に戻る").performClick()
            verify(exactly = 1) { controller.setEditing(false) }
            compose.onNodeWithTag("specialist_chat_actions").assertDoesNotExist()
            compose.onNodeWithTag("chat_header_pill").performClick()
            compose.onNodeWithText("${botTitle}の設定").performClick()
            verify(exactly = 1) { NavigationController.navigateTo(BotSettingsKey(profile)) }

            compose.runOnIdle {
                fixture.value =
                    fixture.value.copy(
                        messages =
                            listOf(
                                ChatMessage(id = "first", role = MessageRole.USER, content = "Bot prompt"),
                                ChatMessage(id = "tool", role = MessageRole.TOOL, content = "Hidden bot tool"),
                                ChatMessage(
                                    id = "prose",
                                    role = MessageRole.ASSISTANT,
                                    content = "Bot answer",
                                    reasoningText = "Hidden reasoning",
                                ),
                                ChatMessage(id = "next", role = MessageRole.USER, content = "Next bot prompt"),
                            ),
                        isAgentTyping = true,
                    )
            }
            compose.onNodeWithText("Bot answer", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag(MAIN_CHAT_WAITING_KEY).assertIsDisplayed()
            compose.onNodeWithText("Hidden bot tool", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Hidden reasoning", substring = true).assertDoesNotExist()

            compose.runOnIdle { inChat.value = false }
            compose.onNodeWithText("Other screen").assertIsDisplayed()
            val history =
                List(60) { index ->
                    ChatMessage(id = "bot-history-$index", role = MessageRole.USER, content = "Bot history $index")
                }
            compose.runOnIdle { fixture.value = fixture.value.copy(messages = history, isAgentTyping = false) }
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnUiThread { inChat.value = true }
                compose.mainClock.advanceTimeByFrame()
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithText("Bot history 59").assertIsDisplayed()
                compose.onNodeWithText("Bot history 0").assertDoesNotExist()
            } finally {
                compose.mainClock.autoAdvance = true
            }
            val timeline = compose.onNodeWithTag("main_chat_timeline")
            timeline.performScrollToIndex(20)
            val anchor = compose.onNodeWithText("Bot history 39")
            val position = anchor.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { inChat.value = false }
            anchor.assertDoesNotExist()
            compose.runOnIdle { inChat.value = true }
            assertEquals(position, anchor.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithContentDescription("最新のメッセージへ移動").performClick()
            compose.onNodeWithText("Bot history 59").assertIsDisplayed()
        } finally {
            unmockkObject(AuthManager, NavigationController)
        }
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
