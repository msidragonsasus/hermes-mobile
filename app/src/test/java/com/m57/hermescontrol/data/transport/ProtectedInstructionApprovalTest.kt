package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.data.ws.EventParser
import com.m57.hermescontrol.data.ws.ScopedWsEvent
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.domain.hermes.AttentionCoordinator
import com.m57.hermescontrol.domain.hermes.AttentionRepository
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.SubmissionResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payload shape from tools.file_tools_write_guards + tui_gateway.server, not a tool execution. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtectedInstructionApprovalTest {
    private val scope = ConnectionScope("server", "connection", "profile", "process", 1)
    private val payload =
        mapOf(
            "request_id" to "protected-write-1",
            "command" to "<write to SOUL.md, AGENTS.md>",
            "description" to
                "Write to protected agent-instruction file(s): SOUL.md, AGENTS.md. " +
                "These files steer future agent behavior; approval is always required (not bypassed by auto-approve).",
            "pattern_keys" to listOf("protected_instruction_file"),
            "allow_permanent" to false,
            "allow_session" to false,
        )

    @Test
    fun liveAndReplayKeepTheSameOperationTargetsAndOnceOnlyChoices() {
        val live =
            EventParser.parseParams(
                mapOf(
                    "type" to "approval.request",
                    "session_id" to "runtime",
                    "payload" to payload,
                ),
            )
        val replay = EventParser.parseApprovalRequest(payload, "runtime")
        assertEquals(live, replay)
        assertEquals(listOf("once", "deny"), replay.choices)
        val item = dashboardAttentionItems(scope, live).single()
        assertEquals(payload["description"], item.title)
        assertEquals(payload["command"], item.details?.action)
        assertEquals("protected-write-1", item.ref.requestId)
        // The guard currently supplies file names inside the command, not structured paths or expiry.
        assertEquals(null, item.details?.target)
        assertEquals(null, item.expiresAt)
    }

    @Test
    fun sessionSnapshotRestoresApprovalOutsideChatWithExactOuterSession() =
        runTest {
            val events = MutableSharedFlow<ScopedWsEvent>()
            val repository = AttentionRepository()
            val transport = mockk<HermesAttentionTransport>()
            val connection = mockk<HermesSubmissionTransport>()
            every { connection.configuredScope() } returns scope
            every { connection.isReady(scope) } returns true
            coEvery { transport.respondApproval(any(), any()) } returns SubmissionResult.Confirmed("rpc", true)
            val source =
                object : HermesAttentionSource {
                    override val attentionSignals = normalizeDashboardAttention(events)
                }
            val coordinator = AttentionCoordinator(repository, source, transport, connection)
            coordinator.start(backgroundScope)
            runCurrent()
            val snapshot = WsEvent.SessionInfo(mapOf("session_id" to "runtime", "pending_approval" to payload))
            events.emit(ScopedWsEvent(scope, 1, snapshot))
            runCurrent()
            val item = repository.items.value.single()
            assertEquals("runtime", item.ref.session.runtimeId)
            assertEquals("protected-write-1", item.ref.requestId)
            coVerify(exactly = 0) { transport.respondApproval(any(), any()) }
            coordinator.respondApproval(item.ref, "always")
            coordinator.respondApproval(item.ref, "once")
            coordinator.respondApproval(item.ref, "once")
            events.emit(ScopedWsEvent(scope, 2, snapshot))
            runCurrent()
            assertEquals(
                AttentionStatus.CONFIRMED,
                repository.items.value
                    .single()
                    .status,
            )
            coVerify(exactly = 1) { transport.respondApproval(item.ref, "once") }
        }

    @Test
    fun incompleteSnapshotsCannotCreateAnActionableRequest() {
        for (snapshot in listOf(
            mapOf("pending_approval" to payload),
            mapOf("session_id" to " ", "pending_approval" to payload),
            mapOf("session_id" to "runtime", "pending_approval" to payload.minus("request_id")),
        )) {
            assertTrue(dashboardAttentionItems(scope, WsEvent.SessionInfo(snapshot)).isEmpty())
        }
    }

    @Test
    fun malformedAdvertisedChoicesNeverFallBackToPermission() {
        for (choices in listOf(null, "once", 1, mapOf("once" to true))) {
            val event = EventParser.parseApprovalRequest(payload + ("choices" to choices), "runtime")
            assertEquals(emptyList<String>(), event.choices)
        }
    }
}
