package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.ui.chat.fullbleed.planMainChatTimeline
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
    @Test fun storedStopCompletesInterruptedReasoningOnlyTurn() {
        val cached = localTurn().let { state ->
            state.copy(
                messages = state.messages.map {
                    if (it.id == "local-answer") it.copy(content = "", finishTimestamp = 2500L) else it
                },
                responseLedger = state.responseLedger.copy(activeId = "r").update("r") {
                    it.copy(authority = it.authority.copy(terminals = emptyList(), deliveryGap = true), finishedAt = null)
                },
            )
        }
        val history = rows().let {
            val previous = listOf(
                SessionMessage(id = 7, role = "user", content = JsonPrimitive("previous question"), timestamp = JsonPrimitive(0.1)),
                SessionMessage(
                    id = 8,
                    role = "assistant",
                    content = JsonPrimitive("previous reply"),
                    finish_reason = "stop",
                    timestamp = JsonPrimitive(0.2),
                ),
            )
            previous + it.dropLast(1) + listOf(
                SessionMessage(id = 14, role = "assistant", content = JsonPrimitive(""), finish_reason = "tool_calls"),
                SessionMessage(
                    id = 15,
                    role = "tool",
                    content = JsonPrimitive("second result"),
                    timestamp = JsonPrimitive(3.5),
                ),
                it.last().copy(id = 16, display_content = null, finish_reason = "stop"),
                SessionMessage(
                    id = 17,
                    role = "user",
                    content = JsonPrimitive("scheduled trigger"),
                    display_kind = "internal_notification",
                    timestamp = JsonPrimitive(5.0),
                ),
                SessionMessage(
                    id = 18,
                    role = "assistant",
                    content = JsonPrimitive("scheduled reply"),
                    finish_reason = "stop",
                    timestamp = JsonPrimitive(6.0),
                ),
            )
        }
        val result = reconciled(cached, history)
        val answer = result.messages.single { it.id == canonicalMessageId(target, session, 16L) }
        val morningId = canonicalMessageId(target, session, 18L)
        assertEquals(
            listOf(canonicalMessageId(target, session, 8L), answer.id, morningId),
            result.messages.filter { it.role == MessageRole.ASSISTANT }.map { it.id },
        )
        assertEquals(canonicalMessageId(target, session, 16L), answer.id)
        assertEquals("answer", answer.content)
        assertEquals("plan", answer.reasoningText)
        assertEquals(morningId, result.messages.last().id)
        assertEquals(4000L, result.responseLedger.responses.single().finishedAt)
        val timeline = planMainChatTimeline(result.messages, null, result.responseLedger, waitingForFirstText = false)
        assertEquals(morningId, timeline.first().message?.id)
    }

    @Test fun unmatchedCachedBubbleStaysBeforeNewerServerReply() {
        val history = rows()
        val hydrated = reconciled(ChatUiState(), history)
        val cached = ChatMessage(
            id = "local-unmatched",
            role = MessageRole.ASSISTANT,
            content = "",
            reasoningText = "local-only trace",
            timestamp = 3500L,
        )
        val result = reconciled(hydrated.copy(messages = hydrated.messages + cached), history)
        assertEquals(listOf(cached.id, canonicalMessageId(target, session, 13L)), result.messages.takeLast(2).map { it.id })
    }

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
        assertEquals("user | assistant(local) | assistant | tool", result.shown())
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
