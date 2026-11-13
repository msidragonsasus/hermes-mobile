package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptReconcilerTest {
    private val target = "synthetic-target"
    private val session = "synthetic-session"

    private fun completedLocalResponse(): ChatUiState =
        ChatUiState(
            messages =
                listOf(
                    ChatMessage(id = "local-user", role = MessageRole.USER, content = "original", timestamp = 1000L),
                    ChatMessage(
                        id = "local-answer",
                        role = MessageRole.ASSISTANT,
                        content = "local answer",
                        timestamp = 1000L,
                    ),
                ),
            responseLedger =
                ResponseLedger(
                    responses =
                        listOf(
                            ResponseRecord(
                                id = "known-response",
                                messageIds = listOf("local-answer"),
                                userIds = listOf("local-user"),
                                userRowIds = listOf(10L),
                                canonicalRowIds = listOf(10L, 11L),
                                canonicalComplete = true,
                                finishedAt = 2000L,
                                authority =
                                    acceptedAuthorityFixture(
                                        10L,
                                        11L,
                                        listOf(10L, 11L),
                                        2000L,
                                        listOf("local-answer"),
                                        complete = true,
                                    ),
                            ),
                        ),
                ),
        )

    private fun row(
        id: Int,
        role: String,
        text: String,
    ): SessionMessage =
        SessionMessage(id = id, role = role, content = JsonPrimitive(text), timestamp = JsonPrimitive(1.0))

    @Test
    fun newServerResponseAfterLocallyKnownReplyIsNotDiscardedOrGivenItsProof() {
        val page =
            listOf(
                row(10, "user", "original"),
                row(11, "assistant", "local answer"),
                row(20, "user", "desktop question"),
                row(21, "assistant", "desktop answer"),
            )
        val result = reconcileTranscript(completedLocalResponse(), target, session, page, 0, true)
        assertEquals(
            listOf("original", "local answer", "desktop question", "desktop answer"),
            result.messages.map {
                it.content
            },
        )
        val known = result.responseLedger.responses.single()
        assertEquals(2000L, known.finishedAt)
        assertTrue(
            result.messages.filter { it.content.startsWith("desktop") }.none {
                it.id in known.messageIds ||
                    it.id in known.userIds
            },
        )
        val repeated = reconcileTranscript(result, target, session, page, 0, true)
        assertEquals(result.messages, repeated.messages)
    }

    @Test
    fun canonicalReplacementRetainsOnlyProvenToolMetadataAndNeverHidesTheNewBody() {
        val original = completedLocalResponse()
        val tool =
            ChatMessage(
                id = "local-tool",
                role = MessageRole.TOOL,
                content = "old live body",
                toolName = "terminal",
                toolCallId = "call-exact",
                toolStatus = ToolStatus.COMPLETED,
                timestamp = 1000L,
            )
        val response =
            original.responseLedger.responses.single().copy(
                messageIds = listOf(tool.id, "local-answer"),
                canonicalRowIds = listOf(10L, 11L, 12L),
                authority =
                    acceptedAuthorityFixture(
                        10L,
                        12L,
                        listOf(10L, 11L, 12L),
                        2000L,
                        listOf(tool.id, "local-answer"),
                        complete = true,
                    ),
            )
        val state =
            original.copy(
                messages = listOf(original.messages.first(), tool, original.messages.last()),
                responseLedger = original.responseLedger.copy(responses = listOf(response)),
            )
        val page =
            listOf(
                row(10, "user", "original"),
                row(11, "tool", "new canonical body").copy(tool_call_id = "call-exact"),
                row(12, "assistant", "local answer"),
            )
        val result = reconcileTranscript(state, target, session, page, 0, true)
        val restored = result.messages.single { it.role == MessageRole.TOOL }
        assertEquals("terminal", restored.toolName)
        assertEquals(ToolStatus.COMPLETED, restored.toolStatus)
        assertEquals("new canonical body", restored.content)
    }

    @Test
    fun newestPageWithoutAnOldReplyAnchorStillRetainsNewServerText() {
        val page = listOf(row(20, "user", "desktop question"), row(21, "assistant", "desktop answer"))
        val result = reconcileTranscript(completedLocalResponse(), target, session, page, 0, true)
        assertEquals(
            listOf("original", "local answer", "desktop question", "desktop answer"),
            result.messages.map {
                it.content
            },
        )
    }
}
