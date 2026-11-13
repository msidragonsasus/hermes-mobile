package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.CoverageEvidence
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.ResponseLedger
import com.m57.hermescontrol.ui.chat.ResponseRecord
import com.m57.hermescontrol.ui.chat.acceptedAuthorityFixture
import org.junit.Assert.assertEquals
import org.junit.Test

/** Compatibility checks against the same real planner as the operation-count regression. */
class ReplyFooterSemanticsPerformanceTest {
    @Test
    fun conflictingRepliesInOnePresentationGroupLeaveNoFooter() {
        val rows = listOf(prose("a"), prose("b"), prose("c"))
        val turns = listOf(ChatTurn.Agent(rows.map { AgentEntry.Prose(it) }))
        val ledger = ResponseLedger(rows.map { canonical(it.id, listOf(it.id)) })
        assertEquals(listOf<ReplyFooter?>(null), turns.replyFooters(ReplyLifecycle(ledger = ledger)))
    }

    @Test
    fun canonicalLookupDoesNotRelaxAuthorityOrResponseOwnership() {
        val record = canonical("reply", listOf("a"))
        val variants =
            listOf(
                ResponseLedger(listOf(record.copy(authority = record.authority.copy(opening = null)))),
                ResponseLedger(listOf(record.copy(authority = record.authority.copy(terminals = emptyList())))),
                ResponseLedger(
                    listOf(
                        record
                            .copy(
                                authority =
                                    record.authority.copy(
                                        terminals =
                                            record.authority.terminals.map {
                                                it.copy(partial = true)
                                            },
                                    ),
                            ),
                    ),
                ),
                ResponseLedger(listOf(record), pendingInputs = mapOf("pending" to "reply")),
                ResponseLedger(listOf(record, canonical("other", listOf("a")))),
                ResponseLedger(listOf(canonical("reply", listOf("a", "missing")))),
            )
        for (ledger in variants) {
            val turns = groupIntoTurns(listOf(prose("a")), ledger)
            assertEquals(listOf<ReplyFooter?>(null), turns.replyFooters(ReplyLifecycle(ledger = ledger)))
        }
    }

    @Test
    fun terminalReplayKeepsOriginalObservedTimeAndHiddenControlAddsNoCopyText() {
        val record = canonical("reply", listOf("a", "hidden"))
        val replay =
            record.copy(
                authority =
                    record.authority.copy(
                        terminals =
                            record.authority.terminals +
                                record.authority.terminals
                                    .single()
                                    .copy(observedAt = 999),
                    ),
            )
        val hidden =
            ChatMessage(
                id = "hidden",
                role = MessageRole.USER,
                content = "model-only",
                displayKind = "hidden",
                timestamp = 0,
            )
        val ledger = ResponseLedger(listOf(replay))
        val turns = groupIntoTurns(listOf(prose("a"), hidden), ledger)
        val footers = turns.replyFooters(ReplyLifecycle(ledger = ledger))
        assertEquals(listOf(ReplyFooter("本文-a", 77, "reply")), footers)
        // Preserve the existing index helper's treatment of this materialized
        // system-style row; footer indexing must not invent a visibility change.
        assertEquals(mapOf("a" to 0, "hidden" to 1), messageIdToLazyIndex(turns, replyFooters = footers))
    }

    private fun canonical(
        id: String,
        members: List<String>,
    ): ResponseRecord =
        ResponseRecord(
            id = id,
            messageIds = members,
            authority =
                acceptedAuthorityFixture(time = 77, members = members).copy(
                    local = null,
                    canonical = CoverageEvidence(source = "fixture", memberIds = members),
                ),
        )

    private fun prose(id: String) =
        ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "本文-$id", timestamp = 0)
}
