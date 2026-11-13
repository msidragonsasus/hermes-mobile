package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.transport.DashboardRpcTransport
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.HermesObservation
import com.m57.hermescontrol.domain.hermes.ObservationKind
import com.m57.hermescontrol.domain.hermes.PromptAcceptance
import com.m57.hermescontrol.domain.hermes.PromptEvidence
import com.m57.hermescontrol.domain.hermes.SessionRef
import com.m57.hermescontrol.domain.hermes.SubmissionResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Test-only boundary injection. No production class, registry or clock is replaced. */
class RpcTimeoutLateAckTest {
    private val activeProfile = MutableStateFlow<String?>("synthetic-profile-a")
    private val frames = mutableListOf<String>()
    private lateinit var socket: WebSocket
    private val session = SessionRef("synthetic-runtime", "synthetic-saved")
    private val baseUrl = "https://synthetic.invalid/never-used"

    private fun field(name: String) = HermesWsClient::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun pending(): Map<*, *> = field("pendingCalls").get(HermesWsClient) as Map<*, *>

    private fun timer(id: String): Job {
        val call = pending()[id] ?: error("real pending entry absent: $id")
        return call.javaClass
            .getDeclaredField("timeoutJob")
            .apply { isAccessible = true }
            .get(call) as Job
    }

    private fun queueSize(): Int = (field("messageQueue").get(HermesWsClient) as Collection<*>).size

    private fun id(index: Int = frames.lastIndex): String =
        Json
            .parseToJsonElement(frames[index])
            .jsonObject["id"]!!
            .jsonPrimitive.content

    private fun evidence(
        case: String,
        detail: String,
    ) = println(
        "EVIDENCE " +
            buildJsonObject {
                put("case", case)
                put("detail", detail)
                put("nanoTime", System.nanoTime())
            },
    )

    private fun ack(
        listener: WebSocketListener,
        target: WebSocket,
        request: String,
    ) = listener.onMessage(
        target,
        """{"jsonrpc":"2.0","id":"$request","result":{"status":"streaming","user_row_id":41,"submission_id":"synthetic-submission"}}""",
    )

    /** Same socket/listener fixture seam as existing HermesWsClientTest; never connect(). */
    private fun bind(target: WebSocket = socket): Pair<ConnectionScope, WebSocketListener> {
        val generation = (field("connectionGeneration").get(HermesWsClient) as AtomicInteger).get()
        val scope =
            ConnectionScope(
                "synthetic-server",
                "synthetic-connection",
                activeProfile.value,
                "synthetic-process",
                generation,
            )
        field("webSocket").set(HermesWsClient, target)
        (field("connected").get(HermesWsClient) as AtomicBoolean).set(true)
        (field("intentionalClose").get(HermesWsClient) as AtomicBoolean).set(false)
        field("connectionBaseUrl").set(HermesWsClient, baseUrl)
        @Suppress("UNCHECKED_CAST")
        (field("_connectionScope").get(HermesWsClient) as MutableStateFlow<ConnectionScope?>).value = scope
        @Suppress("UNCHECKED_CAST")
        (field("_connectionStatus").get(HermesWsClient) as MutableStateFlow<ConnectionStatus>).value =
            ConnectionStatus.CONNECTED
        val constructor =
            Class
                .forName("com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl")
                .declaredConstructors
                .single()
                .apply { isAccessible = true }
        return scope to constructor.newInstance(generation, scope) as WebSocketListener
    }

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(AuthManager)
        every { AuthManager.getBaseUrl() } returns baseUrl
        every { AuthManager.getSelectedProfileId() } returns "synthetic-connection"
        every { AuthManager.activeProfileId } returns activeProfile
        every { AuthManager.isAutoReconnect() } returns false
        HermesWsClient.disconnect(clearPendingMessages = true)
        HermesWsClient.rejectAllPending()
        HermesWsClient.setAppForeground(true)
        socket = mockk(relaxed = true)
        every { socket.send(any<String>()) } answers {
            frames.add(firstArg())
            true
        }
        assertTrue(pending().isEmpty())
    }

    @After fun cleanup() {
        HermesWsClient.disconnect(clearPendingMessages = true)
        HermesWsClient.rejectAllPending()
        unmockkAll()
    }

    @Test fun positiveControl_onlyExactRequestAckConfirms() =
        runBlocking {
            val (scope, listener) = bind()
            val transport = DashboardRpcTransport()
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    transport.submitPrompt(scope, session, "synthetic positive control", false, false)
                }
            val request = id()
            assertEquals(
                WsMethods.PROMPT_SUBMIT,
                Json
                    .parseToJsonElement(frames.single())
                    .jsonObject["method"]!!
                    .jsonPrimitive.content,
            )
            val params = Json.parseToJsonElement(frames.single()).jsonObject["params"]!!.jsonObject
            assertEquals(session.runtimeId, params["session_id"]!!.jsonPrimitive.content)
            assertEquals(scope.serverProfileId, params["profile"]!!.jsonPrimitive.content)
            val timeoutJob = timer(request)
            assertTrue(timeoutJob.isActive)
            ack(listener, socket, "wrong-synthetic-request")
            assertFalse(result.isCompleted)
            assertTrue(pending().containsKey(request))
            delay(200)
            ack(listener, socket, request)
            assertEquals(
                SubmissionResult.Confirmed(
                    request,
                    PromptAcceptance.STREAMING,
                    PromptEvidence(41L, "synthetic-submission"),
                ),
                withTimeout(3000) {
                    result.await()
                },
            )
            assertFalse(pending().containsKey(request))
            assertFalse(timeoutJob.isActive)
            assertEquals(1, frames.size)
            assertEquals(0, queueSize())
            evidence("positive", "wrong ID ignored; exact ACK -> Confirmed; timer cancelled; one send")
        }

    @Test fun actualDefaultTimeout_terminalBodySurvivesButLateAckCannotReopenOrResolveFreshRequest() =
        runBlocking {
            val (scope, listener) = bind()
            val transport = DashboardRpcTransport()
            // Read the loaded production constant reflectively, not a compiler-inlined test value.
            val timeoutMs = HermesWsClient::class.java.getField("REQUEST_TIMEOUT_MS").getLong(null)
            assertEquals("actual85 default was incorrectly assumed to be 30s in the handoff", 120_000L, timeoutMs)
            val scoped = Channel<ScopedWsEvent>(Channel.UNLIMITED)
            val observations = Channel<HermesObservation>(Channel.UNLIMITED)
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) { HermesWsClient.scopedEvents.collect { scoped.send(it) } }
            val normalizedCollector =
                launch(start = CoroutineStart.UNDISPATCHED) { transport.observations.collect { observations.send(it) } }
            try {
                val started = System.nanoTime()
                val result =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        transport.submitPrompt(scope, session, "synthetic timeout boundary", false, false)
                    }
                val timedOutId = id()
                val timeoutJob = timer(timedOutId)
                assertTrue(timeoutJob.isActive)
                assertFalse(result.isCompleted)
                assertEquals(1, frames.size) // WebSocket.send returned true: local handoff, NOT server receipt.
                val body = "合成の完全な回答本文。実端末やサーバーの記録ではない。"
                listener.onMessage(
                    socket,
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"${session.runtimeId}","seq":1,"payload":{"text":"$body","status":"completed","partial":false}}}""",
                )
                val terminalEnvelope = withTimeout(3000) { scoped.receive() }
                assertEquals(scope, terminalEnvelope.scope)
                val terminal = terminalEnvelope.event as WsEvent.MessageComplete
                assertEquals(session.runtimeId, terminal.sessionId)
                assertEquals(body, terminal.text)
                assertEquals("completed", terminal.status)
                val completed = withTimeout(3000) { observations.receive() }
                assertEquals(ObservationKind.COMPLETED, completed.kind)
                assertEquals(scope, completed.scope)
                assertFalse("terminal event is not a prompt RPC acknowledgement", result.isCompleted)
                assertTrue(pending().containsKey(timedOutId))
                // This is the real IO coroutine delay in writeScoped; no test scheduler or timer replacement.
                assertSame(SubmissionResult.Unknown, withTimeout(timeoutMs + 20_000L) { result.await() })
                val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
                assertTrue("real default deadline must have elapsed: $elapsedMs", elapsedMs >= timeoutMs - 100)
                assertFalse(pending().containsKey(timedOutId))
                assertFalse(timeoutJob.isActive)
                assertEquals(body, terminal.text)
                assertEquals(0, queueSize())
                evidence(
                    "timeout",
                    "request=$timedOutId elapsed_ms=$elapsedMs real_deadline_ms=$timeoutMs Unknown; registry removed; terminal body preserved at transport",
                )

                // A distinct legitimate new request, not a replay of the unknown submission.
                val fresh =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        transport.submitPrompt(scope, session, "synthetic distinct new submission", false, false)
                    }
                val freshId = id()
                assertNotEquals(timedOutId, freshId)
                assertTrue(pending().containsKey(freshId))
                ack(listener, socket, timedOutId)
                val late = withTimeout(3000) { scoped.receive() }
                assertEquals(timedOutId, (late.event as WsEvent.RpcResult).id)
                assertFalse("late ACK must not resolve fresh request", fresh.isCompleted)
                assertSame(SubmissionResult.Unknown, result.await())
                assertTrue(pending().containsKey(freshId))
                assertFalse(pending().containsKey(timedOutId))
                assertEquals(body, terminal.text)
                ack(listener, socket, freshId)
                assertEquals(
                    SubmissionResult.Confirmed(
                        freshId,
                        PromptAcceptance.STREAMING,
                        PromptEvidence(41L, "synthetic-submission"),
                    ),
                    withTimeout(3000) {
                        fresh.await()
                    },
                )
                assertEquals(2, frames.size)
                assertEquals(0, queueSize())
                evidence(
                    "late-ack",
                    "same-generation old ACK emitted as scoped RpcResult but Unknown immutable; fresh request untouched then exact fresh ACK Confirmed; no queue/replay",
                )
            } finally {
                collector.cancel()
                normalizedCollector.cancel()
                scoped.close()
                observations.close()
            }
        }

    @Test fun generationAndSocketFence_oldListenerCannotResolveCurrentRequest() =
        runBlocking {
            val (oldScope, oldListener) = bind()
            val oldSocket = socket
            HermesWsClient.disconnect(clearPendingMessages = true) // production generation bump
            val freshSocket = mockk<WebSocket>(relaxed = true)
            every { freshSocket.send(any<String>()) } answers {
                frames.add(firstArg())
                true
            }
            val (freshScope, freshListener) = bind(freshSocket)
            assertNotEquals(oldScope.generation, freshScope.generation)
            assertFalse(HermesWsClient.matchesCurrentScope(oldScope))
            val transport = DashboardRpcTransport()
            assertSame(SubmissionResult.NotSent, transport.submitPrompt(oldScope, session, "stale scope", false, false))
            assertEquals(0, frames.size)
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    transport.submitPrompt(freshScope, session, "current scope", false, false)
                }
            val request = id()
            // Even a correct current request ID arriving on a stale listener/socket is rejected.
            ack(oldListener, oldSocket, request)
            ack(oldListener, freshSocket, request)
            ack(freshListener, oldSocket, request)
            assertFalse(result.isCompleted)
            assertTrue(pending().containsKey(request))
            ack(freshListener, freshSocket, request)
            assertTrue(withTimeout(3000) { result.await() } is SubmissionResult.Confirmed)
            assertEquals(1, frames.size)
            evidence(
                "generation-socket",
                "production disconnect bumps generation; old scope NotSent; three stale listener/socket combinations ignored; current pair Confirmed",
            )
        }

    @Test fun profileScopeFence_ackAfterProfileChangeIsUnknownAndNeverResent() =
        runBlocking {
            val (scope, listener) = bind()
            val transport = DashboardRpcTransport()
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    transport.submitPrompt(scope, session, "synthetic scoped write", false, false)
                }
            val request = id()
            activeProfile.value = "synthetic-profile-b"
            assertFalse(HermesWsClient.matchesCurrentScope(scope))
            ack(listener, socket, request)
            assertSame(SubmissionResult.Unknown, withTimeout(3000) { result.await() })
            assertFalse(pending().containsKey(request))
            assertSame(
                SubmissionResult.NotSent,
                transport.submitPrompt(scope, session, "stale-profile attempt", false, false),
            )
            assertEquals(1, frames.size)
            assertEquals(0, queueSize())
            evidence(
                "profile-scope",
                "ACK after profile change -> Unknown; further old-scope submit NotSent; one send; no replay",
            )
        }
}
