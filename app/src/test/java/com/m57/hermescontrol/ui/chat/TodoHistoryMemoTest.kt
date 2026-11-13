package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TodoHistoryMemoTest {
    @Test
    fun emptyAndNonemptyOutcomesReuseOnlyTheLastInputIdentity() {
        val memo = TodoHistoryMemo()
        val empty = CountingHistory(listOf(message("plain", "text", MessageRole.ASSISTANT)))
        val populated = CountingHistory(listOf(todoMessage("tool", "first")))
        for (history in listOf(empty, populated, empty)) {
            val expected = hydrateTodosFromMessages(history)
            val before = history.reads
            val actual = memo.hydrate(history)
            assertEquals(expected, actual)
            assertEquals(before + 1, history.reads)
            val warm = history.reads
            assertSame(actual, memo.hydrate(history))
            assertEquals(warm, history.reads)
        }
    }

    @Test
    fun equalButDistinctListsAreAlwaysHydrated() {
        val memo = TodoHistoryMemo()
        val first = CountingHistory(listOf(todoMessage("same", "first")))
        val second = CountingHistory(listOf(todoMessage("same", "first")))
        assertEquals(first, second)
        assertNotSame(first, second)
        memo.hydrate(first)
        val before = second.reads
        assertEquals(listOf(TodoItem("task", "first", "pending")), memo.hydrate(second))
        assertEquals(before + 1, second.reads)
    }

    @Test
    fun editsReorderingAdditionAndDeletionKeepTheExistingHydrationSemantics() {
        val memo = TodoHistoryMemo()
        val old = todoMessage("same-id", "old")
        val edited = old.copy(content = todoMessage("ignored", "edited").content)
        val newer = todoMessage("newer", "newer")
        val malformed = message("bad", "{\"todos\":invalid}")
        val emptyTodos = message("empty", "{\"todos\":[]}")
        val versions =
            listOf(
                listOf(old),
                listOf(edited),
                listOf(edited, newer),
                listOf(newer, edited),
                listOf(newer),
                listOf(newer, malformed, emptyTodos),
                emptyList(),
            )
        val expected = listOf("old", "edited", "newer", "edited", "newer", "newer", null)
        versions.zip(expected).forEach { (rows, content) ->
            val history = CountingHistory(rows)
            val actual = memo.hydrate(history)
            assertEquals(hydrateTodosFromMessages(rows), actual)
            assertEquals(content, actual.singleOrNull()?.content)
            val before = history.reads
            assertSame(actual, memo.hydrate(history))
            assertEquals(before, history.reads)
        }
    }

    @Test
    fun clearReleasesBothRetainedValuesAndRehydratesTheSameList() {
        val memo = TodoHistoryMemo()
        val history = CountingHistory(listOf(todoMessage("tool", "first")))
        val todos = memo.hydrate(history)
        memo.clear()
        assertNull(
            TodoHistoryMemo::class.java
                .getDeclaredField("messages")
                .apply { isAccessible = true }
                .get(memo),
        )
        assertEquals(
            emptyList<TodoItem>(),
            TodoHistoryMemo::class.java
                .getDeclaredField("todos")
                .apply { isAccessible = true }
                .get(memo),
        )
        val before = history.reads
        val actual = memo.hydrate(history)
        assertEquals(todos, actual)
        assertNotSame(todos, actual)
        assertEquals(before + 1, history.reads)
    }

    @Test
    fun reducerRejectsWrongSessionsBeforeCallingTheOptionalHydrator() {
        val state = ChatUiState(messages = listOf(todoMessage("tool", "first")))
        val streaming = StreamingState()
        val result =
            ChatWsEventReducer.reduce(
                state,
                streaming,
                WsEvent.MessageToken("wrong", "other"),
                "selected",
                hydrateTodos = { error("Wrong-session events must not hydrate") },
            )
        assertSame(state, result.state)
        assertSame(streaming, result.streamingState)
    }

    @Test
    fun reducerPreservesAuthoritativeTodosAndDoesNotHydrateAnEmptyHistory() {
        val authoritative = listOf(TodoItem("authoritative", "live", "completed"))
        for (state in listOf(
            ChatUiState(messages = listOf(todoMessage("tool", "old")), todos = authoritative),
            ChatUiState(),
        )) {
            val event = WsEvent.MessageToken("text", "selected")
            val expected = ChatWsEventReducer.reduce(state, StreamingState(), event, "selected")
            val actual =
                ChatWsEventReducer.reduce(
                    state,
                    StreamingState(),
                    event,
                    "selected",
                    hydrateTodos = { error("Hydration condition must remain unchanged") },
                )
            assertEquals(expected, actual)
            assertSame(state.todos, actual.state.todos)
        }
    }

    @Test
    fun optionalHydratorReceivesTheReducedHistoryAndMatchesThePureDefault() {
        val history =
            listOf(
                todoMessage("tool", "first"),
                ChatMessage(
                    id = "running",
                    role = MessageRole.TOOL,
                    content = "",
                    toolName = "terminal",
                    toolStatus = ToolStatus.RUNNING,
                    timestamp = 0,
                ),
            )
        val state = ChatUiState(messages = history, isAgentTyping = true)
        val streaming = StreamingState()
        val event = WsEvent.ToolComplete("terminal", mapOf("output" to "done"), "selected")
        var observed: List<ChatMessage>? = null
        val expected = ChatWsEventReducer.reduce(state, streaming, event, "selected")
        val actual =
            ChatWsEventReducer.reduce(state, streaming, event, "selected") {
                observed = it
                hydrateTodosFromMessages(it)
            }
        assertEquals(expected, actual)
        assertSame(actual.state.messages, observed)
        assertNotSame(history, observed)
        assertEquals(ToolStatus.COMPLETED, observed!!.last().toolStatus)
        assertEquals("{\"output\":\"done\"}", observed.last().content)
    }

    private fun message(
        id: String,
        content: String,
        role: MessageRole = MessageRole.TOOL,
    ) = ChatMessage(id = id, role = role, content = content, toolName = "todo", timestamp = 0)

    private fun todoMessage(
        id: String,
        content: String,
    ) = message(id, "{\"todos\":[{\"id\":\"task\",\"content\":\"$content\",\"status\":\"pending\"}]}")

    private class CountingHistory(
        private val rows: List<ChatMessage>,
    ) : AbstractList<ChatMessage>() {
        var reads = 0
            private set
        override val size: Int get() = rows.size

        override fun get(index: Int): ChatMessage {
            reads++
            return rows[index]
        }
    }
}
