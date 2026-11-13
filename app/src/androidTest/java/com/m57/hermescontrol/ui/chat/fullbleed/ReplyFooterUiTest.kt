package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatSearchState
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.ToolStatus
import com.m57.hermescontrol.ui.chat.components.ChatScrollController
import com.m57.hermescontrol.ui.chat.formatTimestamp
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** GPT-authored regression sources. Requires instrumentation; JVM tests do not execute these. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ReplyFooterUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val clipboard = mockk<Clipboard>(relaxed = true)
    private val viewModel = mockk<ChatViewModel>(relaxed = true)
    private val copied = AtomicReference<String?>(null)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun answer(
        id: String,
        text: String = "Answer $id",
    ) = ChatMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        content = text,
        timestamp = 1_700_000_000_000L,
        finishTimestamp = 1_700_000_060_000L,
    )

    private fun render(
        messages: List<ChatMessage>,
        lifecycle: MutableState<ReplyLifecycle> = mutableStateOf(ReplyLifecycle()),
        streaming: ChatMessage? = null,
        typingEffect: Boolean = false,
        onSave: (Attachment) -> Unit = {},
    ) {
        coEvery { clipboard.setClipEntry(any()) } answers {
            copied.set(
                firstArg<androidx.compose.ui.platform.ClipEntry?>()
                    ?.clipData
                    ?.getItemAt(0)
                    ?.text
                    ?.toString(),
            )
        }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    val listState = rememberLazyListState()
                    val scope = rememberCoroutineScope()
                    FullBleedChatList(
                        messages = messages,
                        streamingMessage = streaming,
                        searchState = ChatSearchState(),
                        typingEffectEnabled = typingEffect,
                        typingEffectDelayMs = 1,
                        isLoading = false,
                        isLoadingOlder = false,
                        isDark = false,
                        listState = listState,
                        scrollController = ChatScrollController(listState = listState, scope = scope),
                        lastAnimatedMessageId = null,
                        onLastAnimatedMessageIdChange = {},
                        viewModel = viewModel,
                        onSaveAttachment = onSave,
                        replyLifecycle = lifecycle.value,
                    )
                }
            }
        }
    }

    private fun assertNoSkip() {
        compose.onNodeWithText("Skip", ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("スキップ").assertDoesNotExist()
        compose.onNodeWithContentDescription("Skip", ignoreCase = true).assertDoesNotExist()
    }

    private fun assertHeaderHasNoTime(message: ChatMessage) {
        for (use24Hour in listOf(false, true)) {
            compose
                .onAllNodes(
                    hasAnyAncestor(hasTestTag("fullbleed_agent_header")) and
                        hasText(formatTimestamp(message.timestamp, use24Hour), substring = true),
                    useUnmergedTree = true,
                ).assertCountEquals(0)
        }
    }

    @Test
    fun ongoingMultisegmentReplyHasNoBlockFootersOrHeaderTimes_thenOneWholeReplyClipboardAction() {
        val first = answer("first", "First **answer**")
        val last = answer("last", "Last answer")
        val tool =
            ChatMessage(
                id = "tool",
                role = MessageRole.TOOL,
                content = "raw tool output",
                toolStatus = ToolStatus.COMPLETED,
            )
        val lifecycle = mutableStateOf(ReplyLifecycle(activeMessageIds = setOf(first.id, last.id)))
        render(listOf(first, tool, last), lifecycle)
        compose.onAllNodesWithTag("fullbleed_agent_message").assertCountEquals(2)
        compose.onAllNodesWithTag("fullbleed_copy").assertCountEquals(0)
        compose.onAllNodesWithTag("fullbleed_finish_time").assertCountEquals(0)
        assertHeaderHasNoTime(first)
        assertNoSkip()
        // Expanding the activity must not revive block-level metadata.
        compose.onNodeWithTag("agent_turn_activity").performClick()
        compose.onNodeWithTag("fullbleed_tool_row").assertIsDisplayed()
        compose.onAllNodesWithTag("fullbleed_copy").assertCountEquals(0)
        compose.onAllNodesWithTag("fullbleed_finish_time").assertCountEquals(0)
        compose.runOnIdle {
            lifecycle.value =
                ReplyLifecycle(
                    ledger =
                        com.m57.hermescontrol.ui.chat.ResponseLedger(
                            listOf(
                                com.m57.hermescontrol.ui.chat.ResponseRecord(
                                    id = "fixture-response",
                                    messageIds = listOf("first", "tool", "last"),
                                    userIds = listOf(),
                                    finishedAt = 1_700_000_060_000L,
                                ),
                            ),
                        ),
                )
        }
        compose.onAllNodesWithTag("fullbleed_copy").assertCountEquals(1)
        compose.onAllNodesWithTag("fullbleed_finish_time").assertCountEquals(1)
        compose
            .onAllNodes(
                hasTestTag("fullbleed_copy") and hasAnyAncestor(hasTestTag("fullbleed_agent_message")),
                useUnmergedTree = true,
            ).assertCountEquals(0)
        assertHeaderHasNoTime(first)
        compose.onNodeWithTag("fullbleed_copy").performClick()
        compose.waitUntil { copied.get() != null }
        assertEquals("First **answer**\n\nLast answer", copied.get())
        assertNoSkip()
    }

    @Test
    fun typingEffectWrapperDoesNotReviveMessageFooter() {
        val first = answer("first")
        val streaming = answer("stream", "Writing").copy(isStreaming = true)
        render(listOf(first), streaming = streaming, typingEffect = true)
        compose.onAllNodesWithTag("fullbleed_agent_message").assertCountEquals(2)
        compose.onAllNodesWithTag("fullbleed_copy").assertCountEquals(0)
        compose.onAllNodesWithTag("fullbleed_finish_time").assertCountEquals(0)
        assertHeaderHasNoTime(first)
        assertNoSkip()
    }

    @Test
    fun codeCopyAndAttachmentOpenSaveRemainAvailableWhileReplyIsUnfinished() {
        val attachment =
            Attachment(
                uri = "content://fixture/file",
                name = "fixture.txt",
                mimeType = "text/plain",
                gatewayUrl = "https://fixture.invalid/file",
            )
        val message = answer("code", "Intro\n\n```text\nhello\n```").copy(attachments = listOf(attachment))
        val saved = AtomicReference<Attachment?>(null)
        render(listOf(message), onSave = { saved.set(it) })
        compose.onAllNodesWithTag("fullbleed_copy").assertCountEquals(0)
        compose.onAllNodesWithTag("fullbleed_finish_time").assertCountEquals(0)
        compose.onNodeWithTag("code_block").assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy code").performClick()
        compose.onNodeWithContentDescription("Copied").assertIsDisplayed()
        compose.onNodeWithText(attachment.name).performClick()
        verify(exactly = 1) { viewModel.openAttachment(attachment) }
        compose
            .onNodeWithContentDescription(
                context.getString(R.string.chat_save_attachment, attachment.name),
            ).performClick()
        assertEquals(attachment, saved.get())
        assertNoSkip()
    }

    @Test
    fun completedAttachmentOnlyReplyHasActionsButNoEmptyWholeAnswerCopy() {
        val attachment =
            Attachment(
                uri = "content://fixture/file",
                name = "only-file.txt",
                mimeType = "text/plain",
                gatewayUrl = "https://fixture.invalid/file",
            )
        val saved = AtomicReference<Attachment?>(null)
        render(
            listOf(answer("file", "").copy(attachments = listOf(attachment))),
            lifecycle = mutableStateOf(ReplyLifecycle(responseEnded = true)),
            onSave = { saved.set(it) },
        )
        compose.onAllNodesWithTag("fullbleed_copy").assertCountEquals(0)
        compose.onAllNodesWithTag("fullbleed_finish_time").assertCountEquals(0)
        compose.onNodeWithText(attachment.name).performClick()
        verify(exactly = 1) { viewModel.openAttachment(attachment) }
        compose
            .onNodeWithContentDescription(
                context.getString(R.string.chat_save_attachment, attachment.name),
            ).performClick()
        assertEquals(attachment, saved.get())
    }

    @Test
    fun acceptedSteerRendersExistingUserBubbleAndNeverEntersWholeReplyCopy() {
        val steer =
            ChatMessage(id = "steer", role = MessageRole.USER, content = "Also inspect X", displayKind = "steer")
        val lifecycle = mutableStateOf(ReplyLifecycle())
        render(listOf(answer("a1", "A1"), steer, answer("a2", "A2")), lifecycle)
        compose.onNodeWithTag("chat_bubble_user").assertIsDisplayed()
        compose.onNodeWithText("Also inspect X").assertIsDisplayed()
        compose.onAllNodesWithTag("fullbleed_copy").assertCountEquals(0)
        compose.runOnIdle {
            lifecycle.value =
                ReplyLifecycle(
                    ledger =
                        com.m57.hermescontrol.ui.chat.ResponseLedger(
                            listOf(
                                com.m57.hermescontrol.ui.chat.ResponseRecord(
                                    id = "fixture-response",
                                    messageIds = listOf("a1", "a2"),
                                    userIds = listOf("steer"),
                                    finishedAt = 1_700_000_060_000L,
                                ),
                            ),
                        ),
                )
        }
        compose.onAllNodesWithTag("fullbleed_copy").assertCountEquals(1)
        compose.onAllNodesWithTag("fullbleed_finish_time").assertCountEquals(1)
        compose.onNodeWithTag("fullbleed_copy").performClick()
        compose.waitUntil { copied.get() != null }
        assertEquals("A1\n\nA2", copied.get())
        assertNoSkip()
    }

    @Test
    fun standaloneCallerDefaultsRetainItsExistingCopyAndTimestamp() {
        compose.setContent {
            MaterialTheme {
                FullBleedAgentMessage(message = answer("standalone"), showTurnHeader = true, isDarkTheme = false)
            }
        }
        compose.onAllNodesWithTag("fullbleed_copy").assertCountEquals(1)
        compose.onAllNodesWithTag("fullbleed_finish_time").assertCountEquals(1)
    }
}
