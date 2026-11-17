package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatSearchState
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.ToolStatus
import com.m57.hermescontrol.ui.chat.components.ChatScrollController
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for the full-bleed chat renderer (issue #866).
 *
 * Validates the parallel renderer's hierarchy contract:
 * - user messages KEEP the bubble (universal anchor)
 * - assistant prose renders full-bleed (no bubble), one turn header per turn
 * - tool rows render as distinct compact rows, NOT full-bleed prose
 * - streaming + clarify still render
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class FullBleedChatListTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun msg(
        id: String,
        role: MessageRole,
        content: String = "content-$id",
        toolStatus: ToolStatus? = null,
        isStreaming: Boolean = false,
    ) = ChatMessage(id = id, role = role, content = content, toolStatus = toolStatus, isStreaming = isStreaming)

    private fun render(
        messages: List<ChatMessage>,
        streamingMessage: ChatMessage? = null,
        clarify: Boolean = false,
        thinking: Boolean = false,
    ) {
        composeTestRule.setContent {
            val listState = LazyListState()
            FullBleedChatList(
                messages = messages,
                streamingMessage = streamingMessage,
                searchState = ChatSearchState(),
                typingEffectEnabled = false,
                typingEffectDelayMs = 30,
                isLoading = false,
                isLoadingOlder = false,
                isDark = false,
                listState = listState,
                scrollController =
                    ChatScrollController(
                        listState = listState,
                        scope = CoroutineScope(Dispatchers.Main.immediate),
                    ),
                lastAnimatedMessageId = null,
                onLastAnimatedMessageIdChange = {},
                viewModel = mockk<ChatViewModel>(relaxed = true),
                showThinkingIndicator = thinking,
                clarifyRequest =
                    if (clarify) {
                        com.m57.hermescontrol.ui.chat.ClarifyUi(
                            text = "pick",
                            options = listOf("a"),
                        )
                    } else {
                        null
                    },
            )
        }
    }

    @Test
    fun thinkingIsTheExpandableActivityAndEmptyProseDoesNotRender() {
        render(
            messages = listOf(msg("u", MessageRole.USER)),
            streamingMessage =
                msg(
                    "r",
                    MessageRole.ASSISTANT,
                    content = "",
                    isStreaming = true,
                ).copy(reasoningText = "Trace"),
            thinking = true,
        )
        composeTestRule.onNodeWithTag("agent_turn_activity").assertIsDisplayed()
        composeTestRule.onNodeWithTag("thinking_indicator", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("fullbleed_agent_message").assertCountEquals(0)
        composeTestRule.onNodeWithTag("agent_turn_activity").performClick()
        composeTestRule.onNodeWithText("Trace").assertIsDisplayed()
    }

    @Test
    fun requestedModelSwitchRetainsRawCommandWithoutSuccessChip() {
        val command = "/model next --provider example --session"
        render(listOf(msg("switch-request", MessageRole.USER, command).copy(displayKind = "model_switch_request")))
        composeTestRule.onNodeWithText(command).assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullbleed_system_event").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("timeline_marker").assertCountEquals(0)
    }

    @Test
    fun userMessages_keepBubble_agentProse_isFullBleed() {
        render(
            listOf(
                msg("u1", MessageRole.USER),
                msg("a1", MessageRole.ASSISTANT),
            ),
        )
        composeTestRule.onNodeWithTag("chat_bubble_user").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullbleed_agent_message").assertIsDisplayed()
    }

    @Test
    fun agentProse_hasNoBubbleContainer() {
        render(listOf(msg("a1", MessageRole.ASSISTANT)))
        composeTestRule.onNodeWithTag("chat_bubble_assistant").assertDoesNotExist()
        composeTestRule.onNodeWithTag("fullbleed_agent_message").assertIsDisplayed()
    }

    @Test
    fun toolRows_renderCompact_notFullBleed() {
        render(
            listOf(
                msg("a1", MessageRole.ASSISTANT),
                msg("t1", MessageRole.TOOL, toolStatus = ToolStatus.COMPLETED),
            ),
        )
        composeTestRule.onNodeWithTag("fullbleed_tool_row").assertDoesNotExist()
        composeTestRule.onAllNodesWithTag("fullbleed_agent_message").assertCountEquals(1)
        composeTestRule.onNodeWithTag("agent_turn_activity").performClick()
        composeTestRule.onNodeWithTag("fullbleed_tool_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullbleed_agent_message").assertIsDisplayed()
        composeTestRule.onNodeWithTag("agent_turn_activity").performClick()
        composeTestRule.onNodeWithTag("fullbleed_tool_row").assertDoesNotExist()
    }

    @Test
    fun reasoning_isFoldedUntilActivityIsOpened() {
        render(
            listOf(
                msg("a1", MessageRole.ASSISTANT).copy(reasoningText = "Reasoning fixture"),
                msg("t1", MessageRole.TOOL, toolStatus = ToolStatus.COMPLETED),
            ),
        )
        composeTestRule.onNodeWithTag("reasoning_card").assertDoesNotExist()
        composeTestRule.onNodeWithTag("agent_turn_activity").performClick()
        composeTestRule.onNodeWithTag("reasoning_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullbleed_tool_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullbleed_agent_message").assertIsDisplayed()
    }

    @Test
    fun longToolHistory_doesNotPushAnswerOffScreenByDefault() {
        val tools = (1..100).map { msg("tool-$it", MessageRole.TOOL, toolStatus = ToolStatus.COMPLETED) }
        render(listOf(msg("u1", MessageRole.USER)) + tools + msg("answer", MessageRole.ASSISTANT))
        composeTestRule.onAllNodesWithTag("fullbleed_tool_row").assertCountEquals(0)
        composeTestRule.onNodeWithTag("fullbleed_agent_message").assertIsDisplayed()
        composeTestRule.onNodeWithTag("agent_turn_activity").assertIsDisplayed()
    }

    @Test
    fun turnHeader_renderedOncePerAgentTurn() {
        render(
            listOf(
                msg("a1", MessageRole.ASSISTANT),
                msg("t1", MessageRole.TOOL, toolStatus = ToolStatus.COMPLETED),
                msg("a2", MessageRole.ASSISTANT),
                msg("u2", MessageRole.USER),
                msg("a3", MessageRole.ASSISTANT),
            ),
        )
        // Two agent turns (a1..a2, a3) -> two turn headers (one per turn).
        composeTestRule.onAllNodesWithTag("fullbleed_agent_header").assertCountEquals(2)
    }

    @Test
    fun streaming_and_clarify_stillRender() {
        render(
            messages = listOf(msg("u1", MessageRole.USER)),
            streamingMessage = msg("s1", MessageRole.ASSISTANT, content = "streaming", isStreaming = true),
            clarify = true,
        )
        composeTestRule.onNodeWithTag("fullbleed_agent_message").assertIsDisplayed()
        composeTestRule.onNodeWithTag("clarify_bubble").assertIsDisplayed()
    }
}
