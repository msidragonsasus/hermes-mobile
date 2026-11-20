package com.m57.hermescontrol.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.espresso.Espresso
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.components.ChatComposer
import com.m57.hermescontrol.ui.chat.components.ChatInputBar
import com.m57.hermescontrol.ui.chat.fullbleed.ConnectionEventRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatComposerControlsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun stringDraftComposerPreservesCursorAndIndependentInputAndSendGates() {
        val draft = mutableStateOf("abcd")
        val inputEnabled = mutableStateOf(true)
        val sendEnabled = mutableStateOf(false)
        val sent = mutableListOf<String>()
        compose.setContent {
            HermesControlTheme {
                ChatComposer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = { sent += draft.value },
                    inputEnabled = inputEnabled.value,
                    sendEnabled = sendEnabled.value,
                    placeholder = "Request",
                )
            }
        }
        compose.onNodeWithTag("chat_input").assertIsEnabled().performTextInputSelection(TextRange(2))
        compose.onNodeWithTag("chat_input").performTextInput("X")
        compose.onNodeWithTag("chat_input").assertTextEquals("abXcd")
        compose.onNodeWithTag("send_button").assertIsNotEnabled()
        compose.onNodeWithTag("chat_attach_button").assertDoesNotExist()
        compose.onNodeWithTag("mic_button").assertDoesNotExist()
        compose.runOnIdle { sendEnabled.value = true }
        compose.onNodeWithTag("send_button").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("abXcd"), sent)
            draft.value = ""
        }
        compose.onNodeWithTag("chat_input").assertTextEquals("")
        compose.onNodeWithTag("send_button").assertIsNotEnabled()
        compose.runOnIdle { inputEnabled.value = false }
        compose.onNodeWithTag("chat_input").assertIsNotEnabled()
    }

    private fun composer(
        connected: Boolean = true,
        onPhoto: () -> Unit = {},
        onCamera: () -> Unit = {},
        onFile: () -> Unit = {},
        onModel: () -> Unit = {},
        onReasoning: (String?) -> Unit = {},
    ) {
        compose.setContent {
            var draft by remember { mutableStateOf(TextFieldValue("unsent draft")) }
            HermesControlTheme {
                ChatInputBar(
                    inputFieldValue = draft,
                    onInputChange = { draft = it },
                    onSend = {},
                    onMicTap = {},
                    isListening = false,
                    isAgentTyping = false,
                    isConnected = connected,
                    commandCatalog = CommandCatalog(),
                    onImageTap = onPhoto,
                    onCameraTap = onCamera,
                    onFileTap = onFile,
                    currentSessionModel = "model-a",
                    reasoningLevel = "high",
                    onModelTap = onModel,
                    onReasoningTap = onReasoning,
                )
            }
        }
    }

    @Test
    fun everyAttachmentActionDismissesAndCanBeOpenedAgainWithoutChangingDraft() {
        val selected = mutableListOf<String>()
        composer(
            onPhoto = { selected += "photo" },
            onCamera = { selected += "camera" },
            onFile = { selected += "file" },
        )
        listOf("photo", "camera", "file").forEach { choice ->
            compose.onNodeWithTag("chat_attach_button").performClick()
            compose.onNodeWithTag("chat_attachment_" + choice).assertIsDisplayed().performClick()
            compose.onNodeWithTag("chat_attachment_menu").assertDoesNotExist()
            compose.onNodeWithTag("chat_input").assertTextEquals("unsent draft")
        }
        assertEquals(listOf("photo", "camera", "file"), selected)
    }

    @Test
    fun dismissingPickerLeavesDraftAndCanReopen() {
        composer()
        compose.onNodeWithTag("chat_attach_button").performClick()
        compose.onNodeWithTag("chat_attachment_menu").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("chat_attachment_menu").assertDoesNotExist()
        compose.onNodeWithTag("chat_input").assertTextEquals("unsent draft")
        compose.onNodeWithTag("chat_attach_button").performClick()
        compose.onNodeWithTag("chat_attachment_photo").assertIsDisplayed()
    }

    @Test
    fun modelAndReasoningRemainDirectControls() {
        var models = 0
        var reasoning: String? = null
        composer(onModel = { models++ }, onReasoning = { reasoning = it })
        compose.onNodeWithTag("model_chip").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reasoning_chip").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reasoning_panel").assertIsDisplayed()
        listOf("Low", "Medium", "High", "Max").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        compose.onNodeWithTag("reasoning_level_ultra").assertTextEquals("Ultra").performClick()
        assertEquals(1, models)
        assertEquals("ultra", reasoning)
        compose.onNodeWithTag("chat_input").assertTextEquals("unsent draft")
    }

    @Test
    fun disconnectedAttachmentControlCannotLaunchPicker() {
        composer(connected = false)
        compose.onNodeWithTag("chat_attach_button").assertIsNotEnabled()
        compose.onNodeWithTag("chat_attachment_menu").assertDoesNotExist()
    }

    @Test
    fun compactToolKeepsErrorsVisibleAndRawOutputExpandable() {
        compose.setContent {
            HermesControlTheme {
                ToolBubble(
                    ChatMessage(
                        role = MessageRole.TOOL,
                        content = "failed command output",
                        toolStatus = ToolStatus.FAILED,
                    ),
                    compact = true,
                )
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("chat_tool_error", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_tool_toggle").performClick()
        compose.onNodeWithText("failed command output").assertIsDisplayed()
        compose.onNodeWithTag("chat_tool_toggle").performClick()
        compose.onNodeWithTag("chat_tool_error", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun repeatedConnectionRowRevealsOriginalHistory() {
        compose.setContent {
            HermesControlTheme {
                ConnectionEventRow(
                    listOf(
                        ChatMessage(id = "1", role = MessageRole.SYSTEM, content = "Connected to Hermes"),
                        ChatMessage(id = "2", role = MessageRole.SYSTEM, content = "Session resumed"),
                    ),
                    currentMatchId = null,
                )
            }
        }
        compose.onNodeWithTag("chat_connection_events").performClick()
        compose.onNodeWithText("Connected to Hermes", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Session resumed", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_connection_events").performClick()
        compose.onNodeWithText("Session resumed", substring = true).assertDoesNotExist()
    }
}
