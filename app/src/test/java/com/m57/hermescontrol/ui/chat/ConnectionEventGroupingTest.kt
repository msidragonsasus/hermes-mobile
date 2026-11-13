package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry
import com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurnsWithStreaming
import com.m57.hermescontrol.ui.chat.fullbleed.messageIdToLazyIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionEventGroupingTest {
    private fun event(
        id: String,
        text: String,
    ) = ChatMessage(id = id, role = MessageRole.SYSTEM, content = text)

    @Test
    fun adjacentConnectionEventsKeepEveryMessageButHaveNoVisibleSearchDestination() {
        val messages = listOf(event("1", "Connected to Hermes"), event("2", "Session resumed"))
        val entries = (groupIntoTurns(messages).single() as ChatTurn.Agent).entries
        assertEquals(messages, (entries.single() as AgentEntry.ConnectionEvents).messages)
        assertEquals(emptyMap<String, Int>(), messageIdToLazyIndex(groupIntoTurns(messages), leadingItems = 1))
    }

    @Test
    fun errorsApprovalsAndMarkersBreakTheCompactGroup() {
        val connection = event("1", "Connected to Hermes")
        val error = event("2", "Connection failed: rejected")
        val approval = event("3", "Session resumed").copy(approvalInfo = ApprovalInfo(null, null, null))
        val marker = event("4", "Connected to Hermes").copy(displayKind = "model_changed")
        val messages = listOf(connection, error, approval, marker, event("5", "Session resumed"))
        val entries = (groupIntoTurns(messages).single() as ChatTurn.Agent).entries
        assertEquals(5, entries.size)
        assertEquals(error, (entries[1] as AgentEntry.SystemEvent).message)
        assertEquals(approval, (entries[2] as AgentEntry.SystemEvent).message)
        assertEquals(marker, (entries[3] as AgentEntry.SystemEvent).message)
    }

    @Test
    fun groupingCannotCrossUserOrToolMessagesAndStreamingIsPreserved() {
        val user = ChatMessage(id = "u", role = MessageRole.USER, content = "draft")
        val tool = ChatMessage(id = "t", role = MessageRole.TOOL, content = "output", toolStatus = ToolStatus.FAILED)
        val streaming = ChatMessage(id = "a", role = MessageRole.ASSISTANT, content = "partial", isStreaming = true)
        val turns =
            groupIntoTurnsWithStreaming(
                listOf(
                    event("1", "Connected to Hermes"),
                    user,
                    event("2", "Session resumed"),
                    tool,
                    event("3", "Session resumed"),
                ),
                streaming,
            )
        assertEquals(3, turns.size)
        assertEquals(user, (turns[1] as ChatTurn.User).message)
        val entries = (turns[2] as ChatTurn.Agent).entries
        assertTrue(entries[0] is AgentEntry.ConnectionEvents)
        assertEquals(tool, (entries[1] as AgentEntry.ToolRow).message)
        assertTrue(entries[2] is AgentEntry.ConnectionEvents)
        assertEquals(streaming, (entries[3] as AgentEntry.Prose).message)
    }

    @Test
    fun similarAssistantOrSystemTextIsNeverCollapsed() {
        val messages =
            listOf(
                ChatMessage(id = "a", role = MessageRole.ASSISTANT, content = "Session resumed"),
                event("s", "Session resumed with warnings"),
            )
        val entries = (groupIntoTurns(messages).single() as ChatTurn.Agent).entries
        assertTrue(entries[0] is AgentEntry.Prose)
        assertTrue(entries[1] is AgentEntry.SystemEvent)
    }
}
