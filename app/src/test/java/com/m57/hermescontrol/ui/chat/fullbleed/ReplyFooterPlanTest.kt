package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.ui.chat.*
import org.junit.Assert.*
import org.junit.Test

/** The approved v9 contract replaces group-age/creation-time fixtures with explicit proof. */
class ReplyFooterPlanTest {
    private fun prose(
        id: String,
        text: String = "answer-$id",
    ) = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = text, timestamp = 999)

    private fun record(
        vararg ids: String,
        time: Long? = 77,
        id: String = "r",
        coverage: Boolean = true,
    ) = ResponseRecord(
        id = id,
        messageIds = ids.toList(),
        finishedAt = time,
        coverage = coverage,
        authority = acceptedAuthorityFixture(time = time, members = ids.toList(), gap = !coverage),
    )

    private fun plan(
        messages: List<ChatMessage>,
        vararg records: ResponseRecord,
    ): List<ReplyFooter?> {
        val ledger = ResponseLedger(records.toList())
        return groupIntoTurns(messages, ledger).replyFooters(ReplyLifecycle(ledger = ledger))
    }

    @Test fun legacyGroupPositionAndSealedTimesAreNeverProof() {
        val a = prose("a").copy(finishTimestamp = 123)
        val user = ChatMessage(id = "u", role = MessageRole.USER, content = "next")
        assertTrue(plan(listOf(a, user, prose("b"))).all { it == null })
    }

    @Test fun aTransientTerminalFlagWithoutIdentityIsNotProof() {
        val turns = groupIntoTurns(listOf(prose("a")))
        assertEquals(
            listOf<ReplyFooter?>(null),
            turns.replyFooters(ReplyLifecycle(responseEnded = true, finalizedMessageId = "a")),
        )
    }

    @Test fun sealedSegmentsAndCompletedToolsDoNotProveCompletion() {
        val rows =
            listOf(
                prose("a").copy(finishTimestamp = 1),
                ChatMessage(id = "t", role = MessageRole.TOOL, content = "raw", toolStatus = ToolStatus.COMPLETED),
            )
        assertTrue(plan(rows, record("a", "t", time = null)).all { it == null })
    }

    @Test fun streamingFlagCannotReplaceTerminalEvidence() {
        assertEquals(
            listOf<ReplyFooter?>(null),
            plan(listOf(prose("a").copy(isStreaming = true)), record("a", time = null)),
        )
    }

    @Test fun explicitProofWinsOverStaleStreamingAndToolFlags() {
        val rows =
            listOf(
                prose("a").copy(isStreaming = true),
                ChatMessage(id = "t", role = MessageRole.TOOL, content = "raw", toolStatus = ToolStatus.RUNNING),
            )
        assertEquals(listOf(ReplyFooter("answer-a", 77, "r")), plan(rows, record("a", "t")))
    }

    @Test fun terminalTimeNeverFallsBackToCreationOrSegmentFinish() {
        assertEquals(
            listOf<ReplyFooter?>(null),
            plan(listOf(prose("a").copy(finishTimestamp = 333)), record("a", time = null)),
        )
        assertEquals(77L, plan(listOf(prose("a").copy(finishTimestamp = 333)), record("a")).single()!!.finishedAt)
    }

    @Test fun terminalAndExhaustiveCoverageAreIndependent() {
        assertEquals(listOf<ReplyFooter?>(null), plan(listOf(prose("a")), record("a", coverage = false)))
    }

    @Test fun missingVisibleProseWithholdsWholeCopy() {
        assertEquals(listOf<ReplyFooter?>(null), plan(listOf(prose("a")), record("a", "missing")))
    }

    @Test fun repeatedIdenticalMarkdownAndWhitespaceArePreservedExactly() {
        val markdown = "  **見出し**\n\n```kotlin\nval x = 1\n```\n"
        assertEquals(
            listOf(ReplyFooter("$markdown\n\n$markdown", 77, "r")),
            plan(listOf(prose("a", markdown), prose("b", markdown)), record("a", "b")),
        )
    }

    @Test fun copyExcludesToolsSystemAndReasoningWithoutTrimmingProse() {
        val rows =
            listOf(
                prose("a", " first ").copy(reasoningText = "private"),
                ChatMessage(id = "t", role = MessageRole.TOOL, content = "raw"),
                ChatMessage(id = "s", role = MessageRole.SYSTEM, content = "system"),
                prose("blank", " \n ").copy(reasoningText = "private"),
                prose("b", "last\n"),
            )
        assertEquals(listOf(ReplyFooter(" first \n\nlast\n", 77, "r")), plan(rows, record("a", "t", "s", "blank", "b")))
    }

    @Test fun blankReasoningToolAndAttachmentOnlyResponsesHaveNoEmptyCopy() {
        val rows =
            listOf(
                prose("a", " \n\t").copy(
                    reasoningText = "private",
                    attachments = listOf(Attachment("content://fixture/file", "file.txt", "text/plain")),
                ),
                ChatMessage(id = "t", role = MessageRole.TOOL, content = "raw"),
            )
        assertTrue(plan(rows, record("a", "t")).all { it == null })
    }

    @Test fun completedHistoryKeepsProofWhileNextResponseRuns() {
        val u = ChatMessage(id = "u", role = MessageRole.USER, content = "next")
        assertEquals(
            listOf(ReplyFooter("answer-a", 77, "old"), null, null),
            plan(listOf(prose("a"), u, prose("b")), record("a", id = "old"), record("b", id = "new", time = null)),
        )
    }

    @Test fun steerSplitHasOneWholeResponseFooterOnItsLastGroupIncludingBlankProse() {
        val user = ChatMessage(id = "u", role = MessageRole.USER, content = "pending")
        assertEquals(
            listOf(null, null, ReplyFooter("answer-a", 77, "r")),
            plan(listOf(prose("a"), user, prose("b", "")), record("a", "b")),
        )
    }

    @Test fun separateRepliesHaveSeparateFooterTimesAndIdentities() {
        assertEquals(
            listOf(ReplyFooter("answer-a", 11, "one"), ReplyFooter("answer-b", 22, "two")),
            plan(
                listOf(prose("a"), prose("b")),
                record("a", time = 11, id = "one"),
                record("b", time = 22, id = "two"),
            ),
        )
    }

    @Test fun duplicateMembershipFailsClosedInsteadOfDuplicatingCopy() {
        assertTrue(plan(listOf(prose("a")), record("a", id = "one"), record("a", id = "two")).all { it == null })
    }

    @Test fun disconnectAndUnknownDoNotEraseAlreadyPersistedTerminalProof() {
        val r = record("a")
        val state =
            ChatUiState(
                messages = listOf(prose("a")),
                connectionStatus = ConnectionStatus.DISCONNECTED,
                responseStatusUnknown = true,
                responseLedger = ResponseLedger(listOf(r), activeId = r.id),
            )
        assertEquals(
            listOf(ReplyFooter("answer-a", 77, "r")),
            groupIntoTurns(state.messages).replyFooters(replyLifecycle(state, StreamingState())),
        )
    }

    @Test fun currentInputWaitsWithholdControlsWithoutErasingUnrelatedHistory() {
        val r = record("a")
        val base =
            ChatUiState(messages = listOf(prose("a")), responseLedger = ResponseLedger(listOf(r), activeId = r.id))
        val waits =
            listOf(
                base.copy(clarifyRequest = ClarifyUi("pick")),
                base.copy(sudoPrompt = SudoPromptUi("request", "session")),
                base.copy(secretPrompt = SecretPromptUi("request", "session")),
            )
        waits.forEach { state ->
            assertTrue(
                groupIntoTurns(state.messages).replyFooters(replyLifecycle(state, StreamingState())).all {
                    it ==
                        null
                },
            )
        }
    }

    @Test fun pendingApprovalWithholdsCurrentControlsUntilResolved() {
        for (status in listOf(AttentionStatus.PENDING, AttentionStatus.SUBMITTING, AttentionStatus.UNKNOWN)) {
            val rows =
                listOf(
                    prose("a"),
                    ChatMessage(
                        id = "approval",
                        role = MessageRole.SYSTEM,
                        content = "approve",
                        approvalInfo = ApprovalInfo(null, null, null, status = status),
                    ),
                )
            val state =
                ChatUiState(
                    messages = rows,
                    responseLedger = ResponseLedger(listOf(record("a", "approval")), activeId = "r"),
                )
            assertTrue(groupIntoTurns(rows).replyFooters(replyLifecycle(state, StreamingState())).all { it == null })
        }
    }

    @Test fun historicalFooterOffsetsFollowingSearchMatch() {
        val rows = listOf(prose("a"), ChatMessage(id = "u", role = MessageRole.USER, content = "next"))
        val ledger = ResponseLedger(listOf(record("a")))
        val turns = groupIntoTurns(rows, ledger)
        assertEquals(
            2,
            messageIdToLazyIndex(turns, replyFooters = turns.replyFooters(ReplyLifecycle(ledger = ledger)))["u"],
        )
    }
}
