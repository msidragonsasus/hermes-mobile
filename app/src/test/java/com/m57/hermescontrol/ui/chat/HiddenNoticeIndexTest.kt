package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns
import com.m57.hermescontrol.ui.chat.fullbleed.messageIdToLazyIndex
import com.m57.hermescontrol.ui.chat.fullbleed.tabKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HiddenNoticeIndexTest {
    private fun notice(
        id: String,
        text: String = "Connected to Hermes",
    ) = ChatMessage(id = id, role = MessageRole.SYSTEM, content = text)

    @Test
    fun hiddenNoticesDoNotConsumeLazyItemsOrMapToAnotherMessage() {
        val turns = groupIntoTurns(listOf(notice("c"), notice("r", "Session resumed")))
        assertEquals(emptyMap<String, Int>(), messageIdToLazyIndex(turns))
    }

    @Test
    fun contentOnEitherSideOfMultipleNoticesHasActualVisibleIndices() {
        val turns =
            groupIntoTurns(
                listOf(
                    ChatMessage(id = "a", role = MessageRole.ASSISTANT, content = "before"),
                    notice("c"),
                    notice("r", "Session resumed"),
                    ChatMessage(id = "u", role = MessageRole.USER, content = "after"),
                    notice("c2"),
                    ChatMessage(id = "a2", role = MessageRole.ASSISTANT, content = "reply"),
                ),
            )
        assertEquals(mapOf("a" to 1, "u" to 2, "a2" to 3), messageIdToLazyIndex(turns, leadingItems = 1))
    }

    @Test
    fun errorsAndApprovalRequestsRemainMappedEvenWithSameRoutineText() {
        val turns =
            groupIntoTurns(
                listOf(
                    notice("c"),
                    notice("error", "Connection failed: rejected"),
                    notice("approval", "Session resumed").copy(approvalInfo = ApprovalInfo(null, null, null)),
                ),
            )
        val indexes = messageIdToLazyIndex(turns)
        assertEquals(mapOf("error" to 0, "approval" to 1), indexes)
        assertFalse(indexes.containsKey("c"))
    }

    @Test
    fun hiddenNoticesDoNotShiftContentWhenActivityOpensOrCloses() {
        val turns =
            groupIntoTurns(
                listOf(
                    notice("c"),
                    ChatMessage(
                        id = "tool",
                        role = MessageRole.TOOL,
                        content = "{}",
                        toolStatus = ToolStatus.COMPLETED,
                    ),
                    notice("r", "Session resumed"),
                    ChatMessage(id = "a", role = MessageRole.ASSISTANT, content = "result"),
                ),
            )
        val turn = turns.single() as ChatTurn.Agent
        assertEquals(mapOf("a" to 1), messageIdToLazyIndex(turns))
        assertEquals(mapOf("a" to 2), messageIdToLazyIndex(turns, expandedTurns = setOf(turn.tabKey())))
    }
}
