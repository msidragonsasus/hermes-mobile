package com.m57.hermescontrol.ui.chat

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.chat.components.ChatInputBar
import com.m57.hermescontrol.ui.chat.components.ConversationLayout
import com.m57.hermescontrol.ui.chat.components.MessageActionHost
import com.m57.hermescontrol.ui.chat.components.quotedReplyField
import com.m57.hermescontrol.ui.chat.fullbleed.FullBleedAgentMessage
import com.m57.hermescontrol.ui.common.HermesScaffold
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real shared message UI with synthetic content; no connection or inference. */
class MessageActionsTest {
    @Test
    fun markdownFileLinksUseAttachmentOpenAndWebLinksKeepTheirOwnTapHandling() {
        var opened: Attachment? = null
        var external: String? = null
        compose.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            external = uri
                        }
                    },
            ) {
                HermesControlTheme {
                    FullBleedAgentMessage(
                        message = agent.copy(content = "[PDF](</srv/報告 書.pdf>)\n\n[公式](https://example.com)"),
                        showTurnHeader = false,
                        isDarkTheme = true,
                        showTimestamp = false,
                        onOpenAttachment = { opened = it },
                    )
                }
            }
        }
        tapLink("PDF")
        compose.runOnIdle { assertEquals("gateway:/srv/報告 書.pdf", opened?.uri) }
        tapLink("公式")
        compose.runOnIdle { assertEquals("https://example.com", external) }
    }

    private fun tapLink(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText(label)
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        node.performTouchInput { click(layouts.single().getBoundingBox(0).center) }
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val agent = ChatMessage(id = "agent", role = MessageRole.ASSISTANT, content = "予定を確認しました。\n明日の準備を進めましょう。")
    private val user = ChatMessage(id = "user", role = MessageRole.USER, content = "今週の予定を整理して")
    private val draft = mutableStateOf(TextFieldValue("書きかけの相談"))
    private val theme = mutableStateOf(ThemePreference.DARK)
    private val scale = mutableStateOf(1f)
    private var sends = 0
    private var childClicks = 0

    private fun show(streaming: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                HermesControlTheme(themePreference = theme.value) {
                    HermesScaffold(title = { Text("Hermes") }, showShellMenu = false, pinTopBar = true) {
                        ConversationLayout(
                            onReply = { draft.value = quotedReplyField(it, draft.value) },
                            composer = {
                                ChatInputBar(
                                    inputFieldValue = draft.value,
                                    onInputChange = { draft.value = it },
                                    onSend = { sends++ },
                                    onMicTap = {},
                                    isListening = false,
                                    isAgentTyping = false,
                                    isConnected = true,
                                    commandCatalog = CommandCatalog(),
                                    pendingAttachments =
                                        listOf(
                                            Attachment("file:///fixture.txt", "資料.txt", "text/plain"),
                                        ),
                                    currentSessionModel = "gpt-6.1-sol",
                                )
                            },
                        ) {
                            ChatBubble(user, showTimestamp = false)
                            FullBleedAgentMessage(
                                agent.copy(isStreaming = streaming),
                                showTurnHeader = true,
                                showTimestamp = false,
                                isDarkTheme = theme.value == ThemePreference.DARK,
                            )
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    private fun open(tag: String = "fullbleed_agent_message") {
        compose.onNodeWithTag(tag).performTouchInput { longClick(center) }
        compose.onNodeWithTag("message_action_menu").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "message-menu")
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun userAndAgentUseTheSameCopyMenuWithoutPermanentCopyButtons() {
        show()
        compose.onNodeWithTag("fullbleed_copy").assertDoesNotExist()
        open()
        compose.onNodeWithTag("message_action_reply").assertIsDisplayed()
        compose.onNodeWithTag("message_action_copy").assertIsDisplayed()
        compose.onNodeWithTag("message_action_select").assertIsDisplayed()
        screenshot("dark")
        compose.runOnIdle { theme.value = ThemePreference.LIGHT }
        screenshot("light")
        pressBack()
        compose.runOnIdle { scale.value = 2f }
        open()
        compose.onNodeWithTag("message_action_copy").assertIsDisplayed()
        screenshot("large-text")
        compose.onNodeWithTag("message_action_copy").performClick()
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(
                agent.content,
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString(),
            )
            assertEquals("書きかけの相談", draft.value.text)
            assertEquals(0, sends)
            scale.value = 1f
        }
        open("chat_bubble_user")
        compose.onNodeWithTag("message_action_copy").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(
                user.content,
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString(),
            )
        }
    }

    @Test
    fun replyQuotesIntoExistingDraftWithoutSendingOrDroppingAttachments() {
        show()
        open()
        compose.onNodeWithTag("message_action_reply").performClick()
        compose.onNodeWithTag("chat_input").assertTextEquals("> 予定を確認しました。\n> 明日の準備を進めましょう。\n\n書きかけの相談")
        compose.onNodeWithText("資料.txt").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, sends) }
    }

    @Test
    fun selectionStaysInTheSameMessageAndBackClearsItWithoutChangingDraft() {
        show()
        val before = compose.onNodeWithText(agent.content).fetchSemanticsNode().boundsInRoot
        open()
        compose.onNodeWithTag("message_action_select").performClick()
        compose.onNodeWithTag("message_selection_text").assertIsDisplayed()
        compose.onNode(isDialog()).assertDoesNotExist()
        val after =
            compose
                .onNode(
                    hasText(agent.content) and hasAnyAncestor(hasTestTag("message_selection_text")),
                ).fetchSemanticsNode()
                .boundsInRoot
        assertEquals(before, after)
        screenshot("selection")
        pressBack()
        compose.onNodeWithTag("message_selection_text").assertDoesNotExist()
        compose.onNodeWithTag("chat_input").assertTextEquals("書きかけの相談")
    }

    @Test
    fun streamingMessagesHaveNoMenuAndChildActionsStillReceiveTaps() {
        compose.setContent {
            HermesControlTheme {
                Column(Modifier.fillMaxSize()) {
                    MessageActionHost(agent.copy(isStreaming = true)) {
                        Text("生成中", Modifier.fillMaxWidth().padding(24.dp).testTag("streaming"))
                    }
                    MessageActionHost(agent) {
                        TextButton(onClick = { childClicks++ }, modifier = Modifier.testTag("embedded_action")) {
                            Text("条件を変更")
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("streaming").performTouchInput { longClick() }
        compose.onNodeWithTag("message_action_menu").assertDoesNotExist()
        compose.onNodeWithTag("embedded_action").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, childClicks) }
    }
}
