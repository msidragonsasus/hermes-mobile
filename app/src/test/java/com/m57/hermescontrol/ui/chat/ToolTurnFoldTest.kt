package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A live tool turn (reasoning, a tool call, then the answer) arriving from history without a
 * complete receipt. Live streaming puts the reasoning on the answer and the tool.complete payload
 * in the tool bubble, while history stores the reasoning on the tool-call row and the tool's real
 * result. Once the user row is proven, the turn must be shown once.
 */
class ToolTurnFoldTest {
    private val target = "synthetic-target"
    private val session = "synthetic-session"
    private val attempt = "attempt-1"

    private fun rows(
        finalReasoning: String? = null,
        toolCallReasoning: String = "plan",
    ) = listOf(
        SessionMessage(id = 10, role = "user", content = JsonPrimitive("ask"), timestamp = JsonPrimitive(1.0)),
        SessionMessage(
            id = 11,
            role = "assistant",
            content = JsonPrimitive(""),
            reasoning = JsonPrimitive(toolCallReasoning),
            tool_calls =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "call_x")
                            put("type", "function")
                            put(
                                "function",
                                buildJsonObject {
                                    put("name", "message_agent")
                                    put("arguments", "{}")
                                },
                            )
                        },
                    )
                },
            timestamp = JsonPrimitive(2.0),
        ),
        SessionMessage(
            id = 12,
            role = "tool",
            content = JsonPrimitive("{\"status\": \"failed\"}"),
            tool_call_id = "call_x",
            timestamp = JsonPrimitive(3.0),
        ),
        SessionMessage(
            id = 13,
            role = "assistant",
            content = JsonPrimitive("answer"),
            reasoning = finalReasoning?.let(::JsonPrimitive),
            display_content = JsonPrimitive("answer"),
            timestamp = JsonPrimitive(4.0),
        ),
    )

    private fun localTurn(
        acknowledged: Boolean = true,
        answerReasoning: String = "plan",
        tool: ChatMessage = liveTool(),
    ): ChatUiState {
        val record = ResponseRecord(id = "r", messageIds = listOf(tool.id, "local-answer"))
        val sent =
            ResponseLedger(
                responses = listOf(record),
                pendingSubmissions = mapOf(attempt to "r"),
                pendingInputs = mapOf("local-user" to "r"),
            )
        val ledger =
            if (!acknowledged) {
                sent
            } else {
                sent.acceptInput(
                    attempt,
                    "r",
                    null,
                    true,
                    "rpc-1",
                    "STREAMING",
                    target,
                    session,
                    1L,
                    "local-user",
                    10L,
                    null,
                )
            }.update("r") { it.admitTerminal(TerminalEvidence(2000L, 2L, status = "complete")) }
        return ChatUiState(
            messages =
                listOf(
                    ChatMessage(id = "local-user", role = MessageRole.USER, content = "ask", timestamp = 1000L),
                    tool,
                    ChatMessage(
                        id = "local-answer",
                        role = MessageRole.ASSISTANT,
                        content = "answer",
                        reasoningText = answerReasoning,
                        timestamp = 1000L,
                    ),
                ),
            responseLedger = ledger.revokeExclusiveClaims(),
        )
    }

    private fun liveTool(
        status: ToolStatus = ToolStatus.COMPLETED,
        risk: ToolOutputRiskData? = null,
    ) = ChatMessage(
        id = "local-tool",
        role = MessageRole.TOOL,
        content = "{\"name\":\"message_agent\",\"summary\":\"event payload\"}",
        toolName = "message_agent",
        toolStatus = status,
        toolCallId = "call_x",
        toolOutputRiskData = risk,
        timestamp = 1000L,
    )

    private fun reconciled(
        state: ChatUiState,
        history: List<SessionMessage> = rows(),
    ): ChatUiState {
        val result = reconcileTranscript(state, target, session, history, 0, true)
        val again = reconcileTranscript(result, target, session, history, 0, true)
        assertEquals("a second identical sync must not change the transcript", result.messages, again.messages)
        return again
    }

    private fun ChatUiState.shown() =
        messages.joinToString(" | ") {
            "${it.role.name.lowercase()}${if (it.id.startsWith(
                    "local-",
                ) && it.role != MessageRole.USER
            ) {
                "(local)"
            } else {
                ""
            }}"
        }

    @Test fun provenToolTurnIsShownOnce() {
        val result = reconciled(localTurn())
        assertEquals("user | tool | assistant", result.shown())
        assertEquals(
            listOf(canonicalMessageId(target, session, 12L), canonicalMessageId(target, session, 13L)),
            result.responseLedger.responses
                .single()
                .messageIds,
        )
    }

    @Test fun reasoningSplitAcrossToolCallAndFinalRowsIsKept() {
        val result = reconciled(localTurn(answerReasoning = "plan\n\nwrap up"), rows(finalReasoning = "wrap up"))
        assertEquals("user | tool | assistant", result.shown())
    }

    @Test fun reasoningTheHistoryNeverStoredKeepsTheLocalAnswer() {
        val result = reconciled(localTurn(answerReasoning = "something else"))
        assertEquals("user | assistant | tool | assistant(local)", result.shown())
        assertEquals("something else", result.messages.single { it.id == "local-answer" }.reasoningText)
    }

    @Test fun riskFlaggedToolBubbleIsNotFolded() {
        val risky = liveTool(risk = ToolOutputRiskData("high", listOf("secret"), redacted = true))
        val result = reconciled(localTurn(tool = risky))
        assertEquals(true, result.messages.any { it.id == "local-tool" })
    }

    @Test fun unacknowledgedTurnStillKeepsBothCopies() {
        val result = reconciled(localTurn(acknowledged = false))
        assertEquals(true, result.messages.any { it.id == "local-answer" })
        assertEquals(true, result.messages.any { it.id == "local-tool" })
    }
}
