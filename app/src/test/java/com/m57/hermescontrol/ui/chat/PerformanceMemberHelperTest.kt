package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceMemberHelperTest {
    @Test
    fun unchangedIdentityNeedsNoTraversal() {
        val rows = CountingList(List(2048) { message("old-$it") })
        assertEquals(emptyList<String>(), newNonUserMemberIds(rows, rows))
        assertEquals(0, rows.reads)
    }

    @Test
    fun changedListsNeedOnlyOnePassEachAndKeepNewDuplicatesInOrder() {
        val old = CountingList(List(2048) { message("old-$it") } + message("user-collision", MessageRole.USER))
        val next =
            CountingList(
                old.values.map { it.copy(content = "edited", role = MessageRole.TOOL) } +
                    listOf(
                        message("new-b"),
                        message("ignored-user", MessageRole.USER),
                        message("new-a", MessageRole.TOOL),
                        message("new-b", MessageRole.SYSTEM),
                    ),
            )
        val result = newNonUserMemberIds(old, next)
        val reads = old.reads + next.reads
        assertEquals(listOf("new-b", "new-a", "new-b"), result)
        assertEquals("One traversal of each input, not an old-list scan for each new row", old.size + next.size, reads)
    }

    @Test
    fun distinctEqualAndEmptyInputsPreserveMembershipSemantics() {
        val old = listOf(message("a"), message("u", MessageRole.USER))
        assertEquals(emptyList<String>(), newNonUserMemberIds(old, old.map { it.copy() }))
        assertEquals(emptyList<String>(), newNonUserMemberIds(old, emptyList()))
        assertEquals(emptyList<String>(), newNonUserMemberIds(emptyList(), emptyList()))
        assertEquals(
            listOf("a", "a"),
            newNonUserMemberIds(emptyList(), listOf(message("a"), message("u", MessageRole.USER), message("a"))),
        )
    }

    private fun message(
        id: String,
        role: MessageRole = MessageRole.ASSISTANT,
    ) = ChatMessage(id = id, role = role, content = "本文", timestamp = 0)

    private class CountingList<T>(
        val values: List<T>,
    ) : AbstractList<T>() {
        var reads: Int = 0
            private set
        override val size: Int get() = values.size

        override fun get(index: Int): T {
            reads++
            return values[index]
        }
    }
}
