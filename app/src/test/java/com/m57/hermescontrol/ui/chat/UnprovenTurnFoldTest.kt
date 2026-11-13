package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A locally sent turn (optimistic user bubble + streamed answer) later arrives from REST history.
 * Each case varies only what the gateway proved about row identity. The turn must be shown once;
 * anything that cannot be located safely keeps both copies.
 */
class UnprovenTurnFoldTest {
    private val target = "synthetic-target"
    private val session = "synthetic-session"
    private val attempt = "attempt-1"

    private fun row(
        id: Int,
        role: String,
        text: String,
    ) = SessionMessage(id = id, role = role, content = JsonPrimitive(text), timestamp = JsonPrimitive(1.0))

    private val page = listOf(row(10, "user", "original"), row(11, "assistant", "local answer"))

    private fun canonical(
        row: Long,
        role: MessageRole,
        text: String,
    ) = ChatMessage(id = canonicalMessageId(target, session, row), role = role, content = text, timestamp = 500L)

    private fun localTurn(
        ackRow: Long?,
        disposition: String = "STREAMING",
        terminal: TerminalEvidence?,
        finalAlias: Boolean = false,
        userText: String = "original",
        before: List<ChatMessage> = emptyList(),
        extra: List<ChatMessage> = emptyList(),
        active: Boolean = false,
        typing: Boolean = false,
        // Whether the submit ACK arrived. Without an ACK row the default is an unacknowledged
        // input (the result was lost): it stays a pending input with no row binding.
        acknowledged: Boolean = ackRow != null,
    ): ChatUiState {
        val record = ResponseRecord(id = "r", messageIds = listOf("local-answer") + extra.map { it.id })
        val sent =
            ResponseLedger(
                responses = listOf(record),
                activeId = if (active) "r" else null,
                pendingSubmissions = mapOf(attempt to "r"),
                pendingInputs = mapOf("local-user" to "r"),
            )
        var ledger =
            if (!acknowledged) {
                sent
            } else {
                sent.acceptInput(
                    attempt,
                    "r",
                    null,
                    true,
                    "rpc-1",
                    disposition,
                    target,
                    session,
                    1L,
                    "local-user",
                    ackRow,
                    null,
                )
            }
        if (terminal != null) {
            ledger =
                ledger.update("r") {
                    it.admitTerminal(terminal).copy(
                        canonicalRowIds = terminal.rowIds,
                        canonicalFinalAssistantRowId = terminal.finalRowId,
                        canonicalComplete = terminal.complete == true,
                        userRowIds = (it.userRowIds + listOfNotNull(terminal.userRowId)).distinct(),
                    )
                }
        }
        if (finalAlias) ledger = ledger.copy(aliases = ledger.aliases + ("11" to "local-answer"))
        return ChatUiState(
            messages =
                before +
                    listOf(
                        ChatMessage(id = "local-user", role = MessageRole.USER, content = userText, timestamp = 1000L),
                        ChatMessage(
                            id = "local-answer",
                            role = MessageRole.ASSISTANT,
                            content = "local answer",
                            timestamp = 1000L,
                        ),
                    ) + extra,
            responseLedger = ledger.revokeExclusiveClaims(),
            isAgentTyping = typing,
        )
    }

    private fun reconciled(
        state: ChatUiState,
        rows: List<SessionMessage> = page,
    ): ChatUiState {
        val result = reconcileTranscript(state, target, session, rows, 0, true)
        val again = reconcileTranscript(result, target, session, rows, 0, true)
        assertEquals("a second identical sync must not change the transcript", result.messages, again.messages)
        return again
    }

    private fun shown(
        state: ChatUiState,
        rows: List<SessionMessage> = page,
    ): String = reconciled(state, rows).messages.joinToString(" | ") { "${it.role.name.lowercase()}:${it.content}" }

    private val once = "user:original | assistant:local answer"

    private fun complete() =
        TerminalEvidence(2000L, 2L, listOf(10L, 11L), 10L, 11L, complete = true, status = "complete")

    private fun incomplete(
        final: Long? = null,
        status: String = "complete",
    ) = TerminalEvidence(2000L, 2L, listOf(10L, 11L), 10L, final, complete = false, status = status)

    private fun noReceipt() = TerminalEvidence(2000L, 2L, status = "complete")

    // ── Proven turns (unchanged behavior) ─────────────────────────────

    @Test fun ackRowAndCompleteReceipt() = assertEquals(once, shown(localTurn(10L, terminal = complete())))

    @Test fun completeReceiptWithoutAckRow() =
        assertEquals(once, shown(localTurn(null, terminal = complete(), acknowledged = true)))

    @Test fun incompleteReceiptWithFinalRow() =
        assertEquals(
            once,
            shown(localTurn(10L, terminal = incomplete(final = 11L))),
        )

    @Test fun incompleteReceiptWithBoundFinalRow() =
        assertEquals(once, shown(localTurn(10L, terminal = incomplete(final = 11L), finalAlias = true)))

    // ── Previously duplicated, folded because the user row is proven ──

    @Test fun incompleteReceiptWithoutAckRow() =
        assertEquals(once, shown(localTurn(null, terminal = incomplete(), acknowledged = true)))

    @Test fun noReceiptWithAckRow() = assertEquals(once, shown(localTurn(10L, terminal = noReceipt())))

    @Test fun queuedInputWithoutAckRow() =
        assertEquals(once, shown(localTurn(null, disposition = "QUEUED", terminal = complete(), acknowledged = true)))

    @Test fun interruptedTurnWithAckRow() =
        assertEquals(once, shown(localTurn(10L, terminal = incomplete(status = "interrupted"))))

    @Test fun interruptedTurnWithoutAckRow() =
        assertEquals(once, shown(localTurn(null, terminal = incomplete(status = "interrupted"), acknowledged = true)))

    @Test fun completionNeverObservedWithAckRow() = assertEquals(once, shown(localTurn(10L, terminal = null)))

    @Test fun alreadyDuplicatedCacheHeals() {
        val duplicated =
            localTurn(
                null,
                terminal = incomplete(),
                before =
                    listOf(
                        canonical(10L, MessageRole.USER, "original"),
                        canonical(11L, MessageRole.ASSISTANT, "local answer"),
                    ),
            )
        assertEquals(once, shown(duplicated))
    }

    @Test fun foldedTurnOwnsTheServerRows() {
        val state = reconciled(localTurn(null, terminal = incomplete()))
        val record = state.responseLedger.responses.single()
        assertEquals(listOf(canonicalMessageId(target, session, 10L)), record.userIds)
        assertEquals(listOf(canonicalMessageId(target, session, 11L)), record.messageIds)
        assertEquals(emptyMap<String, String>(), state.responseLedger.pendingInputs)
    }

    @Test fun provenRowIsNeverAnOlderSameTextTurn() {
        val history =
            listOf(
                row(5, "user", "original"),
                row(6, "assistant", "old answer"),
                row(10, "user", "original"),
                row(11, "assistant", "local answer"),
            )
        val state =
            localTurn(
                null,
                terminal = incomplete(),
                before =
                    listOf(
                        canonical(5L, MessageRole.USER, "original"),
                        canonical(6L, MessageRole.ASSISTANT, "old answer"),
                    ),
            )
        assertEquals(
            "user:original | assistant:old answer | user:original | assistant:local answer",
            shown(state, history),
        )
    }

    @Test fun anotherClientsLaterTurnStaysAfterOurs() {
        val rows = page + listOf(row(12, "user", "from desktop"), row(13, "assistant", "desktop answer"))
        assertEquals(
            "$once | user:from desktop | assistant:desktop answer",
            shown(localTurn(null, terminal = incomplete()), rows),
        )
    }

    @Test fun approvalCardOfTheTurnIsKept() {
        val card =
            ChatMessage(
                id = "approval-card",
                role = MessageRole.SYSTEM,
                content = "承認が必要です",
                approvalInfo = ApprovalInfo(command = "ls", description = null, patternKeys = null),
            )
        val result = shown(localTurn(null, terminal = incomplete(), extra = listOf(card)))
        assertEquals("$once | system:承認が必要です", result)
    }

    // ── Unproven, ambiguous, lossy or live: keep both copies ──────────

    @Test fun noReceiptAndNoAckRowKeepsTheAmbiguousObservation() {
        val result = reconciled(localTurn(null, terminal = noReceipt()))
        assertEquals(
            "user:original | assistant:local answer | user:original | assistant:local answer",
            result.messages.joinToString(" | ") { "${it.role.name.lowercase()}:${it.content}" },
        )
        assertEquals(true, "local-user" in result.responseLedger.pendingInputs)
    }

    @Test fun differentServerTextKeepsBothCopies() =
        assertEquals(
            "user:edited elsewhere | assistant:local answer | user:original | assistant:local answer",
            shown(localTurn(null, terminal = noReceipt(), userText = "edited elsewhere")),
        )

    @Test fun differentStoredReplyKeepsTheLocalReplyReadable() {
        val stored = listOf(row(10, "user", "original"), row(11, "assistant", "stored differently"))
        val expected = "user:original | assistant:local answer | assistant:stored differently"
        assertEquals(expected, shown(localTurn(null, terminal = incomplete(status = "interrupted")), stored))
        assertEquals(expected, shown(localTurn(10L, terminal = incomplete(status = "interrupted")), stored))
    }

    // ── Review counterexamples (8a438b0) ──────────────────────────────

    @Test
    fun reviewIndependentOlderSameTextTurnCannotConsumeUnacknowledgedLocalInput() {
        val independentHistory =
            page.map {
                it.copy(timestamp = JsonPrimitive(0.1))
            }
        val result =
            reconciled(
                localTurn(null, terminal = null),
                independentHistory,
            )
        assertEquals(
            true,
            result.messages.any { it.id == "local-user" },
        )
        assertEquals(
            2,
            result.messages.count {
                it.role == MessageRole.USER && it.content == "original"
            },
        )
        assertEquals(
            true,
            "local-user" in result.responseLedger.pendingInputs,
        )
    }

    @Test
    fun reviewPartialFinalFoldPreservesRetainedCommentaryBeforeFinal() {
        val commentary =
            ChatMessage(
                id = "local-commentary",
                role = MessageRole.ASSISTANT,
                content = "commentary",
                timestamp = 1000L,
            )
        val base = localTurn(10L, terminal = incomplete())
        val state =
            base.copy(
                messages = listOf(base.messages[0], commentary, base.messages[1]),
                responseLedger =
                    base.responseLedger.update("r") {
                        it.copy(messageIds = listOf(commentary.id, "local-answer"))
                    },
            )
        val result = reconciled(state)
        assertEquals(
            "user:original | assistant:commentary | assistant:local answer",
            result.messages.joinToString(" | ") { "${it.role.name.lowercase()}:${it.content}" },
        )
        assertEquals(
            true,
            commentary.id in
                result.responseLedger.responses
                    .single()
                    .messageIds,
        )
    }

    @Test
    fun reviewSameBodyWithoutReasoningCannotDropLocalReasoning() {
        val base = localTurn(10L, terminal = incomplete())
        val state =
            base.copy(
                messages =
                    base.messages.map {
                        if (it.id == "local-answer") it.copy(reasoningText = "local reasoning") else it
                    },
            )
        val result = reconciled(state)
        assertEquals(
            "local reasoning",
            result.messages.single { it.id == "local-answer" }.reasoningText,
        )
    }

    @Test fun runningTurnIsNotFolded() {
        val running = localTurn(10L, terminal = null, active = true, typing = true)
        assertEquals(
            "user:original | assistant:local answer | assistant:local answer",
            shown(running),
        )
    }

    @Test fun runningToolBubbleIsNotFolded() {
        val tool =
            ChatMessage(
                id = "local-tool",
                role = MessageRole.TOOL,
                content = "",
                toolName = "terminal",
                toolStatus = ToolStatus.RUNNING,
            )
        val state = localTurn(10L, terminal = noReceipt(), extra = listOf(tool))
        val result = reconciled(state).messages.map { it.id }
        assertEquals(true, "local-answer" in result && "local-tool" in result)
    }

    @Test fun knownRowOutsideThePageIsNeverGuessed() {
        val elsewhere = listOf(row(20, "user", "original"), row(21, "assistant", "local answer"))
        val result = shown(localTurn(10L, terminal = noReceipt()), elsewhere)
        assertEquals(
            "user:original | assistant:local answer | user:original | assistant:local answer",
            result,
        )
    }

    @Test fun olderPageEndingMidTurnIsNotFolded() {
        val state = localTurn(10L, terminal = noReceipt())
        val result = reconcileTranscript(state, target, session, page, 0, true, older = true)
        assertEquals(true, result.messages.any { it.id == "local-answer" })
    }
}
