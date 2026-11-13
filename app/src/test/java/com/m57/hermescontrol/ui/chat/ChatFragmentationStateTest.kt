package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry
import com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn
import com.m57.hermescontrol.ui.chat.fullbleed.RenderPlanningCache
import com.m57.hermescontrol.ui.chat.fullbleed.ReplyLifecycle
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurnsWithStreaming
import com.m57.hermescontrol.ui.chat.fullbleed.replyFooters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFragmentationStateTest {
    private val user = ChatMessage(id = "user", role = MessageRole.USER, content = "question")
    private val tool = ChatMessage(id = "tool", role = MessageRole.TOOL, content = "running")
    private val stream =
        ChatMessage(
            id = "live",
            role = MessageRole.ASSISTANT,
            content = "",
            reasoningText = "progress",
            isStreaming = true,
        )
    private val active = ResponseRecord(id = "active", userIds = listOf(user.id), messageIds = listOf(tool.id))
    private val ledger = ResponseLedger(responses = listOf(active), activeId = active.id)

    @Test
    fun explicitLocalModelMarkerBetweenOwnedRowsDoesNotSplitRunningGroup() {
        val marker =
            ChatMessage(id = "switch", role = MessageRole.USER, content = "/model next", displayKind = "model_switch")
        val secondTool = tool.copy(id = "tool2")
        val updated = ledger.copy(responses = listOf(active.copy(messageIds = listOf(tool.id, secondTool.id))))
        val result = groupIntoTurnsWithStreaming(listOf(user, tool, marker, secondTool), stream, updated)
        assertEquals(1, result.filterIsInstance<ChatTurn.Agent>().size)
        assertEquals(
            listOf(
                AgentEntry.ToolRow(tool),
                AgentEntry.SystemEvent(marker),
                AgentEntry.ToolRow(secondTool),
                AgentEntry.Prose(stream),
            ),
            result.filterIsInstance<ChatTurn.Agent>().single().entries,
        )
        assertEquals(listOf(tool.id, secondTool.id), updated.responses.single().messageIds)
    }

    @Test
    fun trailingLocalModelControlDoesNotDetachLiveStream() {
        val marker =
            ChatMessage(id = "switch", role = MessageRole.USER, content = "/model next", displayKind = "model_switch")
        val result = groupIntoTurnsWithStreaming(listOf(user, tool, marker), stream, ledger)
        assertEquals(1, result.filterIsInstance<ChatTurn.Agent>().size)
    }

    @Test
    fun genuineUserAndUntaggedLegacyModelRemainHardBoundaries() {
        for (text in listOf("new question", "/model legacy")) {
            val nextUser = user.copy(id = "next-user", content = text)
            val result = groupIntoTurnsWithStreaming(listOf(user, tool, nextUser), stream, ledger)
            assertEquals(2, result.filterIsInstance<ChatTurn.User>().size)
            assertEquals(2, result.filterIsInstance<ChatTurn.Agent>().size)
            assertEquals(listOf(AgentEntry.Prose(stream)), (result.last() as ChatTurn.Agent).entries)
        }
    }

    @Test
    fun unknownTrailingRowIsNotBlanketMergedIntoOwnedResponse() {
        val unknown = tool.copy(id = "unknown")
        val result = groupIntoTurnsWithStreaming(listOf(tool, unknown), stream, ledger)
        assertEquals(listOf(AgentEntry.ToolRow(tool)), (result.first() as ChatTurn.Agent).entries)
        assertEquals(2, result.size)
    }

    @Test
    fun missingFinishedTerminalOrAmbiguousActiveOwnerDoesNotInferLiveMembership() {
        val variants =
            listOf(
                ledger.copy(activeId = null),
                ledger.copy(activeId = "missing"),
                ledger.copy(responses = listOf(active.copy(finishedAt = 99))),
                ledger.copy(
                    responses =
                        listOf(
                            active.copy(authority = ReplyAuthorityFacts(terminals = listOf(TerminalEvidence(99, 2)))),
                        ),
                ),
                ledger.copy(responses = listOf(active, active)),
                ledger.copy(responses = listOf(active, active.copy(id = "other"))),
                ledger.copy(responses = listOf(active, ResponseRecord(id = "new")), activeId = "new"),
            )
        variants.forEach { candidate ->
            assertEquals(candidate.toString(), 2, groupIntoTurnsWithStreaming(listOf(tool), stream, candidate).size)
        }
    }

    @Test
    fun knownIndependentResponseAndOwnedModelMarkerRemainBoundaries() {
        val other = ResponseRecord(id = "other", messageIds = listOf(stream.id))
        val mixed = ledger.copy(responses = listOf(active, other))
        assertEquals(2, groupIntoTurnsWithStreaming(listOf(tool), stream, mixed).size)
        val marker = user.copy(id = "switch", content = "/model next", displayKind = "model_switch")
        val ownedMarker = ledger.copy(responses = listOf(active, other.copy(messageIds = listOf(marker.id))))
        val result = groupIntoTurnsWithStreaming(listOf(tool, marker), stream, ownedMarker)
        assertEquals(3, result.size)
        assertEquals(listOf(AgentEntry.SystemEvent(marker)), (result[1] as ChatTurn.Agent).entries)
    }

    @Test
    fun duplicateStreamIdKeepsCommittedRowAndDoesNotAddProse() {
        val committed = stream.copy(content = "committed", isStreaming = false)
        val result = groupIntoTurnsWithStreaming(listOf(tool, committed), stream, ledger)
        val prose =
            result
                .filterIsInstance<ChatTurn.Agent>()
                .flatMap { it.entries }
                .filterIsInstance<AgentEntry.Prose>()
        assertEquals(listOf(AgentEntry.Prose(committed)), prose)
    }

    @Test
    fun nonLiveOrNonAssistantTailCannotUsePresentationOverride() {
        assertEquals(2, groupIntoTurnsWithStreaming(listOf(tool), stream.copy(isStreaming = false), ledger).size)
        assertEquals(2, groupIntoTurnsWithStreaming(listOf(tool), stream.copy(role = MessageRole.SYSTEM), ledger).size)
        assertEquals(
            groupIntoTurns(listOf(user, tool), ledger),
            groupIntoTurnsWithStreaming(listOf(user, tool), null, ledger),
        )
    }

    @Test
    fun terminalFooterUsesOnlyOwnedAnswerAndNeverUnownedStreamOrControlText() {
        val answer = stream.copy(id = "answer", content = "owned final", isStreaming = false)
        val marker = user.copy(id = "switch", content = "/model next", displayKind = "model_switch")
        val done =
            active.copy(
                messageIds = listOf(answer.id),
                finishedAt = 99,
                authority =
                    ReplyAuthorityFacts(
                        opening = OpeningEvidence("attempt", "request", "STREAMING", "target", "session", 1),
                        terminals = listOf(TerminalEvidence(99, 2)),
                        local = CoverageEvidence("local", listOf(answer.id)),
                    ),
            )
        val finishedLedger = ledger.copy(responses = listOf(done))
        val result =
            groupIntoTurnsWithStreaming(
                listOf(user, answer, marker),
                stream.copy(content = "unowned live"),
                finishedLedger,
            )
        val footers = result.replyFooters(ReplyLifecycle(ledger = finishedLedger)).filterNotNull()
        assertEquals(1, footers.size)
        assertEquals("owned final", footers.single().text)
        assertEquals(99L, footers.single().finishedAt)
        assertEquals(done.id, footers.single().responseId)
        assertEquals(listOf(answer.id), finishedLedger.responses.single().messageIds)
    }

    @Test
    fun renderPlanningCacheUsesPresentationGroupingWithoutChangingFooterAuthority() {
        val cache = RenderPlanningCache()
        val scope = Any()
        val lifecycle = ReplyLifecycle(ledger = ledger)
        val rows = listOf(user, tool)
        val first = cache.plan(rows, stream, lifecycle, scope)
        val next = cache.plan(rows, stream.copy(content = "live text"), lifecycle, scope)
        assertEquals(1, first.turns.filterIsInstance<ChatTurn.Agent>().size)
        assertEquals(1, next.turns.filterIsInstance<ChatTurn.Agent>().size)
        assertTrue(first.replyFooters.all { it == null })
        assertTrue(next.replyFooters.all { it == null })
        assertEquals(
            listOf(tool.id),
            lifecycle.ledger.responses
                .single()
                .messageIds,
        )
    }

    @Test
    fun ownedToolAndUnownedLiveStreamSharePresentationWithoutAuthorityMutation() {
        val rows = listOf(user, tool)
        val before = ledger.copy()
        val result = groupIntoTurnsWithStreaming(rows, stream, ledger)
        assertEquals(
            "one response must not acquire a separate live header",
            1,
            result.filterIsInstance<ChatTurn.Agent>().size,
        )
        assertEquals(
            listOf(AgentEntry.ToolRow(tool), AgentEntry.Prose(stream)),
            result.filterIsInstance<ChatTurn.Agent>().single().entries,
        )
        assertEquals(before, ledger)
        assertEquals(listOf(user, tool), rows)
        assertTrue(stream.id !in ledger.responses.single().messageIds)
        assertTrue(result.replyFooters(ReplyLifecycle(ledger = ledger)).all { it == null })
    }
}
