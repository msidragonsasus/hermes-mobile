package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Context compression re-inserts the kept transcript under new row ids (same message_uid,
 * content and timestamp) and deactivates the old rows. Every message must still show once.
 */
class RewrittenRowsTest {
    private val target = "synthetic-target"
    private val session = "synthetic-session"

    private fun row(
        id: Int,
        role: String,
        text: String,
        uid: String? = null,
        at: Double = id.toDouble(),
    ) = SessionMessage(
        id = id,
        role = role,
        content = JsonPrimitive(text),
        timestamp = JsonPrimitive(at),
        message_uid = uid,
    )

    private fun ChatUiState.count(text: String) = messages.count { it.content == text }

    @Test
    fun rewrittenRowWithKnownUidReplacesItsOldCopy() {
        val first =
            reconcileTranscript(
                ChatUiState(),
                target,
                session,
                listOf(row(3, "user", "q", "u3"), row(5, "assistant", "a", "u5")),
                0,
                true,
            )
        assertEquals(1, first.count("a"))

        val rewritten =
            listOf(row(3, "user", "q", "u3"), row(8, "assistant", "a", "u5", at = 5.0), row(9, "user", "next", "u9"))
        val next = reconcileTranscript(first, target, session, rewritten, 0, true)

        assertEquals(1, next.count("a"))
        assertEquals(listOf("q", "a", "next"), next.messages.map { it.content })
    }

    @Test
    fun cachedRowMissingInsideTheNewestPageIsDroppedEvenWithoutAKnownUid() {
        val legacy =
            ChatUiState(
                messages =
                    listOf(
                        ChatMessage(
                            id = canonicalMessageId(target, session, 3),
                            role = MessageRole.USER,
                            content = "q",
                        ),
                        ChatMessage(
                            id = canonicalMessageId(target, session, 5),
                            role = MessageRole.ASSISTANT,
                            content = "a",
                        ),
                    ),
            )
        // The copy of row 5 is re-inserted as row 12 after newer rows, keeping its old time.
        val rewritten =
            listOf(
                row(3, "user", "q", at = 300.0),
                row(9, "user", "next", at = 900.0),
                row(12, "assistant", "a", at = 500.0),
            )

        val next = reconcileTranscript(legacy, target, session, rewritten, 0, true)

        assertEquals(1, next.count("a"))
    }

    @Test
    fun olderPageNeverDropsRowsItDoesNotCover() {
        val cached =
            ChatUiState(
                messages =
                    listOf(
                        ChatMessage(
                            id = canonicalMessageId(target, session, 5),
                            role = MessageRole.ASSISTANT,
                            content = "a",
                        ),
                    ),
            )
        val page = listOf(row(3, "user", "q"), row(9, "user", "next"))

        val next = reconcileTranscript(cached, target, session, page, 40, true, older = true)

        assertEquals(1, next.count("a"))
    }

    @Test
    fun turnSubmittedBeforeAndCompletedAfterARewriteShowsOnce() {
        val attempt = "attempt-1"
        val record = ResponseRecord(id = "r", messageIds = listOf("local-answer"))
        val ledger =
            ResponseLedger(
                responses = listOf(record),
                pendingSubmissions = mapOf(attempt to "r"),
                pendingInputs = mapOf("local-user" to "r"),
            ).acceptInput(attempt, "r", null, true, "rpc-1", "STREAMING", target, session, 1L, "local-user", 10L, null)
                .update("r") {
                    it.admitTerminal(
                        TerminalEvidence(
                            observedAt = 2000L,
                            observation = 2L,
                            rowIds = listOf(20, 21),
                            userRowId = 20,
                            finalRowId = 21,
                            complete = true,
                            status = "complete",
                        ),
                    )
                }.copy(activeId = null)
        val state =
            ChatUiState(
                messages =
                    listOf(
                        ChatMessage(
                            id = canonicalMessageId(target, session, 3),
                            role = MessageRole.USER,
                            content = "older",
                        ),
                        ChatMessage(id = "local-user", role = MessageRole.USER, content = "ask", timestamp = 1000L),
                        ChatMessage(
                            id = "local-answer",
                            role = MessageRole.ASSISTANT,
                            content = "answer",
                            timestamp = 1000L,
                        ),
                    ),
                responseLedger = ledger,
            )
        // Row 10 (the acknowledged user row) was rewritten as row 20 during the turn.
        val page = listOf(row(3, "user", "older"), row(20, "user", "ask"), row(21, "assistant", "answer"))

        val next = reconcileTranscript(state, target, session, page, 0, true)

        assertEquals(listOf("older", "ask", "answer"), next.messages.map { it.content })
    }

    @Test
    fun finishedTurnWhoseUserRowWasRewrittenKeepsNoLocalCopyEvenWithoutAReceipt() {
        val attempt = "attempt-1"
        val record = ResponseRecord(id = "r", messageIds = listOf("local-answer"))
        val ledger =
            ResponseLedger(
                responses = listOf(record),
                pendingSubmissions = mapOf(attempt to "r"),
                pendingInputs = mapOf("local-user" to "r"),
            ).acceptInput(attempt, "r", null, true, "rpc-1", "STREAMING", target, session, 1L, "local-user", 10L, null)
                .update("r") {
                    // The receipt was transformed by the compression, so it proves nothing.
                    it.admitTerminal(
                        TerminalEvidence(2000L, 2L, listOf(20, 21), 20, 21, complete = true, transformed = true),
                    )
                }.copy(activeId = null)
        val state =
            ChatUiState(
                messages =
                    listOf(
                        ChatMessage(
                            id = canonicalMessageId(target, session, 3),
                            role = MessageRole.USER,
                            content = "older",
                        ),
                        ChatMessage(id = "local-user", role = MessageRole.USER, content = "ask", timestamp = 1000L),
                        ChatMessage(
                            id = "local-answer",
                            role = MessageRole.ASSISTANT,
                            content = "answer",
                            timestamp = 1000L,
                        ),
                    ),
                responseLedger = ledger,
            )
        val page =
            listOf(
                row(3, "user", "older", at = 900.0),
                row(20, "user", "ask", at = 100.0),
                row(21, "assistant", "answer", at = 101.0),
            )

        val next = reconcileTranscript(state, target, session, page, 0, true)

        assertEquals(listOf("older", "ask", "answer"), next.messages.map { it.content })
    }

    @Test
    fun runningTurnKeepsItsLocalBubblesWhileItsRowsAreMissing() {
        val record =
            ResponseRecord(
                id = "r",
                messageIds = listOf("local-answer"),
                userIds = listOf("local-user"),
                userRowIds = listOf(10),
            )
        val state =
            ChatUiState(
                messages =
                    listOf(
                        ChatMessage(
                            id = canonicalMessageId(target, session, 3),
                            role = MessageRole.USER,
                            content = "older",
                        ),
                        ChatMessage(id = "local-user", role = MessageRole.USER, content = "ask"),
                        ChatMessage(
                            id = "local-answer",
                            role = MessageRole.ASSISTANT,
                            content = "partial",
                            isStreaming = true,
                        ),
                    ),
                responseLedger = ResponseLedger(responses = listOf(record), activeId = "r"),
                isAgentTyping = true,
            )
        val page = listOf(row(3, "user", "older"), row(20, "user", "other"))

        val next = reconcileTranscript(state, target, session, page, 0, true)

        assertEquals(1, next.count("partial"))
    }

    @Test
    fun turnWhoseCompletionNeverArrivedDropsItsLocalCopyOnceItsUserRowIsGone() {
        val record =
            ResponseRecord(id = "r", messageIds = listOf("local-tool", "local-answer"), userRowIds = listOf(10))
        val state =
            ChatUiState(
                messages =
                    listOf(
                        ChatMessage(
                            id = canonicalMessageId(target, session, 3),
                            role = MessageRole.USER,
                            content = "older",
                        ),
                        ChatMessage(
                            id = "local-tool",
                            role = MessageRole.TOOL,
                            content = "{}",
                            toolStatus = ToolStatus.COMPLETED,
                        ),
                        ChatMessage(id = "local-answer", role = MessageRole.ASSISTANT, content = "answer"),
                    ),
                responseLedger = ResponseLedger(responses = listOf(record), activeId = "r"),
            )
        val page =
            listOf(
                row(3, "user", "older", at = 900.0),
                row(20, "user", "ask", at = 100.0),
                row(21, "assistant", "answer", at = 101.0),
            )

        val next = reconcileTranscript(state, target, session, page, 0, true)

        assertEquals(listOf("older", "ask", "answer"), next.messages.map { it.content })
    }

    @Test
    fun ownerlessCutOffStreamIsAbsorbedByTheServerReplyButLocalNoticesStay() {
        val state =
            ChatUiState(
                messages =
                    listOf(
                        ChatMessage(
                            id = canonicalMessageId(target, session, 3),
                            role = MessageRole.USER,
                            content = "q",
                        ),
                        ChatMessage(
                            id = "0b6c2f0e-1d2a-4c55-9a77-3e5f6a7b8c9d",
                            role = MessageRole.ASSISTANT,
                            content = "the full ans",
                            timestamp = 4000L,
                        ),
                        ChatMessage(
                            id = "local-notice",
                            role = MessageRole.ASSISTANT,
                            content = "Usage: 12%",
                            timestamp = 4000L,
                        ),
                    ),
            )
        val page = listOf(row(3, "user", "q", at = 3.0), row(4, "assistant", "the full answer", at = 4.0))

        val next = reconcileTranscript(state, target, session, page, 0, true)

        assertEquals(listOf("q", "the full answer", "Usage: 12%"), next.messages.map { it.content })
    }

    @Test
    fun missingRowsWithoutAnyRewriteTraceAreNeverTreatedAsDeactivated() {
        val record =
            ResponseRecord(id = "r", messageIds = listOf("local-answer"), userRowIds = listOf(5), finishedAt = 1L)
        val state =
            ChatUiState(
                messages =
                    listOf(
                        ChatMessage(
                            id = canonicalMessageId(target, session, 3),
                            role = MessageRole.USER,
                            content = "q",
                        ),
                        ChatMessage(id = "local-answer", role = MessageRole.ASSISTANT, content = "kept"),
                    ),
                responseLedger = ResponseLedger(responses = listOf(record)),
            )
        val page = listOf(row(3, "user", "q"), row(9, "user", "next"))

        val next = reconcileTranscript(state, target, session, page, 0, true)

        assertEquals(1, next.count("kept"))
    }
}
