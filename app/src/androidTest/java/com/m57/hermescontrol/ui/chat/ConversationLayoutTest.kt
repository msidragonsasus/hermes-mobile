package com.m57.hermescontrol.ui.chat

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.chat.components.ChatInputBar
import com.m57.hermescontrol.ui.chat.components.ChatScrollController
import com.m57.hermescontrol.ui.chat.components.ConversationHeaderActions
import com.m57.hermescontrol.ui.chat.components.ConversationLayout
import com.m57.hermescontrol.ui.chat.components.rememberChatScrollController
import com.m57.hermescontrol.ui.chat.fullbleed.FullBleedAgentMessage
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Shared production UI with local presentation data only; never connects to an AI service. */
class ConversationLayoutTest {
    @Test
    fun attachmentAndMicrophoneCentersMatchTheSingleLineInput() {
        compose.setContent { Fixture() }
        val attach = compose.onNodeWithTag("chat_attach_button").fetchSemanticsNode().boundsInRoot
        val mic = compose.onNodeWithTag("mic_button").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("chat_input").fetchSemanticsNode().boundsInRoot
        assertEquals(input.center.y, attach.center.y, 1f)
        assertEquals(input.center.y, mic.center.y, 1f)
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val draft = mutableStateOf(TextFieldValue(""))
    private val attachments = mutableStateOf(emptyList<Attachment>())
    private val width = mutableStateOf(390f)
    private val fontScale = mutableStateOf(1f)
    private val theme = mutableStateOf(ThemePreference.DARK)
    private lateinit var list: LazyListState
    private lateinit var scroll: ChatScrollController
    private var imeBottom = 0

    @Before
    fun setup() {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Composable
    private fun Fixture() {
        val physicalWidth = compose.activity.resources.displayMetrics.widthPixels
        val density = Density(physicalWidth / width.value, fontScale.value)
        CompositionLocalProvider(LocalDensity provides density) {
            HermesControlTheme(themePreference = theme.value) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    HermesScaffold(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 844.dp).testTag("conversation_fixture"),
                        pinTopBar = true,
                        title = { Text("Hermes") },
                        navigationIcon = NavIcon.Menu(onOpen = {}, tonal = true),
                        showShellMenu = false,
                        actions = { ConversationHeaderActions(onNew = {}, onSettings = {}) },
                    ) {
                        list = rememberLazyListState()
                        scroll = rememberChatScrollController(list, rememberCoroutineScope())
                        LaunchedEffect(scroll) { scroll.observeUserScrollPosition() }
                        val keyboardBottom = WindowInsets.ime.getBottom(density)
                        SideEffect { imeBottom = keyboardBottom }
                        ConversationLayout(
                            composer = {
                                ChatInputBar(
                                    inputFieldValue = draft.value,
                                    onInputChange = { draft.value = it },
                                    onSend = {},
                                    onMicTap = {},
                                    isListening = false,
                                    isAgentTyping = false,
                                    isConnected = true,
                                    commandCatalog = CommandCatalog(),
                                    pendingAttachments = attachments.value,
                                    currentSessionModel = "gpt-6.1-sol",
                                    reasoningLevel = "high",
                                )
                            },
                        ) {
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth().testTag("timeline"),
                                state = list,
                            ) {
                                items((0 until 12).toList()) { index ->
                                    if (index % 2 == 0) {
                                        ChatBubble(
                                            ChatMessage(
                                                id = "user-$index",
                                                role = MessageRole.USER,
                                                content = "今週の予定を整理して",
                                            ),
                                            showTimestamp = false,
                                        )
                                    } else {
                                        FullBleedAgentMessage(
                                            ChatMessage(
                                                id = "agent-$index",
                                                role = MessageRole.ASSISTANT,
                                                content = "予定を確認して、優先順位を整理しました。\n\n- 今日の締切を確認\n- 明日の準備を進める",
                                            ),
                                            showTurnHeader = true,
                                            speakerName = "Hermes",
                                            isDarkTheme = theme.value == ThemePreference.DARK,
                                            showTimestamp = false,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun assertNoOverlap() {
        val timeline = compose.onNodeWithTag("timeline").fetchSemanticsNode().boundsInRoot
        val capsule = compose.onNodeWithTag("chat_composer_surface").fetchSemanticsNode().boundsInRoot
        assertTrue("The dock must leave a positive conversation viewport", timeline.height > 0)
        assertTrue("Composer must not cover the timeline", timeline.bottom <= capsule.top + 1f)
        compose.onNodeWithTag("send_button").assertIsDisplayed()
        compose.onNodeWithTag("model_chip").assertIsDisplayed()
        compose.onNodeWithTag("reasoning_chip").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "muse-ui")
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use {
            val bitmap =
                if (name == "keyboard") {
                    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                } else {
                    compose.onNodeWithTag("conversation_fixture").captureToImage().asAndroidBitmap()
                }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun themesNarrowLargeTextAndWideLayoutKeepControlsVisible() {
        draft.value = TextFieldValue("確認したいことを入力")
        compose.setContent { Fixture() }
        listOf(
            Triple("dark-phone", 390f, 1f),
            Triple("light-phone", 390f, 1f),
            Triple("narrow-large-text", 320f, 1.5f),
            Triple("wide", 1000f, 1f),
        ).forEach { (name, w, scale) ->
            compose.runOnIdle {
                width.value = w
                fontScale.value = scale
                theme.value = if (name == "light-phone") ThemePreference.LIGHT else ThemePreference.DARK
                scroll.jumpToBottom()
            }
            compose.waitForIdle()
            assertNoOverlap()
            if (w > 840f) {
                val capsule = compose.onNodeWithTag("chat_composer_surface").fetchSemanticsNode().boundsInRoot
                val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
                assertTrue("Wide screens must keep a centered reading width", capsule.width < root.width * 0.85f)
                assertEquals(root.center.x, capsule.center.x, 2f)
            }
            screenshot(name)
        }
    }

    @Test
    fun dockResizeFollowsLatestButPreservesReaderPositionInHistory() {
        draft.value = TextFieldValue("short")
        compose.setContent { Fixture() }
        compose.runOnIdle { scroll.jumpToBottom() }
        compose.waitUntil(5000) { !list.canScrollForward }
        compose.runOnIdle {
            draft.value = TextFieldValue("一行目\n二行目\n三行目\n四行目\n五行目")
            attachments.value = listOf(Attachment("file:///fixture.txt", "下書き.txt", "text/plain", 120))
        }
        compose.waitForIdle()
        assertNoOverlap()
        compose.waitUntil(5000) { !list.canScrollForward }
        compose.runOnIdle { assertTrue(scroll.isFollowingBottom) }
        screenshot("expanded-dock")
        compose.runOnIdle { scroll.scrollToItem(2, 0) }
        compose.waitUntil(5000) { !scroll.isFollowingBottom }
        var first = 0
        var offset = 0
        compose.runOnIdle {
            first = list.firstVisibleItemIndex
            offset = list.firstVisibleItemScrollOffset
            draft.value = TextFieldValue("short")
            attachments.value = emptyList()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(first, list.firstVisibleItemIndex)
            assertEquals(offset, list.firstVisibleItemScrollOffset)
            assertFalse(scroll.isFollowingBottom)
        }
    }

    @Test
    fun keyboardAndReasoningKeepDraftAndDockAccessible() {
        compose.setContent { Fixture() }
        compose.runOnIdle { scroll.jumpToBottom() }
        compose.waitUntil(5000) { !list.canScrollForward }
        compose.onNodeWithTag("reasoning_chip").assertDoesNotExist()
        compose.onNodeWithTag("chat_input").performClick().performTextInput("送信しない下書き")
        compose.waitUntil(10000) { imeBottom > 0 }
        compose.onNodeWithTag("reasoning_chip").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reasoning_panel").assertIsDisplayed()
        compose.onNodeWithTag("reasoning_chip").performClick()
        compose.onNodeWithTag("reasoning_panel").assertDoesNotExist()
        compose.waitForIdle()
        assertNoOverlap()
        val bottom =
            compose
                .onNodeWithTag("chat_composer_surface")
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        assertTrue(bottom <= compose.activity.resources.displayMetrics.heightPixels - imeBottom + 1f)
        screenshot("keyboard")
        Espresso.pressBack()
        compose.onNodeWithTag("chat_input").assertTextEquals("送信しない下書き")
    }
}
