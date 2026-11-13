package com.m57.hermescontrol.ui.chat

import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PerformanceMemberDiscoveryTest {
    @Test
    fun realViewModelTokenDoesNotTraverseUnchangedMembership() {
        // Reuse the existing fully fake constructor dependencies. Reflection is
        // limited to that test harness and the existing real event/state entry points.
        val harness = ChatViewModelTest()
        harness.setUp()
        var vm: ChatViewModel? = null
        try {
            val created =
                ChatViewModelTest::class.java
                    .getDeclaredMethod("createViewModel", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(harness, false) as ChatViewModel
            vm = created
            val messages =
                CountingMessages(
                    List(512) { index ->
                        ChatMessage(
                            id = "history-$index",
                            role = MessageRole.ASSISTANT,
                            content = "本文-$index",
                            timestamp = 0,
                        )
                    },
                )
            val state =
                ChatUiState(
                    currentSessionId = "session-a",
                    messages = messages,
                    // Isolate membership from the independently demonstrated todo scan.
                    todos = listOf(TodoItem(id = "task", content = "keep", status = "pending")),
                    isAgentTyping = true,
                    responseStatusUnknown = true,
                )
            val streaming =
                StreamingState(
                    streamingMessage =
                        ChatMessage(
                            id = "live",
                            role = MessageRole.ASSISTANT,
                            content = "",
                            timestamp = 0,
                        ),
                )
            stateFlow<ChatUiState>(created, "_uiState").value = state
            stateFlow<StreamingState>(created, "_streamingState").value = streaming

            val before = messages.elementReads
            ChatViewModel::class.java
                .getDeclaredMethod(
                    "handleWsEvent",
                    WsEvent::class.java,
                    Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                ).apply { isAccessible = true }
                .invoke(created, WsEvent.MessageToken("文字", "session-a"), 1001L, 1L)
            val reads = messages.elementReads - before

            val actual = stateFlow<ChatUiState>(created, "_uiState").value
            assertSame(messages, actual.messages)
            assertEquals(state.copy(responseStatusUnknown = false), actual)
            assertEquals(
                streaming.copy(
                    streamingMessage = streaming.streamingMessage!!.copy(content = "文字"),
                    isWritingText = true,
                ),
                stateFlow<StreamingState>(created, "_streamingState").value,
            )
            assertEquals("An ordinary real ViewModel token must not traverse unchanged historical membership", 0, reads)
        } finally {
            vm?.viewModelScope?.cancel()
            harness.tearDown()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> stateFlow(
        vm: ChatViewModel,
        field: String,
    ): MutableStateFlow<T> =
        ChatViewModel::class.java
            .getDeclaredField(field)
            .apply { isAccessible = true }
            .get(vm) as MutableStateFlow<T>

    private class CountingMessages(
        private val values: List<ChatMessage>,
    ) : AbstractList<ChatMessage>() {
        var elementReads: Int = 0
            private set

        override val size: Int get() = values.size

        override fun get(index: Int): ChatMessage {
            elementReads++
            return values[index]
        }
    }
}
