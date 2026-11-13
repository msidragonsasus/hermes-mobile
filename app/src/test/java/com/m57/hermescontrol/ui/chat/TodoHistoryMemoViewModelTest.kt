package com.m57.hermescontrol.ui.chat

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.PaginationInfo
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.model.SessionMessagesResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.ui.chat.fakes.FakeChatPersistenceRepository
import com.m57.hermescontrol.ui.chat.fakes.FakeSubmissionTransport
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class TodoHistoryMemoViewModelTest {
    @Test
    fun newHistoryIdentityRehydratesSameIdEditsAndClearedTodos() =
        withVm { fixture ->
            val original = todoMessage("old")
            fixture.install(listOf(original))
            fixture.token()
            assertEquals(listOf(TodoItem("task", "old", "pending")), fixture.state.value.todos)
            fixture.state.value = fixture.state.value.copy(todos = emptyList())
            fixture.token()
            assertEquals(listOf(TodoItem("task", "old", "pending")), fixture.state.value.todos)

            val edited = listOf(original.copy(content = todoMessage("edited").content))
            fixture.state.value = fixture.state.value.copy(messages = edited, todos = emptyList())
            fixture.token()
            assertSame(edited, fixture.state.value.messages)
            assertEquals(listOf(TodoItem("task", "edited", "pending")), fixture.state.value.todos)
            assertSame(edited, fixture.retainedHistory())
        }

    @Test
    fun wrongSessionAndAuthoritativeTodosKeepStateAndDoNotWarmTheMemo() =
        withVm { fixture ->
            fixture.install(listOf(todoMessage("historical")))
            val before = fixture.state.value
            val streaming = fixture.streaming.value
            fixture.token("other")
            assertEquals(before, fixture.state.value)
            assertEquals(streaming, fixture.streaming.value)
            assertNull(fixture.retainedHistory())

            val authoritative = listOf(TodoItem("live", "authoritative", "completed"))
            fixture.state.value = before.copy(todos = authoritative)
            fixture.token()
            assertSame(authoritative, fixture.state.value.todos)
            assertEquals(before.copy(todos = authoritative, responseStatusUnknown = false), fixture.state.value)
            assertNull(fixture.retainedHistory())
        }

    @Test
    fun selectionAtoBtoAReleasesHistoryAndRequiresFreshHydration() =
        withVm { fixture ->
            val history = listOf(todoMessage("old"))
            fixture.install(history)
            fixture.token()
            fixture.vm.switchSession("session-b")
            assertEquals("session-b", fixture.state.value.currentSessionId)
            fixture.assertReleased()
            fixture.vm.switchSession("session-a")
            assertEquals("session-a", fixture.state.value.currentSessionId)
            fixture.assertReleased()
            fixture.install(history)
            fixture.token()
            assertEquals(listOf(TodoItem("task", "old", "pending")), fixture.state.value.todos)
            assertSame(history, fixture.retainedHistory())
        }

    @Test
    fun undoGenerationReleasesTheMemoWithoutClearingExistingTodos() =
        withVm { fixture ->
            fixture.install(listOf(todoMessage("old")))
            fixture.token()
            val todos = fixture.state.value.todos
            val generation = field<Long>(fixture.vm, "sessionGeneration")
            call(fixture.vm, "handlePrefillResult", "restored prompt", "rewound")
            assertEquals(generation + 1, field<Long>(fixture.vm, "sessionGeneration"))
            assertEquals(emptyList<ChatMessage>(), fixture.state.value.messages)
            assertSame(todos, fixture.state.value.todos)
            fixture.assertReleased()
        }

    @Test
    fun sameSessionReconnectPreservesStateWhileTheNewScopeReleasesTheMemo() =
        withVm { fixture ->
            val scopes = MutableStateFlow<ConnectionScope?>(fixture.submission.scope)
            every { HermesWsClient.connectionScope } returns scopes
            fixture.scheduler.runCurrent()
            fixture.vm.switchSession("session-a")
            fixture.install(listOf(todoMessage("old")))
            fixture.token()
            val history = fixture.state.value.messages
            val todos = fixture.state.value.todos
            fixture.submission.scope =
                fixture.submission.scope.copy(generation = fixture.submission.scope.generation + 1)
            scopes.value = fixture.submission.scope
            fixture.scheduler.runCurrent()
            val state = fixture.state.value
            fixture.vm.switchSession("session-a")
            assertEquals(state, fixture.state.value)
            assertEquals(history, fixture.state.value.messages)
            assertSame(todos, fixture.state.value.todos)
            fixture.assertReleased()
        }

    @Test
    fun sameSavedSessionOnAnotherTargetReleasesMemoAndResetsTheSelection() =
        withVm { fixture ->
            fixture.vm.switchSession("session-a")
            fixture.install(listOf(todoMessage("old")))
            fixture.token()
            val generation = field<Long>(fixture.vm, "sessionGeneration")
            fixture.submission.scope = fixture.submission.scope.copy(serverProfileId = "other-profile")
            fixture.vm.switchSession("session-a")
            assertEquals("session-a", fixture.state.value.currentSessionId)
            assertEquals(generation + 1, field<Long>(fixture.vm, "sessionGeneration"))
            assertEquals(emptyList<ChatMessage>(), fixture.state.value.messages)
            fixture.assertReleased()
        }

    @Test
    fun connectionScopeEmissionReleasesMemoWithoutChangingTodos() =
        withVm { fixture ->
            val scopes = MutableStateFlow<ConnectionScope?>(fixture.submission.scope)
            every { HermesWsClient.connectionScope } returns scopes
            fixture.scheduler.runCurrent()
            fixture.install(listOf(todoMessage("old")))
            fixture.token()
            val todos = fixture.state.value.todos
            scopes.value = fixture.submission.scope.copy(generation = fixture.submission.scope.generation + 1)
            fixture.scheduler.runCurrent()
            assertSame(todos, fixture.state.value.todos)
            fixture.assertReleased()
        }

    @Test
    fun gatewayReadyReleasesMemoEvenIfTheScopeHasNotEmittedYet() =
        withVm { fixture ->
            fixture.vm.switchSession("session-a")
            fixture.install(listOf(todoMessage("old")))
            fixture.token()
            val todos = fixture.state.value.todos
            call(fixture.vm, "handleGatewayReady")
            assertEquals("session-a", fixture.state.value.currentSessionId)
            assertSame(todos, fixture.state.value.todos)
            fixture.assertReleased()
        }

    @Test
    fun viewModelClearReleasesRetainedHistoryAndTodos() =
        withVm { fixture ->
            fixture.install(listOf(todoMessage("old")))
            fixture.token()
            val state = fixture.state.value
            ViewModelStore().apply {
                put("chat", fixture.vm)
                clear()
            }
            assertEquals(state, fixture.state.value)
            fixture.assertReleased()
        }

    @Test
    fun cacheRestoreHydratesThroughTheSameMemoUsedByEvents() {
        for (withTodos in listOf(false, true)) {
            withVm { fixture ->
                val history = listOf(historyMessage("cached", withTodos))
                val expected = hydrateTodosFromMessages(history)
                runBlocking {
                    field<FakeChatPersistenceRepository>(fixture.harness, "fakeRepo")
                        .persistMessages(history, "session-a")
                }
                fixture.vm.switchSession("session-a")
                fixture.scheduler.runCurrent()
                val state = fixture.state.value
                assertEquals(expected, state.todos)
                assertEquals(history.single().content, state.messages.single().content)
                assertSame(state.messages, fixture.retainedHistory())
                fixture.state.value = state.copy(todos = emptyList())
                fixture.token()
                assertEquals(expected, fixture.state.value.todos)
                assertSame(state.messages, fixture.retainedHistory())
            }
        }
    }

    @Test
    fun restHydrationReplacesTodosAndWarmsTheEventMemo() {
        for (withTodos in listOf(false, true)) {
            withVm { fixture ->
                fixture.vm.switchSession("session-a")
                fixture.install(listOf(historyMessage("old", withTodos)))
                fixture.token()
                // REST replaces authoritative live todos even when the restored result is empty.
                fixture.state.value = fixture.state.value.copy(todos = listOf(TodoItem("live", "live", "completed")))
                val restored = historyMessage("rest", withTodos)
                val server = SessionMessage(id = 1, role = "tool", content = JsonPrimitive(restored.content))
                coEvery { ApiClient.hermesApi.getSessionMessages("session-a", any(), any(), any(), any()) } returns
                    Response.success(
                        SessionMessagesResponse(
                            messages = listOf(server),
                            pagination = PaginationInfo(order = "latest", offset = 0, returned = 1),
                        ),
                    )
                fixture.scheduler.runCurrent()
                val state = fixture.state.value
                val expected = hydrateTodosFromMessages(listOf(restored))
                assertEquals(expected, state.todos)
                assertEquals(restored.content, state.messages.last().content)
                assertSame(state.messages, fixture.retainedHistory())
                fixture.state.value = state.copy(todos = emptyList())
                fixture.token()
                assertEquals(expected, fixture.state.value.todos)
            }
        }
    }

    private fun historyMessage(
        content: String,
        withTodos: Boolean,
    ) = if (withTodos) todoMessage(content) else todoMessage(content).copy(content = content)

    private class Fixture(
        val harness: ChatViewModelTest,
        val vm: ChatViewModel,
    ) {
        val state = field<MutableStateFlow<ChatUiState>>(vm, "_uiState")
        val streaming = field<MutableStateFlow<StreamingState>>(vm, "_streamingState")
        val memo = field<TodoHistoryMemo>(vm, "todoHistoryMemo")
        val submission = field<FakeSubmissionTransport>(harness, "fakeSubmission")
        val scheduler = field<TestDispatcher>(harness, "testDispatcher").scheduler

        fun install(history: List<ChatMessage>) {
            state.value =
                ChatUiState(
                    currentSessionId = "session-a",
                    messages = history,
                    isAgentTyping = true,
                    responseStatusUnknown = true,
                )
            streaming.value =
                StreamingState(
                    streamingMessage =
                        ChatMessage(
                            id = "live",
                            role = MessageRole.ASSISTANT,
                            content = "",
                            timestamp = 0,
                        ),
                )
        }

        fun token(session: String = "session-a") {
            call(vm, "handleWsEvent", WsEvent.MessageToken("text", session), 1001L, 1L)
        }

        fun retainedHistory(): List<ChatMessage>? = field(memo, "messages")

        fun assertReleased() {
            assertNull(retainedHistory())
            assertEquals(emptyList<TodoItem>(), field<List<TodoItem>>(memo, "todos"))
        }
    }

    private fun withVm(block: (Fixture) -> Unit) {
        val harness = ChatViewModelTest()
        harness.setUp()
        var vm: ChatViewModel? = null
        try {
            val created = call(harness, "createViewModel", false) as ChatViewModel
            vm = created
            block(Fixture(harness, created))
        } finally {
            vm?.viewModelScope?.cancel()
            harness.tearDown()
        }
    }

    private fun todoMessage(content: String) =
        ChatMessage(
            id = "same-id",
            role = MessageRole.TOOL,
            toolName = "todo",
            content = "{\"todos\":[{\"id\":\"task\",\"content\":\"$content\",\"status\":\"pending\"}]}",
            timestamp = 0,
        )

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun <T> field(
            owner: Any,
            name: String,
        ): T =
            owner.javaClass
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(owner) as T

        private fun call(
            owner: Any,
            name: String,
            vararg args: Any,
        ): Any? =
            owner.javaClass.declaredMethods
                .single { it.name == name && it.parameterCount == args.size }
                .apply { isAccessible = true }
                .invoke(owner, *args)
    }
}
