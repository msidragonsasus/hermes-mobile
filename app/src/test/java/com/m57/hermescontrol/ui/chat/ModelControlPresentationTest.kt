package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.domain.hermes.draftTargetKey
import com.m57.hermescontrol.ui.chat.fakes.FakeChatPersistenceRepository
import com.m57.hermescontrol.ui.chat.fakes.FakeSubmissionTransport
import com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry
import com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurnsWithStreaming
import com.m57.hermescontrol.ui.chat.fullbleed.timelineMarkerLabelRes
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Reuse existing fake wiring, without editing the shared ChatViewModelTest seam. */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelControlPresentationTest {
    private val fixture = ChatViewModelTest()

    @Before fun setUp() = fixture.setUp()

    @After fun tearDown() = fixture.tearDown()

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T =
        ChatViewModelTest::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(fixture) as T

    private suspend fun TestScope.session(): ChatViewModel {
        val vm =
            ChatViewModelTest::class.java
                .getDeclaredMethod("createViewModel", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(fixture, false) as ChatViewModel
        advanceUntilIdle()
        field<MutableStateFlow<ConnectionStatus>>("mockConnectionStatus").value = ConnectionStatus.CONNECTED
        val events = field<MutableSharedFlow<WsEvent>>("mockEventsFlow")
        events.emit(WsEvent.GatewayReady(null))
        advanceUntilIdle()
        events.emit(WsEvent.RpcResult("req-id-3", mapOf("session_id" to "session-123")))
        advanceUntilIdle()
        assertEquals("session-123", vm.uiState.value.currentSessionId)
        return vm
    }

    @Test
    fun unacknowledgedModelRequestDoesNotRenderSuccessfulSwitchLabel() =
        runTest {
            val vm = session()
            vm.sendSlashModel("openai", "gpt-4o")
            advanceUntilIdle()
            val row =
                vm.uiState.value.messages
                    .single { it.content == "/model gpt-4o --provider openai --session" }
            assertTrue("retain control provenance", row.displayKind != null)
            assertNull("a request is not proof of a completed switch", timelineMarkerLabelRes(row.displayKind))
            assertTrue(
                "authoritative server model switch retains its label",
                timelineMarkerLabelRes("model_switch") != null,
            )
        }

    @Test
    fun pickerSelectionRetainsTaggedCommandInMemoryPersistenceAndOneRunningGroup() =
        runTest {
            val vm = session()
            val before = vm.uiState.value.responseLedger
            vm.sendSlashModel("openai", "gpt-4o")
            advanceUntilIdle()
            val row =
                vm.uiState.value.messages
                    .single { it.content == "/model gpt-4o --provider openai --session" }
            assertEquals("local selection is a control, not a new prompt", "model_switch_request", row.displayKind)
            assertEquals(MessageRole.USER, row.role)
            assertEquals(before, vm.uiState.value.responseLedger)
            val scope = field<FakeSubmissionTransport>("fakeSubmission").scope.draftTargetKey()
            val saved =
                field<FakeChatPersistenceRepository>("fakeRepo").readTranscript(scope, "session-123").messages.single {
                    it.id ==
                        row.id
                }
            assertEquals(row.content, saved.content)
            assertEquals(row.displayKind, saved.displayKind)
            verify { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }

            // The exact produced row is fed into the real grouping function.
            val tool1 = ChatMessage(id = "tool1", role = MessageRole.TOOL, content = "first")
            val tool2 = tool1.copy(id = "tool2", content = "second")
            val stream = ChatMessage(id = "live", role = MessageRole.ASSISTANT, content = "", isStreaming = true)
            val ledger =
                ResponseLedger(
                    responses = listOf(ResponseRecord(id = "r", messageIds = listOf(tool1.id, tool2.id))),
                    activeId = "r",
                )
            val groups = groupIntoTurnsWithStreaming(listOf(tool1, row, tool2), stream, ledger)
            assertEquals(1, groups.size)
            assertEquals(
                listOf(
                    AgentEntry.ToolRow(tool1),
                    AgentEntry.SystemEvent(row),
                    AgentEntry.ToolRow(tool2),
                    AgentEntry.Prose(stream),
                ),
                (groups.single() as ChatTurn.Agent).entries,
            )
        }

    @Test
    fun typedModelSwitchUsesSameTagButOtherSlashCommandKeepsPromptBoundary() =
        runTest {
            val vm = session()
            val command = "/MODEL gpt-4o --provider openai --session"
            vm.sendMessage(command)
            advanceUntilIdle()
            assertEquals(
                "model_switch_request",
                vm.uiState.value.messages
                    .single { it.content == command }
                    .displayKind,
            )
            vm.sendMessage("/status")
            advanceUntilIdle()
            val status =
                vm.uiState.value.messages
                    .single { it.content == "/status" }
            assertNull(status.displayKind)
            assertTrue(groupIntoTurnsWithStreaming(listOf(status), null).single() is ChatTurn.User)
        }
}
