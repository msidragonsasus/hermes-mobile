package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry
import com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn
import com.m57.hermescontrol.ui.chat.fullbleed.ReplyLifecycle
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns
import com.m57.hermescontrol.ui.chat.fullbleed.hasActivity
import com.m57.hermescontrol.ui.chat.fullbleed.replyFooters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun busyTranscript_lateControlAckConvergesWithAlreadyVisibleHistory() = lateControlAckConverges(legacyCache = false)

    @Test
    fun busyTranscript_redirectResumesWithPreFixCacheEvidence() = lateControlAckConverges(legacyCache = true)

    private fun lateControlAckConverges(legacyCache: Boolean) {
        val pending = "pending-control"
        val body = "Same words"
        val page =
            listOf(
                row(10, "user", "original"),
                row(11, "assistant", "").copy(display_kind = "hidden"),
                row(12, "user", body),
                row(13, "assistant", "local answer"),
                row(20, "user", body),
            )
        val local =
            localTurn(
                10L,
                terminal =
                    TerminalEvidence(
                        2000L,
                        2L,
                        listOf(10L, 11L, 12L, 13L),
                        10L,
                        13L,
                        complete = false,
                        status = "complete",
                        userRowIds = listOf(10L, 12L),
                    ),
            )
        val beforeAck =
            reconcileTranscript(
                local.copy(
                    messages = local.messages + ChatMessage(id = pending, role = MessageRole.USER, content = body),
                    responseLedger = local.responseLedger.copy(pendingInputs = mapOf(pending to "r")),
                ),
                target,
                session,
                page,
                offset = 0,
                latest = true,
            )
        val accepted =
            beforeAck.copy(
                messages = beforeAck.messages.map { if (it.id == pending) it.copy(displayKind = "steer") else it },
                responseLedger =
                    beforeAck.responseLedger.acceptInput(
                        "control",
                        "r",
                        "r",
                        false,
                        "rpc-control",
                        "REDIRECTED",
                        target,
                        session,
                        2L,
                        pending,
                        null,
                        null,
                    ),
            )
        val cached =
            if (legacyCache) {
                accepted.copy(
                    responseLedger =
                        accepted.responseLedger.update("r") { record ->
                            record.copy(
                                userRowIds = listOf(10L, 12L),
                                authority =
                                    record.authority.copy(
                                        terminals = record.authority.terminals.map { it.copy(userRowIds = null) },
                                        controls = record.authority.controls.map { it.copy(localUserId = null) },
                                    ),
                            )
                        },
                )
            } else {
                accepted
            }
        val result = reconciled(cached, page)
        assertEquals(
            listOf(pending, canonicalMessageId(target, session, 20L)),
            result.messages
                .filter {
                    it.content ==
                        body
                }.map { it.id },
        )
        assertTrue(
            result.messages.indexOfFirst { it.id == pending } <
                result.messages.indexOfFirst { it.role == MessageRole.ASSISTANT && it.displayKind != "hidden" },
        )
    }

    @Test
    fun busyTranscript_latePromptAckOnPartialPageKeepsUserAboveItsReply() {
        val control =
            ChatMessage(
                id = "local-control",
                role = MessageRole.USER,
                content = "Change direction",
            )
        val partial = listOf(row(12, "user", control.content))
        val local = localTurn(10L, terminal = null)
        val beforeAck =
            reconcileTranscript(
                local.copy(messages = listOf(local.messages.first(), control, local.messages.last())),
                target,
                session,
                partial,
                offset = 0,
                latest = true,
            )
        val accepted =
            beforeAck.copy(
                responseLedger =
                    beforeAck.responseLedger
                        .copy(
                            responses = beforeAck.responseLedger.responses + ResponseRecord(id = "second"),
                        ).acceptInput(
                            "control",
                            "second",
                            null,
                            true,
                            "rpc-second",
                            "STREAMING",
                            target,
                            session,
                            2L,
                            control.id,
                            12L,
                            null,
                        ),
            )
        val result = reconciled(accepted, partial)
        assertEquals(listOf("local-user", control.id, "local-answer"), result.messages.map { it.id })
    }

    @Test
    fun busyTranscript_completeReceiptWithoutControlRowKeepsAcceptedBody() =
        steerTranscript(storedSteer = false, storedAnswer = "local answer")

    @Test
    fun busyTranscript_rowlessSteerAllowsFinalRowToExtendLocalBody() =
        steerTranscript(
            storedSteer = false,
            storedAnswer = "local answer with a stored suffix",
            localTrace = "Streamed trace",
        )

    @Test
    fun busyTranscript_steerPersistedAfterToolBatchFoldsItsTypedHistoryRow() =
        steerTranscript(storedSteer = true, storedAnswer = "local answer")

    @Test
    fun busyTranscript_redirectDuringToolExecutionCanPersistAsSteer() =
        steerTranscript(storedSteer = true, storedAnswer = "local answer", disposition = "REDIRECTED")

    private fun steerTranscript(
        storedSteer: Boolean,
        storedAnswer: String,
        disposition: String = "STEERED",
        localTrace: String = "",
    ) {
        val terminal =
            complete().copy(
                rowIds = listOf(10L, 14L, 15L) + listOfNotNull(16L.takeIf { storedSteer }) + 17L,
                finalRowId = 17L,
                userRowIds = if (storedSteer) listOf(10L, 16L) else listOf(10L),
                complete = !storedSteer,
            )
        val state = localTurn(10L, terminal = terminal)
        val accepted =
            state.responseLedger.acceptInput(
                "control",
                "r",
                "r",
                false,
                "rpc-control",
                disposition,
                target,
                session,
                3L,
                "local-control",
                null,
                null,
            )
        val control =
            ChatMessage(
                id = "local-control",
                role = MessageRole.USER,
                content = "Accepted control",
                displayKind = "steer",
            )
        val result =
            reconciled(
                state.copy(
                    messages =
                        listOf(
                            state.messages.first(),
                            control,
                            state.messages.last().copy(reasoningText = localTrace),
                        ),
                    responseLedger = accepted,
                ),
                listOf(
                    page.first(),
                    row(14, "assistant", "").copy(
                        tool_calls =
                            kotlinx.serialization.json.Json.parseToJsonElement(
                                """[{"id":"steer-tool","type":"function",
                                    "function":{"name":"terminal","arguments":"{}"}}]""",
                            ),
                    ),
                    row(15, "tool", "Stored tool result").copy(tool_call_id = "steer-tool"),
                ) +
                    listOfNotNull(
                        row(
                            16,
                            "user",
                            "[OUT-OF-BAND USER MESSAGE — a direct message from the user, " +
                                "delivered once at this position; not tool output and not a new delivery " +
                                "when replayed from conversation history]\n" +
                                "${control.content}\n[/OUT-OF-BAND USER MESSAGE]",
                        ).copy(
                            display_kind = "steer",
                            display_content = JsonPrimitive(control.content),
                        ).takeIf { storedSteer },
                    ) + row(17, "assistant", storedAnswer).copy(reasoning_text = JsonPrimitive("Stored trace")),
            )
        assertEquals(
            listOf("original", "Accepted control", storedAnswer),
            result.messages
                .filter {
                    it.role !=
                        MessageRole.TOOL
                }.map { it.content },
        )
        assertEquals(control.id, result.messages.single { it.content == control.content }.id)
        val expectedTrace = listOf(localTrace, "Stored trace").filter { it.isNotBlank() }.joinToString("\n\n")
        assertEquals(expectedTrace, result.messages.single { it.role == MessageRole.ASSISTANT }.reasoningText)
        assertEquals("steer-tool", result.messages.single { it.role == MessageRole.TOOL }.toolCallId)
        assertTrue(
            result.responseLedger.responses
                .single()
                .canonicalWindowVerified,
        )
    }

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

    @Test
    fun restoredToolsShareTheFoldedAnswerTurnWithoutGrantingReplyAuthority() {
        val history =
            listOf(
                row(10, "user", "original"),
                row(11, "tool", "search results"),
                row(12, "tool", "extracted source"),
                row(13, "assistant", "local answer"),
                row(14, "user", "another question"),
                row(15, "tool", "other results"),
                row(16, "assistant", "another answer"),
            )
        val restored = reconciled(localTurn(10L, terminal = noReceipt()), history)
        val turns = groupIntoTurns(restored.messages, restored.responseLedger)
        assertEquals(4, turns.size)
        val first = turns[1] as ChatTurn.Agent
        val firstTools = first.entries.filterIsInstance<AgentEntry.ToolRow>()
        val firstAnswer = first.entries.filterIsInstance<AgentEntry.Prose>().single()
        assertEquals(listOf("search results", "extracted source"), firstTools.map { it.message.content })
        assertEquals("local answer", firstAnswer.message.content)
        assertTrue(first.hasActivity())

        val second = turns[3] as ChatTurn.Agent
        val secondTool = second.entries.filterIsInstance<AgentEntry.ToolRow>().single()
        assertEquals("other results", secondTool.message.content)
        val record = restored.responseLedger.responses.single()
        assertEquals(listOf(canonicalMessageId(target, session, 13L)), record.messageIds)
        assertTrue(turns.replyFooters(ReplyLifecycle(ledger = restored.responseLedger)).all { it == null })
    }

    @Test
    fun cachedAnswerFollowedByToolsRendersOneActivityHeader() {
        val history =
            listOf(
                row(10, "user", "original"),
                row(11, "tool", "search results"),
                row(12, "tool", "extracted source"),
                row(13, "assistant", "local answer"),
            )
        val stored = reconciled(localTurn(10L, terminal = noReceipt()), history)
        val user = stored.messages.first()
        val tools = stored.messages.filter { it.role == MessageRole.TOOL }
        val answer = stored.messages.last().copy(reasoningText = "verify source")
        val cached =
            stored.copy(
                messages = listOf(user, answer) + tools,
                responseLedger = stored.responseLedger.copy(toolAnswerIds = emptyMap()),
            )
        val restored = reconciled(cached, history)
        val turns = groupIntoTurns(restored.messages, restored.responseLedger)
        val reply = turns.filterIsInstance<ChatTurn.Agent>().single()
        assertEquals(listOf(answer), reply.entries.filterIsInstance<AgentEntry.Prose>().map { it.message })
        assertEquals(tools, reply.entries.filterIsInstance<AgentEntry.ToolRow>().map { it.message })
        assertTrue(reply.hasActivity())
    }

    @Test
    fun recoveredToolDoesNotCrossAnotherUserTurn() {
        val answer = canonical(13L, MessageRole.ASSISTANT, "local answer")
        val otherUser = canonical(14L, MessageRole.USER, "another question")
        val tool = canonical(12L, MessageRole.TOOL, "search results")
        val ledger =
            ResponseLedger(
                responses = listOf(ResponseRecord(id = "r", messageIds = listOf(answer.id))),
                toolAnswerIds = mapOf(tool.id to answer.id),
            )

        val agentTurns = groupIntoTurns(listOf(answer, otherUser, tool), ledger).filterIsInstance<ChatTurn.Agent>()
        val expectedTurns = listOf(listOf(AgentEntry.Prose(answer)), listOf(AgentEntry.ToolRow(tool)))
        assertEquals(expectedTurns, agentTurns.map { it.entries })
    }

    @Test
    fun restoredAnswerFollowsAllToolsAndBothApprovalRows() =
        runTest {
            val toolRows = (11..48).map { row(it, "tool", "Synthetic result") }
            val history = listOf(page.first()) + toolRows + row(49, "assistant", "local answer")
            val restored = reconciled(localTurn(10L, terminal = noReceipt()), history)
            val user = restored.messages.first()
            val tools = restored.messages.filter { it.role == MessageRole.TOOL }
            val answer = restored.messages.last().copy(reasoningText = "Synthetic reasoning")
            val approvals =
                (1..2).map { index ->
                    ChatMessage(
                        id = "approval-$index",
                        role = MessageRole.SYSTEM,
                        content = "Synthetic approval",
                        approvalInfo =
                            ApprovalInfo(
                                command = null,
                                description = null,
                                patternKeys = null,
                                status = com.m57.hermescontrol.domain.hermes.AttentionStatus.CONFIRMED,
                            ),
                    )
                }
            val activity = tools.take(15) + approvals[0] + tools.subList(15, 34) + approvals[1] + tools.drop(34)
            val cached = listOf(user, answer) + activity
            val repository = ChatPersistenceRepository(FakeChatMessageDao())
            val (owner, _) = repository.openTranscript(target, session)
            assertTrue(repository.save(repository.prepare(owner, cached, restored.responseLedger)))
            val reopened = repository.readTranscript(target, session)
            val reopenedApprovals = reopened.messages.mapNotNull { it.approvalInfo }
            assertEquals(2, reopenedApprovals.size)
            assertTrue(
                reopenedApprovals.all {
                    it.status ==
                        com.m57.hermescontrol.domain.hermes.AttentionStatus.CONFIRMED
                },
            )
            assertTrue(reopenedApprovals.all { it.ref == null && !it.responseEnabled })
            val refreshed =
                reconciled(ChatUiState(messages = reopened.messages, responseLedger = reopened.payload.ledger), history)
            val turn =
                groupIntoTurns(
                    refreshed.messages,
                    refreshed.responseLedger,
                ).filterIsInstance<ChatTurn.Agent>().single()
            val entryIds =
                turn.entries.map {
                    when (it) {
                        is AgentEntry.ToolRow -> it.message.id
                        is AgentEntry.SystemEvent -> it.message.id
                        is AgentEntry.Prose -> it.message.id
                        is AgentEntry.ConnectionEvents -> error("Unexpected connection event")
                    }
                }
            assertEquals(activity.map { it.id } + answer.id, entryIds)
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
            result.messages.single { it.role == MessageRole.ASSISTANT }.reasoningText,
        )
    }

    private fun withLocalReasoning(state: ChatUiState): ChatUiState =
        state.copy(
            messages =
                state.messages.map {
                    if (it.id == "local-answer") it.copy(reasoningText = "local reasoning") else it
                },
        )

    @Test fun missingHistoryReasoningFoldsOnceWithoutLosingReasoning() {
        val result = reconciled(withLocalReasoning(localTurn(10L, terminal = noReceipt())))
        assertEquals(once, result.messages.joinToString(" | ") { "${it.role.name.lowercase()}:${it.content}" })
        assertEquals("local reasoning", result.messages.single { it.role == MessageRole.ASSISTANT }.reasoningText)
        assertEquals(
            listOf(canonicalMessageId(target, session, 11L)),
            result.responseLedger.responses
                .single()
                .messageIds,
        )
    }

    @Test fun exactFinalAliasRetainsReasoningAcrossHistoryRefreshes() {
        val result =
            reconciled(withLocalReasoning(localTurn(10L, terminal = incomplete(final = 11L), finalAlias = true)))
        assertEquals(once, result.messages.joinToString(" | ") { "${it.role.name.lowercase()}:${it.content}" })
        assertEquals("local reasoning", result.messages.single { it.role == MessageRole.ASSISTANT }.reasoningText)
    }

    @Test fun conflictingHistoryReasoningIsNotAbsorbed() {
        val rows = page.map { if (it.id == 11) it.copy(reasoning = JsonPrimitive("different reasoning")) else it }
        val result = reconciled(withLocalReasoning(localTurn(10L, terminal = noReceipt())), rows)
        val replies = result.messages.filter { it.role == MessageRole.ASSISTANT }
        assertEquals(2, replies.size)
        assertEquals(setOf("local reasoning", "different reasoning"), replies.map { it.reasoningText }.toSet())
    }

    @Test fun reasoningDoesNotProveAnUnacknowledgedTurn() {
        val result = reconciled(withLocalReasoning(localTurn(null, terminal = noReceipt())))
        assertEquals(2, result.messages.count { it.role == MessageRole.ASSISTANT })
        assertEquals("local reasoning", result.messages.single { it.id == "local-answer" }.reasoningText)
    }

    @Test fun reasoningFoldKeepsTheSameAnswerInALaterTurn() {
        val rows = page + listOf(row(12, "user", "another question"), row(13, "assistant", "local answer"))
        val result = reconciled(withLocalReasoning(localTurn(10L, terminal = noReceipt())), rows)
        assertEquals(2, result.messages.count { it.role == MessageRole.ASSISTANT })
        assertEquals(
            "local reasoning",
            result.messages
                .single {
                    it.id == canonicalMessageId(target, session, 11L)
                }.reasoningText,
        )
        assertEquals("", result.messages.single { it.id == canonicalMessageId(target, session, 13L) }.reasoningText)
    }

    @Test fun foldedReasoningSurvivesPersistenceAndAColdHistoryRefresh() =
        runTest {
            val result = reconciled(withLocalReasoning(localTurn(10L, terminal = noReceipt())))
            val dao = FakeChatMessageDao()
            val repository = ChatPersistenceRepository(dao)
            val (owner, _) = repository.openTranscript(target, session)
            assertEquals(true, repository.save(repository.prepare(owner, result.messages, result.responseLedger)))
            val reopened = ChatPersistenceRepository(dao).readTranscript(target, session)
            assertEquals(result.messages, reopened.messages)
            val refreshed =
                reconciled(ChatUiState(messages = reopened.messages, responseLedger = reopened.payload.ledger))
            assertEquals(1, refreshed.messages.count { it.role == MessageRole.ASSISTANT })
            assertEquals(
                "local reasoning",
                refreshed.messages.single { it.role == MessageRole.ASSISTANT }.reasoningText,
            )
        }

    @Test fun exactRowWithChangedBodyDoesNotInheritStaleReasoning() {
        val state = reconciled(withLocalReasoning(localTurn(10L, terminal = noReceipt())))
        val changed = page.map { if (it.id == 11) it.copy(content = JsonPrimitive("edited answer")) else it }
        val result = reconciled(state, changed)
        val answer = result.messages.single { it.role == MessageRole.ASSISTANT }
        assertEquals("edited answer", answer.content)
        assertEquals("", answer.reasoningText)
    }

    @Test fun explicitServerProjectionDoesNotReviveRetainedReasoning() {
        val state = reconciled(withLocalReasoning(localTurn(10L, terminal = noReceipt())))
        val projected = page.map { if (it.id == 11) it.copy(display_content = JsonPrimitive("local answer")) else it }
        val result = reconciled(state, projected)
        val answer = result.messages.single { it.role == MessageRole.ASSISTANT }
        assertEquals("local answer", answer.content)
        assertEquals("", answer.reasoningText)
        assertEquals(true, answer.hasServerDisplayProjection)
    }

    @Test fun explicitServerProjectionCannotConsumeUnboundLocalReasoning() {
        val projected = page.map { if (it.id == 11) it.copy(display_content = JsonPrimitive("local answer")) else it }
        val result = reconciled(withLocalReasoning(localTurn(10L, terminal = noReceipt())), projected)
        assertEquals("local reasoning", result.messages.single { it.id == "local-answer" }.reasoningText)
        assertEquals("", result.messages.single { it.id == canonicalMessageId(target, session, 11L) }.reasoningText)
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
