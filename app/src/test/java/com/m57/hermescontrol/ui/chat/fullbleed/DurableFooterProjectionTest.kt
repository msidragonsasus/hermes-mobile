package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.ui.chat.*
import org.junit.Assert.*
import org.junit.Test

class DurableFooterProjectionTest {
    @Test fun distinctRepliesWithoutUserSeparatorKeepTheirOwnAnchorsAndSearchOffsets() {
        val a = ChatMessage(id = "a", role = MessageRole.ASSISTANT, content = "same")
        val b = a.copy(id = "b")
        val ledger =
            ResponseLedger(
                listOf(
                    ResponseRecord(
                        id = "r1",
                        messageIds = listOf("a"),
                        finishedAt = 11,
                        authority = acceptedAuthorityFixture(time = 11, members = listOf("a")),
                    ),
                    ResponseRecord(
                        id = "r2",
                        messageIds = listOf("b"),
                        finishedAt = 22,
                        authority = acceptedAuthorityFixture(time = 22, members = listOf("b")),
                    ),
                ),
            )
        val turns = groupIntoTurnsWithStreaming(listOf(a, b), null, ledger)
        val footers = turns.replyFooters(ReplyLifecycle(ledger = ledger))
        assertEquals(2, turns.size)
        assertEquals(listOf(ReplyFooter("same", 11, "r1"), ReplyFooter("same", 22, "r2")), footers)
        assertEquals(mapOf("a" to 0, "b" to 2), messageIdToLazyIndex(turns, replyFooters = footers))
    }

    @Test fun footerIsAtLastOwnedToolOnlyGroupWithoutIncludingToolText() {
        val messages =
            listOf(
                ChatMessage(id = "a", role = MessageRole.ASSISTANT, content = "same\n"),
                ChatMessage(id = "u", role = MessageRole.USER, content = "pending"),
                ChatMessage(id = "t", role = MessageRole.TOOL, content = "not copied"),
            )
        val ledger =
            ResponseLedger(
                listOf(
                    ResponseRecord(
                        id = "r",
                        messageIds = listOf("a", "t"),
                        finishedAt = 77,
                        authority = acceptedAuthorityFixture(time = 77, members = listOf("a", "t")),
                    ),
                ),
            )
        val footers = groupIntoTurns(messages).replyFooters(ReplyLifecycle(ledger = ledger))
        assertEquals(listOf(null, null, ReplyFooter("same\n", 77, "r")), footers)
    }
}
