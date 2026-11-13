package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.data.ws.EventParser
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.ScopedRpcSubmission
import com.m57.hermescontrol.data.ws.ScopedWsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.domain.hermes.AttentionCoordinator
import com.m57.hermescontrol.domain.hermes.AttentionRepository
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalChoiceParityTest {
    private val scope = ConnectionScope("server", "connection", "profile", "process", 1)
    private val events = MutableSharedFlow<ScopedWsEvent>()
    private val repository = AttentionRepository()
    private val source =
        object : HermesAttentionSource {
            override val attentionSignals = normalizeDashboardAttention(events)
        }
    private val connection = mockk<HermesSubmissionTransport>()
    private val transport = DashboardRpcTransport()
    private val coordinator = AttentionCoordinator(repository, source, transport, connection)

    @Before
    fun setup() {
        mockkObject(HermesWsClient)
        every { HermesWsClient.matchesCurrentScope(scope) } returns true
        every { connection.configuredScope() } returns scope
        every { connection.isReady(scope) } returns true
        every { HermesWsClient.writeScoped(scope, any(), any(), any()) } returns
            ScopedRpcSubmission("rpc", true, CompletableDeferred(Json.parseToJsonElement("""{"resolved":1}""")))
    }

    @After
    fun cleanup() = unmockkAll()

    @Test
    fun missingChoicesNeverInferBroaderPermissionFromFlagsInLiveOrSnapshot() {
        val payload =
            mapOf(
                "request_id" to "legacy",
                "allow_session" to true,
                "allow_permanent" to true,
            )
        val live =
            EventParser.parseParams(
                mapOf("type" to "approval.request", "session_id" to "runtime", "payload" to payload),
            )
        val snapshot =
            com.m57.hermescontrol.data.ws.WsEvent.SessionInfo(
                mapOf("session_id" to "runtime", "pending_approval" to payload),
            )
        for (event in listOf(live, snapshot)) {
            assertEquals(
                listOf("once", "deny"),
                dashboardAttentionItems(scope, event).single().details?.approvalChoices,
            )
        }
    }

    @Test
    fun serverRestrictionsAreAppliedBeforeRepositoryObservation() =
        runTest {
            coordinator.start(backgroundScope)
            runCurrent()
            val cases =
                listOf(
                    mapOf("smart_denied" to true) to listOf("once", "deny"),
                    mapOf("allow_permanent" to false) to listOf("once", "session", "deny"),
                    mapOf("allow_session" to false) to listOf("once", "deny"),
                )
            for ((index, case) in cases.withIndex()) {
                val (flags, expected) = case
                val event =
                    EventParser.parseApprovalRequest(
                        flags +
                            mapOf(
                                "request_id" to "restricted:$index",
                                "choices" to listOf("once", "session", "always", "deny", "unknown"),
                            ),
                        "runtime",
                    )
                events.emit(ScopedWsEvent(scope, index + 1L, event))
                runCurrent()
                assertEquals(
                    expected,
                    repository.items.value
                        .first {
                            it.ref.requestId == "restricted:$index"
                        }.details
                        ?.approvalChoices,
                )
            }
            verify(exactly = 0) { HermesWsClient.writeScoped(any(), any(), any(), any()) }
        }

    @Test
    fun explicitlyAdvertisedBroaderChoicesResolveOnlyTheirExactRequestOnce() =
        runTest {
            coordinator.start(backgroundScope)
            runCurrent()
            for ((index, choice) in listOf("session", "always").withIndex()) {
                val requestId = "approval:$choice"
                val event =
                    EventParser.parseParams(
                        mapOf(
                            "type" to "approval.request",
                            "session_id" to "runtime:exact",
                            "payload" to
                                mapOf(
                                    "request_id" to requestId,
                                    "choices" to listOf("once", "session", "always", "deny"),
                                ),
                        ),
                    )
                events.emit(ScopedWsEvent(scope, index + 1L, event))
                runCurrent()
                val item = repository.items.value.first { it.ref.requestId == requestId }
                assertEquals(listOf("once", "session", "always", "deny"), item.details?.approvalChoices)
                verify(exactly = index) {
                    HermesWsClient.writeScoped(scope, WsMethods.APPROVAL_RESPOND, any(), any())
                }
                coordinator.respondApproval(item.ref, choice)
                coordinator.respondApproval(item.ref, choice)
                assertEquals(
                    AttentionStatus.CONFIRMED,
                    repository.items.value
                        .first { it.ref == item.ref }
                        .status,
                )
                verify(exactly = 1) {
                    HermesWsClient.writeScoped(
                        scope,
                        WsMethods.APPROVAL_RESPOND,
                        mapOf(
                            "session_id" to "runtime:exact",
                            "request_id" to requestId,
                            "choice" to choice,
                            "all" to false,
                        ),
                        any(),
                    )
                }
            }
        }
}
