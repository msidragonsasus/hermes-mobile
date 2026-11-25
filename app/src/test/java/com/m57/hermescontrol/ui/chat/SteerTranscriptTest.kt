package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SteerTranscriptTest {
    private val target = "target"
    private val session = "session"
    private val json = Json { ignoreUnknownKeys = true }
    private val root = row("""{"id":10,"role":"user","content":"start"}""")
    private val final = row("""{"id":15,"role":"assistant","content":"first answer","finish_reason":"stop"}""")
    private val typed =
        row(
            """{"id":14,"role":"user","content":"[OUT-OF-BAND USER MESSAGE]\nmore\nmore\n[/OUT-OF-BAND USER MESSAGE]",
                "display_content":"more\nmore","display_kind":"steer","message_uid":"steer-uid"}""",
        )
    private val drained = row("""{"id":16,"role":"user","content":"more\nmore","message_uid":"drain-uid"}""")
    private val answer = row("""{"id":17,"role":"assistant","content":"follow-up answer","finish_reason":"stop"}""")
    private val bubbles = listOf("more-1", "more-2")

    @Test
    fun joinedTypedRowUsesTwoDistinctStableBubbles() {
        val initial = localState(withFinal = true)
        val once = reconcile(initial, root, typed, final)
        assertEquals(listOf("start", "more", "more", "first answer"), once.messages.map { it.content })
        assertEquals(bubbles, once.messages.filter { it.displayKind == "steer" }.map { it.id })
        assertEquals(once.messages, reconcile(once, root, typed, final).messages)
    }

    @Test
    fun leftoversUseOneNextTurnAndKeepBothBubblesAfterThePredecessorFinal() {
        val initial = localState(withFinal = true)
        val once = reconcile(initial, root, final, drained, answer)
        assertEquals(
            listOf("start", "first answer", "more", "more", "follow-up answer"),
            once.messages.map { it.content },
        )
        assertEquals(bubbles, once.messages.filter { it.content == "more" }.map { it.id })
        assertEquals(null, once.messages.single { it.id == bubbles.first() }.displayKind)
        assertEquals(once.messages, reconcile(once, root, final, drained, answer).messages)
    }

    @Test
    fun missingStoredRowPreservesTheAcceptedInputs() {
        val once = reconcile(localState(withFinal = true), root, final)
        assertEquals(bubbles, once.messages.filter { it.content == "more" }.map { it.id })
        assertEquals(bubbles.toSet(), once.responseLedger.unresolvedSteerIds())
    }

    @Test
    fun historyBeforeAckAndDistinctTypedRowsKeepBothOccurrences() {
        val initial = localState(withFinal = true)
        val owner = initial.responseLedger.responses.single()
        val pending = initial.beforeAcknowledgement()
        val first = typed.copy(id = 13, display_content = JsonPrimitive("more"), message_uid = "first")
        val second = typed.copy(display_content = JsonPrimitive("more"), message_uid = "second")

        val beforeAck = reconcile(pending, root, first, second, final)
        assertEquals(bubbles, beforeAck.messages.filter { it.content == "more" }.map { it.id })
        val afterAck =
            beforeAck.copy(
                responseLedger =
                    beforeAck.responseLedger
                        .update(owner.id) { it.copy(authority = owner.authority) }
                        .copy(
                            pendingInputs = emptyMap(),
                            pendingSubmissions = emptyMap(),
                            possibleContinuations = emptyMap(),
                        ),
            )
        assertEquals(beforeAck.messages, reconcile(afterAck, root, first, second, final).messages)
    }

    @Test
    fun observedSuccessorReconcilesLeftoversEvenIfTheAckIsLate() {
        val pending = localState(withFinal = true).beforeAcknowledgement()
        val ledger = pending.responseLedger
        val successor =
            ResponseRecord(
                id = "successor",
                steerPredecessorId = ledger.activeId,
                authority =
                    ReplyAuthorityFacts(
                        terminals =
                            listOf(
                                TerminalEvidence(1, 1, userRowId = 16, finalRowId = 17, userRowIds = listOf(16)),
                            ),
                    ),
            )
        val state =
            reconcile(
                pending.copy(responseLedger = ledger.copy(responses = ledger.responses + successor)),
                root,
                final,
                drained,
                answer,
            )
        assertEquals(
            listOf("start", "first answer", "more", "more", "follow-up answer"),
            state.messages.map { it.content },
        )
        assertEquals(bubbles, state.messages.filter { it.content == "more" }.map { it.id })
    }

    @Test
    fun compressionQueueMergeUsesBlankLineAndKeepsLaterIdenticalRemotePrompt() {
        val compressed = drained.copy(content = JsonPrimitive("more\n\nmore"))
        val later = row("""{"id":18,"role":"user","content":"more"}""")
        val state = reconcile(localState(withFinal = true), root, final, compressed, answer, later)

        assertEquals(
            listOf("start", "first answer", "more", "more", "follow-up answer", "more"),
            state.messages.map { it.content },
        )
        assertEquals(bubbles, state.messages.filter { it.id in bubbles }.map { it.id })
    }

    @Test
    fun compressionDemotionBeforeLeftoverStillShowsEachSendInOrder() {
        val initial = localState(withFinal = true)
        val both =
            initial.copy(
                messages = initial.messages.map { if (it.id == bubbles.last()) it.copy(content = "newer") else it },
            )
        val merged = drained.copy(content = JsonPrimitive("newer\n\nmore"))
        val state = reconcile(both, root, final, merged, answer)
        assertEquals(
            listOf("start", "first answer", "more", "newer", "follow-up answer"),
            state.messages.map { it.content },
        )
        assertEquals(bubbles, state.messages.filter { it.id in bubbles }.map { it.id })
    }

    private fun localState(withFinal: Boolean): ChatUiState {
        val response =
            ResponseRecord(
                id = "running",
                userRowIds = listOf(10),
                userIds = bubbles,
                canonicalFinalAssistantRowId = 15L.takeIf { withFinal },
                authority =
                    ReplyAuthorityFacts(
                        opening = OpeningEvidence("root", "rpc", "STREAMING", target, session, 1, userRowId = 10),
                        controls = bubbles.map { ControlInputEvidence(it, "rpc-$it", "STEERED", localUserId = it) },
                    ),
            )
        return ChatUiState(
            messages =
                bubbles.map {
                    ChatMessage(
                        id = it,
                        role = MessageRole.USER,
                        content = "more",
                        displayKind = "steer",
                    )
                },
            responseLedger = ResponseLedger(responses = listOf(response), activeId = response.id),
        )
    }

    private fun ChatUiState.beforeAcknowledgement(): ChatUiState {
        val owner = responseLedger.responses.single()
        return copy(
            responseLedger =
                responseLedger
                    .update(owner.id) { it.copy(authority = it.authority.copy(controls = emptyList())) }
                    .copy(
                        pendingInputs = mapOf(bubbles.first() to "first", bubbles.last() to "second"),
                        pendingSubmissions = mapOf("attempt-1" to "first", "attempt-2" to "second"),
                        possibleContinuations = mapOf("attempt-1" to owner.id, "attempt-2" to owner.id),
                    ),
        )
    }

    private fun reconcile(
        state: ChatUiState,
        vararg rows: SessionMessage,
    ): ChatUiState = reconcileTranscript(state, target, session, rows.toList(), offset = 0, latest = true)

    private fun row(body: String): SessionMessage =
        json.decodeFromString<SessionMessage>(body).copy(timestamp = JsonPrimitive(1795433431))
}
