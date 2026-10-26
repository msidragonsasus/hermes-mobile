package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.ui.chat.ApprovalInfo
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ClarifyUi
import com.m57.hermescontrol.ui.chat.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTurnActivityTest {
    @Test
    fun answersAlwaysShowAndToolsFoldByDefault() {
        val prose = AgentEntry.Prose(ChatMessage(id = "answer", role = MessageRole.ASSISTANT, content = "Answer"))
        val tool = AgentEntry.ToolRow(ChatMessage(id = "tool", role = MessageRole.TOOL, content = "Output"))
        assertTrue(prose.visibleWhen(activityExpanded = false))
        assertTrue(prose.visibleWhen(activityExpanded = true))
        assertFalse(tool.visibleWhen(activityExpanded = false))
        assertTrue(tool.visibleWhen(activityExpanded = true))
    }

    @Test
    fun approvalAndClarificationNeverFoldAway() {
        val approval =
            ChatMessage(
                id = "approval",
                role = MessageRole.TOOL,
                content = "Approval",
                approvalInfo = ApprovalInfo(command = null, description = null, patternKeys = null),
            )
        val clarify =
            ChatMessage(
                id = "clarify",
                role = MessageRole.ASSISTANT,
                content = "Question",
                clarifyInfo = ClarifyUi(text = "Choose", options = listOf("a")),
            )
        val system = AgentEntry.SystemEvent(ChatMessage(id = "system", role = MessageRole.SYSTEM, content = "Status"))
        listOf(false, true).forEach { expanded ->
            assertTrue(AgentEntry.ToolRow(approval).visibleWhen(expanded))
            assertTrue(AgentEntry.Prose(clarify).visibleWhen(expanded))
            assertTrue(system.visibleWhen(expanded))
        }
    }

    @Test
    fun searchIndicesFollowFoldedAndOpenedTurns() {
        val messages =
            listOf(
                ChatMessage(id = "u", role = MessageRole.USER, content = "Request"),
                ChatMessage(id = "a1", role = MessageRole.ASSISTANT, content = "First", reasoningText = "Trace one"),
                ChatMessage(id = "t", role = MessageRole.TOOL, content = "Output"),
                ChatMessage(id = "a2", role = MessageRole.ASSISTANT, content = "Second", reasoningText = "Trace two"),
                ChatMessage(id = "s", role = MessageRole.SYSTEM, content = "Important status"),
                ChatMessage(id = "next", role = MessageRole.USER, content = "Next"),
            )
        val turns = groupIntoTurns(messages)
        val folded = messageIdToLazyIndex(turns)
        assertEquals(2, folded["a1"])
        assertEquals(3, folded["a2"])
        assertEquals(5, folded["next"])
        val opened = messageIdToLazyIndex(turns, leadingItems = 1, expandedTurns = setOf("a1"))
        assertEquals(5, opened["a1"])
        assertEquals(7, opened["a2"])
        assertEquals(9, opened["next"])
        assertEquals("a1", (turns[1] as ChatTurn.Agent).tabKey())
    }
}
