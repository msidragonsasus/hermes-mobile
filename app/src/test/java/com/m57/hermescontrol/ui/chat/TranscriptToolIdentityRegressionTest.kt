package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptToolIdentityRegressionTest {
    private fun checkTransfer(
        localIds: List<String>,
        canonicalIds: List<String>,
        expectedTransfer: Boolean,
    ) {
        val user = ChatMessage(id = "user", role = MessageRole.USER, content = "original", timestamp = 1000L)
        val answer = ChatMessage(id = "answer", role = MessageRole.ASSISTANT, content = "final", timestamp = 1000L)
        val tools =
            localIds.mapIndexed { index, call ->
                ChatMessage(
                    id = "local-tool-$index",
                    role = MessageRole.TOOL,
                    content = "old local tool $index",
                    toolCallId = call,
                    toolName = "terminal-$index",
                    toolStatus = ToolStatus.COMPLETED,
                    timestamp = 1000L,
                )
            }
        val finalRow = 11 + canonicalIds.size
        val rows =
            listOf(SessionMessage(id = 10, role = "user", content = JsonPrimitive("original"))) +
                canonicalIds.mapIndexed { index, call ->
                    SessionMessage(
                        id = 11 + index,
                        role = "tool",
                        content = JsonPrimitive("canonical body $index"),
                        tool_call_id = call,
                    )
                } + SessionMessage(id = finalRow, role = "assistant", content = JsonPrimitive("final"))
        val response =
            ResponseRecord(
                id = "reply",
                messageIds = tools.map { it.id } + answer.id,
                userIds = listOf(user.id),
                userRowIds = listOf(10L),
                canonicalRowIds = (10..finalRow).map { it.toLong() },
                canonicalComplete = true,
                finishedAt = 2000L,
                authority =
                    acceptedAuthorityFixture(
                        10L,
                        finalRow.toLong(),
                        (10..finalRow).map { it.toLong() },
                        2000L,
                        tools.map { it.id } + answer.id,
                        complete = true,
                    ),
            )
        val result =
            reconcileTranscript(
                ChatUiState(
                    messages = listOf(user) + tools + answer,
                    responseLedger = ResponseLedger(responses = listOf(response)),
                ),
                "target",
                "session",
                rows,
                0,
                true,
            )
        val restored = result.messages.filter { it.role == MessageRole.TOOL }
        assertEquals(canonicalIds.size, restored.size)
        assertEquals(canonicalIds, restored.map { it.toolCallId })
        assertEquals(canonicalIds.indices.map { "canonical body $it" }, restored.map { it.content })
        assertEquals(
            canonicalIds.indices.map {
                canonicalMessageId("target", "session", (11 + it).toLong())
            },
            restored.map { it.id },
        )
        if (expectedTransfer) {
            assertEquals(listOf("terminal-0"), restored.map { it.toolName })
            assertEquals(listOf(ToolStatus.COMPLETED), restored.map { it.toolStatus })
        } else {
            assertTrue(
                "Ambiguous or nonmatching IDs must not lend a local name or status",
                restored.all {
                    it.toolName ==
                        null &&
                        it.toolStatus == null
                },
            )
        }
        assertEquals(
            2000L,
            result.responseLedger.responses
                .single()
                .finishedAt,
        )
        assertTrue(
            result.responseLedger.responses
                .single()
                .canonicalComplete,
        )
    }

    @Test fun duplicateCanonicalCallIdsCannotBothBorrowOneLocalToolsMetadata() =
        checkTransfer(listOf("call-exact"), listOf("call-exact", "call-exact"), false)

    @Test fun duplicateLocalCallIdsDoNotTransferMetadata() =
        checkTransfer(listOf("call-exact", "call-exact"), listOf("call-exact"), false)

    @Test fun differentCallIdsDoNotTransferMetadataDespiteMatchingToolRole() =
        checkTransfer(listOf("call-local"), listOf("call-canonical"), false)

    @Test fun blankCallIdsCannotTransferMetadata() = checkTransfer(listOf(""), listOf(""), false)

    @Test fun aUniqueExactCallIdOnBothSidesTransfersOnlyMetadata() =
        checkTransfer(listOf("call-exact"), listOf("call-exact"), true)
}
