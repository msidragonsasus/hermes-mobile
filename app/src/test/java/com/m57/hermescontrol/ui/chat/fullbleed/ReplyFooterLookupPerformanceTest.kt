package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.CoverageEvidence
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.ResponseLedger
import com.m57.hermescontrol.ui.chat.ResponseRecord
import com.m57.hermescontrol.ui.chat.acceptedAuthorityFixture
import io.mockk.every
import io.mockk.spyk
import io.mockk.unmockkAll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyFooterLookupPerformanceTest {
    @Test
    fun canonicalPlanningReadsEachPhysicalIdOnlyABoundedNumberOfTimes() {
        // Flattening makes the entry list private to the real planner. Observe
        // one real message getter instead: no substitute planner or clock bound.
        var idReads = 0
        val watched = spyk(prose("answer-0", "  本文-0\n"))
        try {
            every { watched.id } answers {
                idReads++
                callOriginal()
            }
            val rows = listOf(watched) + (1 until 128).map { prose("answer-$it", "  本文-$it\n") }
            val records = (0 until 128).map { canonical("reply-$it", listOf("answer-$it"), 1000L + it) }
            val ledger = ResponseLedger(records)
            val turns = groupIntoTurns(rows, ledger)
            idReads = 0

            val actual = turns.replyFooters(ReplyLifecycle(ledger = ledger))
            val readsDuringPlanning = idReads

            assertEquals((0 until 128).map { ReplyFooter("  本文-$it\n", 1000L + it, "reply-$it") }, actual)
            assertTrue(
                "Canonical lookup must index each physical ID rather than rescan it per member: reads=$readsDuringPlanning",
                readsDuringPlanning <= 4,
            )
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun canonicalOrderDuplicatePhysicalIdsAndFooterDestinationArePreserved() {
        val a = prose("a", " first \n")
        val b = prose("b", "最後\n")
        val user = ChatMessage(id = "u", role = MessageRole.USER, content = "next", timestamp = 0)
        val duplicate = prose("duplicate", "must not be copied")
        // Canonical order differs from display order. Duplicate physical IDs
        // must be omitted just as singleOrNull did, never last/first-wins.
        val turns =
            listOf(
                ChatTurn.Agent(listOf(AgentEntry.Prose(a), AgentEntry.Prose(duplicate))),
                ChatTurn.User(user),
                ChatTurn.Agent(listOf(AgentEntry.Prose(b), AgentEntry.Prose(duplicate.copy(content = "other copy")))),
            )
        val ledger = ResponseLedger(listOf(canonical("reply", listOf("b", "duplicate", "a"), 77)))
        assertEquals(
            listOf(ReplyFooter("最後\n\n\n first \n", 77, "reply"), null, null),
            turns.replyFooters(ReplyLifecycle(ledger = ledger)),
        )
    }

    @Test
    fun localFallbackKeepsTaggedUserControlDestinationAndExactCopy() {
        val a = prose("a", "  first\n")
        val control =
            ChatMessage(
                id = "control",
                role = MessageRole.USER,
                content = "steer",
                displayKind = "steer",
                timestamp = 0,
            )
        val b = prose("b", "second  ")
        val record =
            ResponseRecord(
                id = "local",
                messageIds = listOf("a", "b"),
                userIds = listOf("control"),
                authority = acceptedAuthorityFixture(time = 41, members = listOf("a", "b")),
            )
        val ledger = ResponseLedger(listOf(record))
        val turns = groupIntoTurns(listOf(a, b, control), ledger)
        val footers = turns.replyFooters(ReplyLifecycle(ledger = ledger))
        assertEquals(listOf(ReplyFooter("  first\n\n\nsecond  ", 41, "local")), footers)
        assertEquals(mapOf("a" to 0, "b" to 1, "control" to 2), messageIdToLazyIndex(turns, replyFooters = footers))
    }

    private fun canonical(
        id: String,
        members: List<String>,
        time: Long,
    ) = ResponseRecord(
        id = id,
        messageIds = members,
        authority =
            acceptedAuthorityFixture(time = time, members = members).copy(
                local = null,
                canonical = CoverageEvidence(source = "fixture", memberIds = members),
            ),
    )

    private fun prose(
        id: String,
        text: String,
    ) = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = text, timestamp = 0)
}
