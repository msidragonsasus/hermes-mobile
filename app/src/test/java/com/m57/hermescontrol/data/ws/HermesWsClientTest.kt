package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.DashboardSessionTokenRefresher
import com.m57.hermescontrol.data.remote.NetworkMonitor
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.util.captureDiagnosticLogs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class HermesWsClientTest {
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0

        mockWebServer = MockWebServer()
        mockWebServer.start()

        mockkObject(AuthManager)
        every { AuthManager.wsUrl() } returns mockWebServer.url("/").toString().replace("http://", "ws://")
        every { AuthManager.isAutoReconnect() } returns false
        every { AuthManager.getSessionCookie() } returns null
        // Non-gated by default (token mode) so the gated ticket path is exercised
        // only by the explicit gated-mode test below.
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config
                        .ServerStoreState()
            }

        // Issue #470: clients are built through OkHttpProvider, which now
        // resolves the shared CookieManager.cookieJar. Inject a fake jar so
        // the WS stack can build its OkHttp clients without app context.
        CookieManager.setJarForTest(buildFakePersistentCookieJar())

        // Reset state
        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val statusField = HermesWsClient::class.java.getDeclaredField("_connectionStatus")
        statusField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (statusField.get(HermesWsClient) as MutableStateFlow<ConnectionStatus>).value = ConnectionStatus.DISCONNECTED

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>).clear()

        HermesWsClient.disconnect(clearPendingMessages = true) // Ensure it starts clean
        HermesWsClient.releaseExternalActivityConnectionLease()
        HermesWsClient.setAppForeground(true)
        val acceptQueuedMessagesField = HermesWsClient::class.java.getDeclaredField("acceptQueuedMessages")
        acceptQueuedMessagesField.isAccessible = true
        (acceptQueuedMessagesField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)
    }

    @After
    fun tearDown() {
        HermesWsClient.releaseExternalActivityConnectionLease()
        HermesWsClient.disconnect(clearPendingMessages = true)
        // Wait a bit to allow internal OkHttp coroutines to clean up before shutting down MockWebServer
        // Increased from 100ms for OkHttp 5.x — needs more time for the WS close handshake
        Thread.sleep(500)
        try {
            mockWebServer.shutdown()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        unmockkAll()
    }

    @Test
    fun testDiagnosticsKeepWirePayloadAndQueuedFrameIntact() {
        val logs = captureDiagnosticLogs()
        val secret = "SYNTHETIC_PRIVATE_secret_sudo_token_ticket_cookie_本文"
        val sent = mutableListOf<String>()
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } answers {
            sent.add(firstArg())
            false
        }
        val listener = diagnosticListener(socket)
        val id =
            HermesWsClient.send(
                WsMethods.SESSION_HISTORY,
                mapOf("secret" to secret, "sudo" to secret, "text" to secret.repeat(100)),
            )
        val original = sent.single()
        val wire = Json.parseToJsonElement(original).jsonObject
        assertEquals(id, wire["id"]?.jsonPrimitive?.content)
        assertEquals(
            secret,
            wire["params"]
                ?.jsonObject
                ?.get("secret")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            secret.repeat(100),
            wire["params"]
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content,
        )

        every { socket.send(any<String>()) } answers {
            sent.add(firstArg())
            true
        }
        listener.onOpen(socket, mockk(relaxed = true))
        assertEquals(listOf(original, original), sent)
        assertEquals(BuildConfig.DEBUG, logs.any { it.startsWith("WS send chars=") })
        assertEquals(BuildConfig.DEBUG, logs.any { it.startsWith("WS queue send chars=") })
        assertFalse(logs.joinToString("\n").contains("SYNTHETIC_PRIVATE"))
    }

    @Test
    fun testDiagnosticsExcludeMalformedFramesUnknownTypesAndExceptionChains() =
        runBlocking {
            val logs = captureDiagnosticLogs()
            val socket = mockk<WebSocket>(relaxed = true)
            val listener = diagnosticListener(socket)
            val malformed = "{\"secret\":\"SYNTHETIC_PRIVATE_壊れたJSON"
            val received =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5000) { HermesWsClient.events.first { it is WsEvent.Unknown } }
                }
            listener.onMessage(socket, malformed)
            assertEquals(malformed, (received.await() as WsEvent.Unknown).raw)
            val unknown = EventParser.parseParams(mapOf("type" to "SYNTHETIC_PRIVATE_type"), malformed)
            assertEquals(malformed, (unknown as WsEvent.Unknown).raw)

            val failure =
                IOException("SYNTHETIC_PRIVATE_token=ticket", IllegalStateException("SYNTHETIC_PRIVATE_cookie"))
            failure.addSuppressed(IllegalArgumentException("SYNTHETIC_PRIVATE_sudo"))
            listener.onFailure(socket, failure, null)
            assertTrue(logs.any { it.startsWith("WS parse failed:") })
            assertTrue(logs.contains("WebSocket failure: IOException"))
            assertFalse(logs.joinToString("\n").contains("SYNTHETIC_PRIVATE"))
        }

    @Test
    fun testDiagnosticsDoNotLogConnectionUrlButHandshakeKeepsIt() {
        val logs = captureDiagnosticLogs()
        val path =
            "/SYNTHETIC_PRIVATE_path?token=SYNTHETIC_PRIVATE_token&ticket=SYNTHETIC_PRIVATE_ticket" +
                "&cookie=SYNTHETIC_PRIVATE_cookie&other=%53YNTHETIC_PRIVATE_encoded"
        every { AuthManager.wsUrl() } returns mockWebServer.url(path).toString().replace("http://", "ws://")
        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        val handshake = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals(path, handshake?.path)
        assertEquals(BuildConfig.DEBUG, logs.contains("Opening WebSocket"))
        assertFalse(logs.joinToString("\n").contains("SYNTHETIC_PRIVATE"))
    }

    @Test
    fun testDiagnosticsExcludeReplayRpcErrorsAndPendingMethods() =
        runBlocking {
            val logs = captureDiagnosticLogs()
            val socket = mockk<WebSocket>(relaxed = true)
            val listener = diagnosticListener(socket)
            every { socket.send(any<String>()) } answers {
                val id =
                    Json
                        .parseToJsonElement(firstArg<String>())
                        .jsonObject["id"]
                        ?.jsonPrimitive
                        ?.content
                listener.onMessage(
                    socket,
                    "{\"id\":\"$id\",\"error\":{\"code\":-1,\"message\":\"SYNTHETIC_PRIVATE_rpc\"}}",
                )
                true
            }
            HermesWsClient.setSeqWatermark("SYNTHETIC_PRIVATE_session", 1)
            HermesWsClient.fetchReplayForTest()
            assertFalse(HermesWsClient.isReplayInFlight())
            assertTrue(logs.any { it.startsWith("Replay failed:") })
            every { socket.send(any<String>()) } returns true
            val pending = HermesWsClient.request("SYNTHETIC_PRIVATE_method")
            HermesWsClient.rejectAllPending()
            assertTrue(pending.isCancelled)
            assertFalse(logs.joinToString("\n").contains("SYNTHETIC_PRIVATE"))
        }

    @Test
    fun testDiagnosticsExcludeTicketExceptionsAndMalformedTicketBodies() {
        val logs = captureDiagnosticLogs()
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config
                        .ServerStoreState(wsAuthParam = "ticket")
            }
        every { AuthManager.endpointForBuild() } throws IOException("SYNTHETIC_PRIVATE_cookie_authorization")
        assertNull(HermesWsClient.mintWsTicket())
        assertTrue(logs.contains("WS ticket mint failed: IOException"))

        every { AuthManager.endpointForBuild() } returns
            ServerEndpoint.parse(mockWebServer.url("/").toString(), CleartextPolicy.ALLOW_WITH_WARNING)
        mockWebServer.enqueue(MockResponse().setBody("{\"ticket\":\"SYNTHETIC_PRIVATE_broken"))
        assertNull(HermesWsClient.mintWsTicket())
        assertTrue(logs.contains("WS ticket mint failed: unparseable ticket response"))
        assertFalse(logs.joinToString("\n").contains("SYNTHETIC_PRIVATE"))
    }

    @Test
    fun testScopedListenerKeepsCapturedProfileAndNormalizesLikeFake() =
        runBlocking {
            val socket = mockk<WebSocket>(relaxed = true)
            val listener = diagnosticListener(socket)
            val received = CompletableDeferred<ScopedWsEvent>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    received.complete(HermesWsClient.scopedEvents.first())
                }
            every { AuthManager.getSelectedProfileId() } returns "new-profile"
            listener.onMessage(
                socket,
                """{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"runtime-a"}}""",
            )
            val envelope = withTimeout(5000) { received.await() }
            assertEquals("synthetic", envelope.scope.serverIdentity)
            assertNull(envelope.scope.connectionProfileId)
            val observation =
                com.m57.hermescontrol.data.transport
                    .normalizeDashboardEvent(envelope)
            assertEquals(com.m57.hermescontrol.domain.hermes.ObservationKind.STARTED, observation?.kind)
            assertEquals("runtime-a", observation?.session?.runtimeId)
            collector.join()
        }

    @Test
    fun testScopedReadNeverQueuesAndRejectsChangedProfile() {
        val socket = mockk<WebSocket>(relaxed = true)
        diagnosticListener(socket)

        fun field(name: String) = HermesWsClient::class.java.getDeclaredField(name).apply { isAccessible = true }
        val generation = (field("connectionGeneration").get(HermesWsClient) as AtomicInteger).get()
        val scope = ConnectionScope("synthetic", null, null, "test", generation)

        @Suppress("UNCHECKED_CAST")
        val scopeFlow = field("_connectionScope").get(HermesWsClient) as MutableStateFlow<ConnectionScope?>
        scopeFlow.value = scope
        field("connectionBaseUrl").set(HermesWsClient, AuthManager.getBaseUrl())
        every { AuthManager.getSelectedProfileId() } returns null
        every { AuthManager.activeProfileId } returns MutableStateFlow(null)
        every { socket.send(any<String>()) } returns false
        assertTrue(HermesWsClient.readSessions(scope).isCancelled)
        @Suppress("UNCHECKED_CAST")
        val queue = field("messageQueue").get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        assertTrue(queue.isEmpty())
        verify(exactly = 1) { socket.send(any<String>()) }
        every { socket.send(any<String>()) } returns true
        val timedOut = HermesWsClient.readSessions(scope, timeoutMs = 1)
        runBlocking {
            try {
                withTimeout(5000) { timedOut.await() }
                org.junit.Assert.fail("Unanswered read must time out")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertEquals(-1, e.code)
            }
        }
        assertTrue(timedOut.isCancelled)
        assertTrue(queue.isEmpty())
        every { AuthManager.getSelectedProfileId() } returns "other-profile"
        assertTrue(HermesWsClient.readSessions(scope).isCancelled)
        verify(exactly = 2) { socket.send(any<String>()) }
        assertTrue(queue.isEmpty())
    }

    @Test
    fun testOfflineWritesAreRejectedAndNeverFlushedOnReconnect() {
        for (method in listOf(
            WsMethods.PROMPT_SUBMIT,
            WsMethods.APPROVAL_RESPOND,
            WsMethods.CLARIFY_RESPOND,
            WsMethods.SUDO_RESPOND,
            WsMethods.SECRET_RESPOND,
            WsMethods.SESSION_CREATE,
            "future.write",
        )) {
            assertTrue(HermesWsClient.request(method, mapOf("text" to "SYNTHETIC_PRIVATE_body")).isCancelled)
        }
        assertFalse(HermesWsClient.pendingReply)
        assertEquals(0, mockWebServer.requestCount)
        val socket = mockk<WebSocket>(relaxed = true)
        val listener = diagnosticListener(socket)
        listener.onOpen(socket, mockk(relaxed = true))
        verify(exactly = 0) { socket.send(any<String>()) }
    }

    private fun scopedWriteFixture(socket: WebSocket): Pair<ConnectionScope, WebSocketListener> {
        val listener = diagnosticListener(socket)

        fun field(name: String) = HermesWsClient::class.java.getDeclaredField(name).apply { isAccessible = true }
        val generation = (field("connectionGeneration").get(HermesWsClient) as AtomicInteger).get()
        val scope = ConnectionScope("synthetic", null, "profile-a", "test", generation)

        @Suppress("UNCHECKED_CAST")
        val scopeFlow = field("_connectionScope").get(HermesWsClient) as MutableStateFlow<ConnectionScope?>
        scopeFlow.value = scope
        field("connectionBaseUrl").set(HermesWsClient, AuthManager.getBaseUrl())
        every { AuthManager.getSelectedProfileId() } returns null
        every { AuthManager.activeProfileId } returns MutableStateFlow("profile-a")
        return scope to listener
    }

    @Test
    fun testConfiguredDraftScopeReadsOneServerSnapshotWithoutConnecting() {
        val store = mockk<com.m57.hermescontrol.data.config.ServerStore>()
        every { AuthManager.serverStore } returns store
        every { store.getLatestState() } returns
            com.m57.hermescontrol.data.config.ServerStoreState(
                baseUrl = "https://synthetic.example/gateway",
                selectedProfileId = "connection-a",
            )
        every { AuthManager.activeProfileId } returns MutableStateFlow("profile-a")
        val captured = requireNotNull(HermesWsClient.configuredScope())
        assertEquals("connection-a", captured.connectionProfileId)
        assertEquals("profile-a", captured.serverProfileId)
        assertEquals(64, captured.serverIdentity.length)
        verify(exactly = 1) { store.getLatestState() }
        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun testLegacySessionWriteCannotCrossProfileDuringRegistration() {
        val socket = mockk<WebSocket>(relaxed = true)
        val (scope, _) = scopedWriteFixture(socket)
        val activeProfile = MutableStateFlow(scope.serverProfileId)
        every { AuthManager.activeProfileId } returns activeProfile
        HermesWsClient.send(WsMethods.SESSION_CREATE, onSent = { activeProfile.value = "other" })
        verify(exactly = 0) { socket.send(any<String>()) }
        activeProfile.value = scope.serverProfileId
        HermesWsClient.send(WsMethods.SESSION_CREATE, mapOf("profile" to "other"))
        verify(exactly = 0) { socket.send(any<String>()) }
        val sent = mutableListOf<String>()
        every { socket.send(any<String>()) } answers {
            activeProfile.value = "other"
            sent.add(firstArg())
            true
        }
        HermesWsClient.send(WsMethods.SESSION_CREATE)
        assertEquals(1, sent.size)
        assertEquals(
            "profile-a",
            Json
                .parseToJsonElement(
                    sent.single(),
                ).jsonObject["params"]
                ?.jsonObject
                ?.get("profile")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun testScopedWriteRejectsWrongDestinationAndUnknownMethodBeforeWire() {
        val socket = mockk<WebSocket>(relaxed = true)
        val (scope, listener) = scopedWriteFixture(socket)
        val params = mapOf("session_id" to "runtime", "text" to "SYNTHETIC_PRIVATE_body")
        for (candidate in listOf(
            scope.copy(serverProfileId = "other"),
            scope.copy(processId = "other"),
            scope.copy(generation = -1),
        )) {
            assertFalse(HermesWsClient.writeScoped(candidate, WsMethods.PROMPT_SUBMIT, params).accepted)
        }
        assertFalse(HermesWsClient.writeScoped(scope, "future.write", params).accepted)
        assertFalse(
            HermesWsClient.writeScoped(scope, WsMethods.PROMPT_SUBMIT, params + ("profile" to "other")).accepted,
        )
        assertFalse(HermesWsClient.writeScoped(scope, WsMethods.PROMPT_SUBMIT, emptyMap()).accepted)
        verify(exactly = 0) { socket.send(any<String>()) }
        assertEquals(0, mockWebServer.requestCount)
        every { socket.send(any<String>()) } returns false
        val rejected = HermesWsClient.writeScoped(scope, WsMethods.PROMPT_SUBMIT, params)
        assertFalse(rejected.accepted)
        assertTrue(rejected.response.isCancelled)
        listener.onOpen(socket, mockk(relaxed = true))
        verify(exactly = 1) { socket.send(any<String>()) }
    }

    @Test
    fun testScopedWritesMatchReverseAcksAndKeepFixedProfile() =
        runBlocking {
            val legacyEvents = Channel<WsEvent>(Channel.UNLIMITED)
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) { HermesWsClient.events.collect { legacyEvents.send(it) } }
            try {
                val socket = mockk<WebSocket>(relaxed = true)
                val frames = mutableListOf<String>()
                every { socket.send(any<String>()) } answers {
                    frames.add(firstArg())
                    true
                }
                val (scope, listener) = scopedWriteFixture(socket)
                val first =
                    HermesWsClient.writeScoped(
                        scope,
                        WsMethods.PROMPT_SUBMIT,
                        mapOf(
                            "session_id" to "runtime-a",
                            "text" to "A",
                        ),
                    )
                val second =
                    HermesWsClient.writeScoped(
                        scope,
                        WsMethods.PROMPT_SUBMIT,
                        mapOf(
                            "session_id" to "runtime-b",
                            "text" to "B",
                        ),
                    )
                assertTrue(first.accepted && second.accepted)
                for (frame in frames) {
                    assertEquals(
                        "profile-a",
                        Json
                            .parseToJsonElement(
                                frame,
                            ).jsonObject["params"]
                            ?.jsonObject
                            ?.get("profile")
                            ?.jsonPrimitive
                            ?.content,
                    )
                }
                listener.onMessage(
                    socket,
                    """{"jsonrpc":"2.0","id":"${second.requestId}","result":{"status":"queued"}}""",
                )
                assertTrue(second.response.isCompleted)
                assertFalse(first.response.isCompleted)
                listener.onMessage(
                    socket,
                    """{"jsonrpc":"2.0","id":"${first.requestId}","result":{"status":"streaming"}}""",
                )
                assertTrue(first.response.isCompleted)
                assertTrue(
                    first.response
                        .await()
                        .toString()
                        .contains("streaming"),
                )
                assertTrue(
                    second.response
                        .await()
                        .toString()
                        .contains("queued"),
                )
                // The following legacy event is an ordered barrier: neither typed ACK may precede it here.
                listener.onMessage(socket, """{"jsonrpc":"2.0","method":"synthetic.marker","params":{}}""")
                assertTrue(withTimeout(5000) { legacyEvents.receive() } is WsEvent.Unknown)
            } finally {
                collector.cancel()
                legacyEvents.close()
            }
        }

    @Test
    fun testScopedAcceptedWriteTimeoutNeverBecomesReplay() =
        runBlocking {
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns true
            val (scope, listener) = scopedWriteFixture(socket)
            val call =
                HermesWsClient.writeScoped(
                    scope,
                    WsMethods.PROMPT_SUBMIT,
                    mapOf(
                        "session_id" to "runtime",
                        "text" to "A",
                    ),
                    1,
                )
            assertTrue(call.accepted)
            try {
                withTimeout(5000) { call.response.await() }
                org.junit.Assert.fail("Missing ACK must not succeed")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertEquals(-1, e.code)
            }
            listener.onOpen(socket, mockk(relaxed = true))
            verify(exactly = 1) { socket.send(any<String>()) }
        }

    @Test
    fun testRejectedUnrelatedWritePreservesActiveReply() {
        HermesWsClient::class.java.getDeclaredField("pendingReply").apply {
            isAccessible = true
            setBoolean(HermesWsClient, true)
        }
        val result = HermesWsClient.request(WsMethods.APPROVAL_RESPOND)
        assertTrue(result.isCancelled)
        assertTrue(HermesWsClient.pendingReply)
        val prompt = HermesWsClient.request(WsMethods.PROMPT_SUBMIT, mapOf("session_id" to "runtime", "text" to "new"))
        assertTrue(prompt.isCancelled)
        assertTrue(HermesWsClient.pendingReply)
    }

    private fun diagnosticListener(socket: WebSocket): WebSocketListener {
        HermesWsClient.clearSeqWatermarks()

        fun field(name: String) = HermesWsClient::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("webSocket").set(HermesWsClient, socket)
        (field("connected").get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)
        (field("intentionalClose").get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        val generation = (field("connectionGeneration").get(HermesWsClient) as AtomicInteger).get()
        val constructor =
            Class
                .forName("com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl")
                .declaredConstructors
                .single()
                .apply { isAccessible = true }
        val scope = ConnectionScope("synthetic", null, null, "test", generation)

        @Suppress("UNCHECKED_CAST")
        val scopeFlow = field("_connectionScope").get(HermesWsClient) as MutableStateFlow<ConnectionScope?>
        scopeFlow.value = scope
        field("connectionBaseUrl").set(HermesWsClient, AuthManager.getBaseUrl())
        return constructor.newInstance(
            generation,
            scope,
        ) as WebSocketListener
    }

    @Test
    fun testConnectAndSend() {
        var serverWebSocket: WebSocket? = null
        val serverLatch = CountDownLatch(1)
        val messageLatch = CountDownLatch(1)
        var receivedMessage: String? = null

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverWebSocket = webSocket
                        serverLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        receivedMessage = text
                        messageLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue("Server failed to accept connection", serverLatch.await(5, TimeUnit.SECONDS))
        assertTrue(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)

        // Send a message
        val id = HermesWsClient.send("test_method", mapOf("param" to "value"))

        // Verify message received by server
        assertTrue("Message not received", messageLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(receivedMessage)
        val msg = receivedMessage ?: ""
        assertTrue(msg.contains("test_method"))
        assertTrue(msg.contains("value"))
        assertTrue(msg.contains(id))
    }

    @Test
    fun testFailedSocketWriteNeverQueuesForReconnect() {
        val staleSocket = mockk<WebSocket>(relaxed = true)
        every { staleSocket.send(any<String>()) } returns false
        diagnosticListener(staleSocket)

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, staleSocket)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val statusField = HermesWsClient::class.java.getDeclaredField("_connectionStatus")
        statusField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (statusField.get(HermesWsClient) as MutableStateFlow<ConnectionStatus>).value = ConnectionStatus.CONNECTED

        HermesWsClient.send(WsMethods.PROMPT_SUBMIT, mapOf("session_id" to "s1", "text" to "hello"))

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        assertFalse(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun testSendRegistersRequestIdWhileOutboundLockHeld() {
        val lockField = HermesWsClient::class.java.getDeclaredField("outboundLock").apply { isAccessible = true }
        val outboundLock = lockField.get(HermesWsClient)
        var callbackHeldLock = false

        HermesWsClient.send("test.method", onSent = { callbackHeldLock = Thread.holdsLock(outboundLock) })

        assertTrue(callbackHeldLock)
    }

    @Test
    fun testNetworkChangeInvalidatesOldSocketAndOpensReplacementImmediately() {
        every { AuthManager.isAutoReconnect() } returns true
        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns true
        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, socket)
        val generationField = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
        generationField.isAccessible = true
        val generation = generationField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicInteger
        val oldGeneration = generation.get()
        val listenerClass = Class.forName("com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl")
        val constructor = listenerClass.declaredConstructors.single()
        constructor.isAccessible = true
        val staleListener =
            constructor.newInstance(
                oldGeneration,
                ConnectionScope("synthetic", null, null, "test", oldGeneration),
            ) as WebSocketListener
        var replacementOpens = 0
        val deferred = HermesWsClient.request(WsMethods.PROCESS_LIST, mapOf("session_id" to "s1"))

        HermesWsClient.reconnectForNetworkChange { replacementOpens++ }
        staleListener.onClosing(socket, 4401, "expired")
        staleListener.onFailure(socket, IOException("old network lost"), null)

        verify(exactly = 1) { socket.cancel() }
        assertEquals(1, replacementOpens)
        assertEquals(oldGeneration + 1, generation.get())
        assertFalse(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.RECONNECTING, HermesWsClient.connectionStatus.value)
        assertEquals(null, socketField.get(HermesWsClient))
        assertTrue(deferred.isCompleted)
    }

    @Test
    fun testNetworkLossPreservesAuthExpiredStatus() =
        runBlocking {
            every { AuthManager.isAutoReconnect() } returns true
            val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
            intentionalCloseField.isAccessible = true
            (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)
            val statusField = HermesWsClient::class.java.getDeclaredField("_connectionStatus")
            statusField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val status = statusField.get(HermesWsClient) as MutableStateFlow<ConnectionStatus>
            status.value = ConnectionStatus.AUTH_EXPIRED
            val socket = mockk<WebSocket>(relaxed = true)
            val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
            socketField.isAccessible = true
            socketField.set(HermesWsClient, socket)

            HermesWsClient.reconnectForNetworkChange(false)
            HermesWsClient.reconnectForNetworkChange(true)

            assertEquals(ConnectionStatus.AUTH_EXPIRED, HermesWsClient.connectionStatus.value)
            verify(exactly = 0) { socket.cancel() }
        }

    @Test
    fun testBreakBeforeMakeDoesNotReplayAcceptedRequest() {
        every { AuthManager.isAutoReconnect() } returns true
        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)
        val oldSocket = mockk<WebSocket>(relaxed = true)
        every { oldSocket.send(any<String>()) } returns true
        diagnosticListener(oldSocket)
        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, oldSocket)
        HermesWsClient.send(WsMethods.SUBSCRIPTION_CHANGE, mapOf("cancel" to true))
        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        assertTrue(queue.isEmpty())

        HermesWsClient.reconnectForNetworkChange(false)
        assertTrue(queue.isEmpty())

        var replacementOpens = 0
        HermesWsClient.reconnectForNetworkChange(true) { replacementOpens++ }
        val replacementSocket = mockk<WebSocket>(relaxed = true)
        every { replacementSocket.send(any<String>()) } returns true
        val generationField = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
        generationField.isAccessible = true
        val generation = generationField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicInteger
        val listenerClass = Class.forName("com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl")
        val constructor = listenerClass.declaredConstructors.single()
        constructor.isAccessible = true
        val replacementListener =
            constructor.newInstance(
                generation.get(),
                ConnectionScope("synthetic", null, null, "test", generation.get()),
            ) as WebSocketListener
        replacementListener.onOpen(replacementSocket, mockk(relaxed = true))

        assertEquals(1, replacementOpens)
        assertTrue(queue.isEmpty())
        verify(exactly = 1) { oldSocket.cancel() }
        verify(exactly = 0) { replacementSocket.send(any<String>()) }
    }

    @Test
    fun testNetworkChangePreservesAuthExpiredSocket() {
        every { AuthManager.isAutoReconnect() } returns true
        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        val statusField = HermesWsClient::class.java.getDeclaredField("_connectionStatus")
        statusField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val status = statusField.get(HermesWsClient) as MutableStateFlow<ConnectionStatus>
        status.value = ConnectionStatus.AUTH_EXPIRED
        val socket = mockk<WebSocket>(relaxed = true)
        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, socket)

        HermesWsClient.reconnectForNetworkChange()

        assertEquals(ConnectionStatus.AUTH_EXPIRED, HermesWsClient.connectionStatus.value)
        verify(exactly = 0) { socket.cancel() }
    }

    @Test
    fun testReconnectSchedulingIsIdempotentWhilePending() {
        mockkObject(NetworkMonitor)
        every { AuthManager.isAutoReconnect() } returns true
        every { NetworkMonitor.isConnected } returns MutableStateFlow(true)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val backoffField = HermesWsClient::class.java.getDeclaredField("currentBackoff")
        backoffField.isAccessible = true
        backoffField.setLong(HermesWsClient, 1_000L)

        val reconnectMethod = HermesWsClient::class.java.getDeclaredMethod("scheduleReconnect")
        reconnectMethod.isAccessible = true
        reconnectMethod.invoke(HermesWsClient)
        reconnectMethod.invoke(HermesWsClient)

        assertEquals(2_000L, backoffField.getLong(HermesWsClient))
    }

    @Test
    fun testCompletedReconnectDoesNotClearReplacementJob() {
        mockkObject(NetworkMonitor)
        every { AuthManager.isAutoReconnect() } returns true
        every { NetworkMonitor.isConnected } returns MutableStateFlow(true)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val backoffField = HermesWsClient::class.java.getDeclaredField("currentBackoff")
        backoffField.isAccessible = true
        backoffField.setLong(HermesWsClient, 0L)

        val reconnectJobField = HermesWsClient::class.java.getDeclaredField("reconnectJob")
        reconnectJobField.isAccessible = true
        val replacementJob = mockk<Job>(relaxed = true)

        val lockField = HermesWsClient::class.java.getDeclaredField("outboundLock")
        lockField.isAccessible = true
        val lock = lockField.get(HermesWsClient)
        val reconnectMethod = HermesWsClient::class.java.getDeclaredMethod("scheduleReconnect")
        reconnectMethod.isAccessible = true

        synchronized(lock) {
            reconnectMethod.invoke(HermesWsClient)
            reconnectJobField.set(HermesWsClient, replacementJob)
        }

        Thread.sleep(100)
        assertSame(replacementJob, reconnectJobField.get(HermesWsClient))
    }

    @Test
    fun testOversizedRejectedMessageIsNotQueued() {
        val staleSocket = mockk<WebSocket>(relaxed = true)
        every { staleSocket.send(any<String>()) } returns false
        diagnosticListener(staleSocket)

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, staleSocket)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)

        HermesWsClient.send(
            WsMethods.PROMPT_SUBMIT,
            mapOf("session_id" to "s1", "text" to "x".repeat(16 * 1024 * 1024 + 1)),
        )

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        assertTrue(queue.isEmpty())
        io.mockk.verify(exactly = 0) { staleSocket.cancel() }
    }

    @Test
    fun testDisconnectedOversizedMessageIsNotQueued() {
        HermesWsClient.send(
            WsMethods.PROMPT_SUBMIT,
            mapOf("session_id" to "s1", "text" to "x".repeat(16 * 1024 * 1024 + 1)),
        )

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        assertTrue(queue.isEmpty())
    }

    @Test
    fun testQueuePressureDoesNotCancelAcceptedFrames() {
        val pressuredSocket = mockk<WebSocket>(relaxed = true)
        every { pressuredSocket.send(any<String>()) } returns false
        every { pressuredSocket.queueSize() } returns 1024L
        diagnosticListener(pressuredSocket)

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, pressuredSocket)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        HermesWsClient.send(WsMethods.PROMPT_SUBMIT, mapOf("session_id" to "s1", "text" to "hello"))

        io.mockk.verify(exactly = 0) { pressuredSocket.cancel() }
        val drainJobField = HermesWsClient::class.java.getDeclaredField("outboundDrainJob")
        drainJobField.isAccessible = true
        assertNotNull(drainJobField.get(HermesWsClient))
    }

    @Test
    fun testFailureDuringOutboundDrainSchedulesReconnect() {
        mockkObject(NetworkMonitor)
        every { AuthManager.isAutoReconnect() } returns true
        every { NetworkMonitor.isConnected } returns MutableStateFlow(true)

        val pressuredSocket = mockk<WebSocket>(relaxed = true)
        every { pressuredSocket.send(any<String>()) } returns false
        every { pressuredSocket.queueSize() } returns 1024L

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, pressuredSocket)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val generationField = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
        generationField.isAccessible = true
        (generationField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicInteger).set(1)

        val backoffField = HermesWsClient::class.java.getDeclaredField("currentBackoff")
        backoffField.isAccessible = true
        backoffField.setLong(HermesWsClient, 1_000L)

        diagnosticListener(pressuredSocket)
        HermesWsClient.send(WsMethods.PROMPT_SUBMIT, mapOf("session_id" to "s1", "text" to "hello"))

        val listenerClass = Class.forName("com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl")
        val constructor = listenerClass.declaredConstructors.single()
        constructor.isAccessible = true
        val listener =
            constructor.newInstance(
                1,
                ConnectionScope("synthetic", null, null, "test", 1),
            ) as WebSocketListener
        listener.onFailure(pressuredSocket, IOException("connection lost"), null)

        assertEquals(2_000L, backoffField.getLong(HermesWsClient))
    }

    @Test
    fun testReplayPressureSchedulesRecoveryWithoutDroppingHead() {
        val pressuredSocket = mockk<WebSocket>(relaxed = true)
        every { pressuredSocket.send(any<String>()) } returnsMany listOf(true, false)
        every { pressuredSocket.queueSize() } returns 1024L

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        queue.add("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"session.list\",\"params\":{}}")
        queue.add("{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"session.list\",\"params\":{}}")

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val generationField = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
        generationField.isAccessible = true
        (generationField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicInteger).set(1)

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, pressuredSocket)

        val listenerClass = Class.forName("com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl")
        val constructor = listenerClass.declaredConstructors.single()
        constructor.isAccessible = true
        val listener =
            constructor.newInstance(
                1,
                ConnectionScope("synthetic", null, null, "test", 1),
            ) as WebSocketListener
        listener.onOpen(pressuredSocket, mockk(relaxed = true))

        assertFalse(HermesWsClient.isConnected)
        assertEquals(1, queue.size)
        assertTrue(queue.peek()?.contains("\"2\"") == true)
        io.mockk.verify(exactly = 0) { pressuredSocket.cancel() }
        val drainJobField = HermesWsClient::class.java.getDeclaredField("outboundDrainJob")
        drainJobField.isAccessible = true
        assertNotNull(drainJobField.get(HermesWsClient))
    }

    @Test
    fun testStaleOnOpenDoesNotReplayQueue() {
        val staleSocket = mockk<WebSocket>(relaxed = true)
        val currentSocket = mockk<WebSocket>(relaxed = true)
        every { currentSocket.send(any<String>()) } returns true

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, currentSocket)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val generationField = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
        generationField.isAccessible = true
        (generationField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicInteger).set(2)

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        queue.add("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"session.list\",\"params\":{}}")

        val listenerClass = Class.forName("com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl")
        val constructor = listenerClass.declaredConstructors.single()
        constructor.isAccessible = true
        val staleListener =
            constructor.newInstance(
                1,
                ConnectionScope("synthetic", null, null, "test", 1),
            ) as WebSocketListener
        val currentListener =
            constructor.newInstance(
                2,
                ConnectionScope("synthetic", null, null, "test", 2),
            ) as WebSocketListener
        val response = mockk<okhttp3.Response>(relaxed = true)

        staleListener.onOpen(staleSocket, response)
        currentListener.onOpen(currentSocket, response)

        io.mockk.verify(exactly = 0) { staleSocket.send(any<String>()) }
        io.mockk.verify(exactly = 1) { currentSocket.send(any<String>()) }
        assertTrue(queue.isEmpty())
    }

    @Test
    fun testRejectedSendAfterDisconnectIsNotQueued() {
        val staleSocket = mockk<WebSocket>(relaxed = true)
        every { staleSocket.send(any<String>()) } answers {
            HermesWsClient.disconnect(clearPendingMessages = true)
            false
        }
        diagnosticListener(staleSocket)

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, staleSocket)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        HermesWsClient.send(WsMethods.PROMPT_SUBMIT, mapOf("session_id" to "s1", "text" to "hello"))

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        assertTrue(queue.isEmpty())
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)
    }

    @Test
    fun testDisconnectPreservesQueuedMessagesUnlessExplicitlyCleared() {
        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        HermesWsClient.send(WsMethods.SESSION_HISTORY, mapOf("session_id" to "s1", "text" to "hello"))

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        assertEquals(1, queue.size)

        HermesWsClient.disconnect()
        assertEquals(1, queue.size)

        HermesWsClient.send(WsMethods.SESSION_HISTORY, mapOf("session_id" to "s1", "text" to "during reconnect"))
        assertEquals(2, queue.size)

        HermesWsClient.disconnect(clearPendingMessages = true)
        assertTrue(queue.isEmpty())

        HermesWsClient.send(WsMethods.SESSION_HISTORY, mapOf("session_id" to "s1", "text" to "after logout"))
        assertTrue(queue.isEmpty())
    }

    @Test
    fun testRejectAllPendingRemovesQueuedAwaitedRpc() {
        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val deferred = HermesWsClient.request(WsMethods.PROCESS_LIST, mapOf("session_id" to "s1"))

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        assertEquals(1, queue.size)

        HermesWsClient.rejectAllPending()

        assertTrue(deferred.isCompleted)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun testAuthClosingCodeSurvivesRejectedSendRecovery() {
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns false
        every { socket.queueSize() } returns 0L

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, socket)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val generationField = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
        generationField.isAccessible = true
        (generationField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicInteger).set(1)

        val listenerClass = Class.forName("com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl")
        val constructor = listenerClass.declaredConstructors.single()
        constructor.isAccessible = true
        val listener =
            constructor.newInstance(
                1,
                ConnectionScope("synthetic", null, null, "test", 1),
            ) as WebSocketListener

        listener.onClosing(socket, 4401, "expired")
        HermesWsClient.send(WsMethods.PROMPT_SUBMIT, mapOf("session_id" to "s1", "text" to "hello"))

        assertEquals(ConnectionStatus.AUTH_EXPIRED, HermesWsClient.connectionStatus.value)
        io.mockk.verify(exactly = 0) { socket.cancel() }
    }

    @Test
    fun testSocketFailureDoesNotOverwriteAuthExpired() {
        val socket = mockk<WebSocket>(relaxed = true)
        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        val generationField = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
        generationField.isAccessible = true
        (generationField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicInteger).set(1)
        val statusField = HermesWsClient::class.java.getDeclaredField("_connectionStatus")
        statusField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val status = statusField.get(HermesWsClient) as MutableStateFlow<ConnectionStatus>
        status.value = ConnectionStatus.AUTH_EXPIRED
        val listenerClass = Class.forName("com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl")
        val constructor = listenerClass.declaredConstructors.single()
        constructor.isAccessible = true
        val listener =
            constructor.newInstance(
                1,
                ConnectionScope("synthetic", null, null, "test", 1),
            ) as WebSocketListener

        listener.onFailure(socket, IOException("network changed"), null)

        assertEquals(ConnectionStatus.AUTH_EXPIRED, HermesWsClient.connectionStatus.value)
    }

    @Test
    fun testExplicitConnectRetriesAfterAuthExpired() =
        runBlocking {
            val statusField = HermesWsClient::class.java.getDeclaredField("_connectionStatus")
            statusField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val status = statusField.get(HermesWsClient) as MutableStateFlow<ConnectionStatus>
            status.value = ConnectionStatus.AUTH_EXPIRED
            val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
            connectedField.isAccessible = true
            (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)
            val oldSocket = mockk<WebSocket>(relaxed = true)
            val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
            socketField.isAccessible = true
            socketField.set(HermesWsClient, oldSocket)
            mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))

            HermesWsClient.connect()

            withTimeout(5_000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
            verify(exactly = 1) { oldSocket.cancel() }
        }

    @Test
    fun testDisconnectThenConnectSupersedesBlockedSocketOpen() =
        runBlocking {
            mockkObject(DashboardSessionTokenRefresher)
            every { AuthManager.baseUrl() } returns mockWebServer.url("/").toString()
            val refreshStarted = CountDownLatch(1)
            val releaseRefresh = CountDownLatch(1)
            val refreshCalls = AtomicInteger(0)
            every { DashboardSessionTokenRefresher.fetch(any(), any()) } answers {
                if (refreshCalls.getAndIncrement() == 0) {
                    refreshStarted.countDown()
                    releaseRefresh.await(5, TimeUnit.SECONDS)
                    "stale-token"
                } else {
                    null
                }
            }
            val staleConnect = thread { HermesWsClient.connect() }
            assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))

            HermesWsClient.disconnect(clearPendingMessages = true)
            mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            val generationField = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
            generationField.isAccessible = true
            val generation = generationField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicInteger
            val disconnectedGeneration = generation.get()
            val replacementConnect = thread { HermesWsClient.connect() }
            val generationDeadline = System.currentTimeMillis() + 5_000
            while (generation.get() == disconnectedGeneration && System.currentTimeMillis() < generationDeadline) {
                Thread.sleep(10)
            }
            assertTrue(generation.get() > disconnectedGeneration)
            val currentGeneration = generation.get()

            releaseRefresh.countDown()
            staleConnect.join(5_000)
            replacementConnect.join(5_000)
            withTimeout(5_000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }

            assertFalse(staleConnect.isAlive)
            assertFalse(replacementConnect.isAlive)
            assertEquals(currentGeneration, generation.get())
            assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)
            verify(exactly = 0) { AuthManager.setToken("stale-token") }
        }

    @Test
    fun testReceiveMessage() {
        var serverWebSocket: WebSocket? = null
        val serverLatch = CountDownLatch(1)

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverWebSocket = webSocket
                        serverLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS))

        // Server sends a message to client
        val jsonResponse =
            """
            {
                "jsonrpc": "2.0",
                "id": "1",
                "result": "success"
            }
            """.trimIndent()

        val receivedEvent =
            runBlocking {
                withTimeout(5000) {
                    launch { serverWebSocket?.send(jsonResponse) }
                    HermesWsClient.events.first { it is WsEvent.RpcResult }
                }
            }

        assertTrue(receivedEvent is WsEvent.RpcResult)
        assertEquals("1", (receivedEvent as WsEvent.RpcResult).id)
    }

    @Test
    fun testBackgroundWithoutPendingWorkDisconnects() {
        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }

        HermesWsClient.setAppForeground(false)

        assertFalse(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)
    }

    @Test
    fun testExternalActivityLeaseKeepsIdleBackgroundSocketUntilReleased() {
        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }

        HermesWsClient.acquireExternalActivityConnectionLease()
        HermesWsClient.setAppForeground(false)

        assertTrue(HermesWsClient.isConnected)

        HermesWsClient.releaseExternalActivityConnectionLease()

        assertFalse(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)
    }

    @Test
    fun testBackgroundWithPendingReplyStaysConnected() {
        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        HermesWsClient.sendMessage("session-1", "hello")

        HermesWsClient.setAppForeground(false)

        assertTrue(HermesWsClient.isConnected)
    }

    @Test
    fun testRejectedPromptDisconnectsIdleBackgroundSocket() {
        lateinit var serverSocket: WebSocket
        val connectedLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        serverSocket = webSocket
                        connectedLatch.countDown()
                    }
                },
            ),
        )
        HermesWsClient.connect()
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS))
        val requestId = HermesWsClient.sendMessage("deleted-session", "hello")
        HermesWsClient.setAppForeground(false)

        assertTrue(HermesWsClient.pendingReply)
        serverSocket.send(
            """{"jsonrpc":"2.0","id":"$requestId","error":{"code":4001,"message":"Session not found"}}""",
        )

        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.DISCONNECTED }
            }
        }
        assertFalse(HermesWsClient.pendingReply)
        assertFalse(HermesWsClient.isConnected)
    }

    @Test
    fun testBackgroundQueuedSendDisconnectsAfterFlush() {
        val messageLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        messageLatch.countDown()
                    }
                },
            ),
        )
        HermesWsClient.setAppForeground(false)

        HermesWsClient.send(WsMethods.SESSION_LIST)

        assertTrue(messageLatch.await(5, TimeUnit.SECONDS))
        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.DISCONNECTED }
            }
        }
        assertFalse(HermesWsClient.isConnected)
    }

    @Test
    fun testMessageCompleteDisconnectsIdleBackgroundSocket() {
        lateinit var serverSocket: WebSocket
        val connectedLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        serverSocket = webSocket
                        connectedLatch.countDown()
                    }
                },
            ),
        )
        HermesWsClient.connect()
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS))
        ActiveSessionHolder.setScoped(
            "runtime-session",
            "stored-session",
            checkNotNull(HermesWsClient.configuredScope()),
        )
        HermesWsClient.sendMessage("session-1", "hello")
        HermesWsClient.setAppForeground(false)

        val completeEvent =
            """
            {"jsonrpc":"2.0","method":"event","params":{"type":"message.complete",
            "payload":{"text":"done","session_id":"runtime-session"}}}
            """.trimIndent()
        val receivedEvent =
            runBlocking {
                withTimeout(5000) {
                    launch { serverSocket.send(completeEvent) }
                    HermesWsClient.events.first { it is WsEvent.MessageComplete } as WsEvent.MessageComplete
                }
            }

        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.DISCONNECTED }
            }
        }
        assertFalse(HermesWsClient.isConnected)
        assertEquals("stored-session", receivedEvent.storedSessionId)
    }

    @Test
    fun testDisconnect() {
        val serverLatch = CountDownLatch(1)
        val closedLatch = CountDownLatch(1)

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        closedLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS))

        HermesWsClient.disconnect(clearPendingMessages = true)
        assertFalse(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)

        // Verify server received close frame
        assertTrue(closedLatch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun testSendMessage() {
        var serverWebSocket: WebSocket? = null
        val serverLatch = CountDownLatch(1)
        val messageLatch = CountDownLatch(1)
        var receivedMessage: String? = null

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverWebSocket = webSocket
                        serverLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        receivedMessage = text
                        messageLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue("Server failed to accept connection", serverLatch.await(5, TimeUnit.SECONDS))

        // Use the convenience method
        HermesWsClient.sendMessage("test_session_id", "Hello Hermes!")

        // Verify message received by server
        assertTrue("Message not received", messageLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(receivedMessage)
        val msg = receivedMessage ?: ""
        assertTrue(msg.contains(WsMethods.PROMPT_SUBMIT))
        assertTrue(msg.contains("test_session_id"))
        assertTrue(msg.contains("Hello Hermes!"))
    }

    @Test
    fun testAutoReconnect() {
        every { AuthManager.isAutoReconnect() } returns true
        // Deterministic reconnect: zero backoff removes the real-time race
        // that flaked this test on loaded CI runners (1s backoff + fixed
        // 5-6s latch windows routinely overshoot under parallel load).
        HermesWsClient.setReconnectBackoffForTest(0L)
        // The reconnect path refreshes the WS token over the network before
        // opening the socket. Stub it so no real HTTP call sits inside the
        // test's timing window (CI network latency was the dominant flake).
        mockkObject(DashboardSessionTokenRefresher)
        every { DashboardSessionTokenRefresher.fetch(any(), any()) } returns null

        var serverSocket1: WebSocket? = null
        var serverSocket2: WebSocket? = null

        val connect1Latch = CountDownLatch(1)
        val connect2Latch = CountDownLatch(1)

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverSocket1 = webSocket
                        connect1Latch.countDown()
                    }
                },
            ),
        )

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverSocket2 = webSocket
                        connect2Latch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()

        assertTrue("Failed initial connection", connect1Latch.await(15, TimeUnit.SECONDS))
        runBlocking {
            withTimeout(15_000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } }
        }
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)

        // Force server to close socket 1 to trigger reconnect
        serverSocket1?.close(1001, "Server shutting down")

        // Wait for the reconnect to trigger. With the test backoff forced to
        // 0ms the RECONNECTING state is transient and can be emitted + consumed
        // before this collector attaches, so we don't gate on the exact
        // intermediate state — we require it to have progressed (RECONNECTING
        // or later). The definitive proof is the second socket opening below.
        val statusNow = HermesWsClient.connectionStatus.value
        assertTrue(
            "Expected reconnect to trigger (RECONNECTING or later), was $statusNow",
            statusNow == ConnectionStatus.RECONNECTING ||
                statusNow == ConnectionStatus.CONNECTING ||
                statusNow == ConnectionStatus.CONNECTED,
        )

        // The client should now attempt to reconnect after the (0ms) backoff.
        // Wait for the second connection to hit the server. Generous ceiling:
        // loaded CI runners routinely stretch short wall-clock windows.
        try {
            assertTrue("Failed to reconnect", connect2Latch.await(30, TimeUnit.SECONDS))
            runBlocking {
                withTimeout(15_000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } }
            }
            assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)
        } finally {
            // Restore production backoff so later tests see the default, even
            // if an assertion above threw.
            HermesWsClient.setReconnectBackoffForTest(1_000L)
        }
    }

    // ── TEST-10: WS reconnect state recovery ────────────────────────────

    @Test
    fun testBackoffResetsOnSuccessfulConnect() {
        every { AuthManager.isAutoReconnect() } returns true
        // Pin the initial backoff so this test's reset assertion is
        // deterministic regardless of what earlier tests configured.
        HermesWsClient.setReconnectBackoffForTest(1_000L)

        val serverLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }

        // After connect, backoff should be back to initial
        val backoffField = HermesWsClient::class.java.getDeclaredField("currentBackoff")
        backoffField.isAccessible = true
        assertEquals(
            "Backoff should reset to initial after successful connect",
            1000L,
            backoffField.getLong(HermesWsClient),
        )
    }

    @Test
    fun testIntentionalClosePreventsReconnect() {
        every { AuthManager.isAutoReconnect() } returns true

        val serverLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }

        // Disconnect — this sets intentionalClose = true and cancels reconnect
        HermesWsClient.disconnect(clearPendingMessages = true)
        var replacementOpens = 0
        HermesWsClient.reconnectForNetworkChange(false)
        HermesWsClient.reconnectForNetworkChange(true) { replacementOpens++ }

        assertFalse(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)
        assertEquals(0, replacementOpens)
    }

    @Test
    fun testDoubleConnect_ignoresSecondCallWhenConnected() {
        val serverLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue(HermesWsClient.isConnected)

        // Second connect call should be a no-op
        HermesWsClient.connect()
        assertTrue(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)
    }

    @Test
    fun testStatusTransitionOnConnect() {
        val serverLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }
                },
            ),
        )

        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)

        HermesWsClient.connect()

        // After connect(), status should be CONNECTING
        var status: ConnectionStatus
        val deadline = System.currentTimeMillis() + 2000
        do {
            status = HermesWsClient.connectionStatus.value
            if (status == ConnectionStatus.CONNECTING) break
            Thread.sleep(10)
        } while (System.currentTimeMillis() < deadline)
        assertEquals(ConnectionStatus.CONNECTING, status)

        // Wait for actual connection
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)
    }

    @Test
    fun testDisconnectWhileReconnecting_transitionsToDisconnected() {
        every { AuthManager.isAutoReconnect() } returns true

        val connectLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        connectLatch.countDown()
                    }
                },
            ),
        )

        // Enqueue a second response for reconnect attempt
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        // No-op — should be cancelled
                    }
                },
            ),
        )

        HermesWsClient.connect()
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS))
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }

        // Disconnect (sets intentionalClose) — after this, reconnect should be prevented
        HermesWsClient.disconnect(clearPendingMessages = true)
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)
        assertFalse(HermesWsClient.isConnected)
    }

    // ── Issue #635: gated-mode WS ticket fetch must not be blocked by a
    // missing bare-name session cookie (HTTPS deployments prefix it with
    // __Host- / __Secure-). ────────────────────────────────────────────────

    @Test
    fun testGatedMode_attemptsTicketFetchWithoutBareCookie() {
        // Force gated mode (ws auth via ticket, not loopback token).
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config
                        .ServerStoreState(wsAuthParam = "ticket")
            }
        // No bare-name session cookie present (the prefixed one is server-side).
        every { AuthManager.getSessionCookie() } returns null
        // setToken is exercised by the ticket refresh; stub it (AuthManager is
        // a mocked object, so unstubbed calls throw).
        every { AuthManager.setToken(any()) } returns Unit

        // Separate server for the ticket endpoint so its queue can't interleave
        // with the WebSocket upgrade on the main mockWebServer.
        val ticketServer = MockWebServer()
        ticketServer.start()
        every { AuthManager.endpointForBuild() } returns
            ServerEndpoint.parse(
                ticketServer.url("/").toString(),
                CleartextPolicy.ALLOW_WITH_WARNING,
            )
        ticketServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ticket":"refreshed-ticket"}"""),
        )

        val connectLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        connectLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()

        // Before the fix, a null bare cookie short-circuited to AUTH_EXPIRED and
        // the ticket endpoint was NEVER called. After the fix it is attempted,
        // so the connection reaches CONNECTED.
        assertTrue(
            "Gated WS ticket fetch should be attempted even without a bare cookie",
            connectLatch.await(5, TimeUnit.SECONDS),
        )
        // The server-side onOpen latch fires a hair before the client receives
        // the 101 handshake and WsListenerImpl sets CONNECTED — await the real
        // status transition (as every other connect test does) instead of a
        // racy read that can observe CONNECTING.
        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
        }
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)

        ticketServer.shutdown()
    }

    @Test
    fun testGatedMode_permanentTicketFailureStopsRetrying() {
        every { AuthManager.isAutoReconnect() } returns true
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config
                        .ServerStoreState(wsAuthParam = "ticket")
            }

        val ticketServer = MockWebServer()
        ticketServer.start()
        ticketServer.enqueue(MockResponse().setResponseCode(400))
        every { AuthManager.endpointForBuild() } returns
            ServerEndpoint.parse(
                ticketServer.url("/").toString(),
                CleartextPolicy.ALLOW_WITH_WARNING,
            )

        HermesWsClient.connect()

        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)
        ticketServer.shutdown()
    }

    @Test
    fun testGatedMode_transientTicketFailureKeepsReconnectFlow() {
        every { AuthManager.isAutoReconnect() } returns true
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config
                        .ServerStoreState(wsAuthParam = "ticket")
            }

        val unavailableTicketServer = MockWebServer()
        unavailableTicketServer.start()
        val unavailableEndpoint = unavailableTicketServer.url("/").toString()
        unavailableTicketServer.shutdown()
        every { AuthManager.endpointForBuild() } returns
            ServerEndpoint.parse(
                unavailableEndpoint,
                CleartextPolicy.ALLOW_WITH_WARNING,
            )

        HermesWsClient.connect()

        assertEquals(ConnectionStatus.RECONNECTING, HermesWsClient.connectionStatus.value)
    }

    @Test
    fun testGatedMode_parsesEscapedTicketFromRealJsonShape() {
        every { AuthManager.isAutoReconnect() } returns true
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config
                        .ServerStoreState(wsAuthParam = "ticket")
            }
        every { AuthManager.setToken(any()) } returns Unit

        val ticketServer = MockWebServer()
        ticketServer.start()
        // Real backend shape with an ESCAPED QUOTE inside the ticket value.
        // Built from char vals so nobody has to count backslashes again:
        //   wire body == {"ticket":"a<backslash><dquote>y","ttl_seconds":30}
        // The old regex ([^"]+) stops at the inner dquote and extracts "a<bs>",
        // which never equals the real ticket — only the JSON parser survives.
        val bs = 92.toChar() // backslash
        val dq = 34.toChar() // dquote
        val wireTicket = "a" + bs + dq + "y"

        fun jsonEscape(s: String): String = s.replace("$bs", "$bs$bs").replace("$dq", "$bs$dq")
        val wireBody = """{"ticket":"${jsonEscape(wireTicket)}","ttl_seconds":30}"""
        ticketServer.enqueue(MockResponse().setResponseCode(200).setBody(wireBody))
        every { AuthManager.endpointForBuild() } returns
            ServerEndpoint.parse(
                ticketServer.url("/").toString(),
                CleartextPolicy.ALLOW_WITH_WARNING,
            )

        HermesWsClient.connect()

        io.mockk.verify(timeout = 5000) { AuthManager.setToken(wireTicket) }
        ticketServer.shutdown()
    }

    @Test
    fun testStaleSocketIsCancelledByWatchdog() {
        var serverWebSocket: WebSocket? = null
        val serverLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverWebSocket = webSocket
                        serverLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        // Any inbound frame refreshes liveness — send nothing,
                        // simulating a dead NAT'd link where only client pings flow.
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
        }
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS))
        assertTrue(HermesWsClient.isConnected)

        // Backdate liveness past the staleness threshold, then force one
        // synchronous watchdog pass. Deterministic — no real-time waiting.
        HermesWsClient.forceHealthCheckForTest(staleMillis = 200_000L)

        // Cancel fires onFailure on an OkHttp thread; with auto-reconnect off
        // (setUp default) the terminal state settles at DISCONNECTED.
        val deadline = System.currentTimeMillis() + 5000
        while (HermesWsClient.isConnected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertFalse("Stale socket was not cancelled", HermesWsClient.isConnected)
        assertTrue(
            "Status stuck at CONNECTED after stale cancel",
            HermesWsClient.connectionStatus.value != ConnectionStatus.CONNECTED,
        )
    }

    @Test
    fun testSequenceNumberDeduplication() {
        var serverWebSocket: WebSocket? = null
        val serverLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverWebSocket = webSocket
                        serverLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
        }
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS))
        val ws = serverWebSocket
        assertNotNull(ws)

        // PR01 / R18: SharedFlow has no replay; subscribe before the server sends.
        // Thread-safe storage and receive barriers replace fixed sleeps.
        val receivedTokens = java.util.concurrent.CopyOnWriteArrayList<String>()
        val firstToken = CountDownLatch(1)
        val secondToken = CountDownLatch(1)
        val collectorJob =
            kotlinx.coroutines
                .CoroutineScope(
                    kotlinx.coroutines.Dispatchers.IO,
                ).launch(start = CoroutineStart.UNDISPATCHED) {
                    HermesWsClient.events.collect { event ->
                        if (event is WsEvent.MessageToken) {
                            receivedTokens.add(event.token)
                            if (event.token == "A") firstToken.countDown()
                            if (event.token == "B") secondToken.countDown()
                        }
                    }
                }

        // Send seq 1
        ws!!.send(
            """{"method":"event","params":{"type":"message.token","session_id":"s1","seq":1,"payload":{"text":"A"}}}""",
        )
        assertTrue("First token was not delivered", firstToken.await(5, TimeUnit.SECONDS))
        assertEquals(1, HermesWsClient.getSeqWatermarks()["s1"])
        assertEquals(listOf("A"), receivedTokens)

        // Send duplicate seq 1 -> should be dropped
        ws.send(
            """{"method":"event","params":{"type":"message.token","session_id":"s1","seq":1,"payload":{"text":"A-dup"}}}""",
        )
        // Send seq 2 -> should be accepted
        ws.send(
            """{"method":"event","params":{"type":"message.token","session_id":"s1","seq":2,"payload":{"text":"B"}}}""",
        )
        // Ordered WebSocket delivery makes B a barrier for the preceding duplicate.
        assertTrue("Second token was not delivered", secondToken.await(5, TimeUnit.SECONDS))
        assertEquals(2, HermesWsClient.getSeqWatermarks()["s1"])
        assertEquals(listOf("A", "B"), receivedTokens)

        collectorJob.cancel()
    }

    @Test
    fun testEpochChangeClearsWatermarks() {
        var serverWebSocket: WebSocket? = null
        val serverLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverWebSocket = webSocket
                        serverLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
        }
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS))
        val ws = serverWebSocket
        assertNotNull(ws)

        // Initial gateway.ready with epoch-1
        ws!!.send(
            """{"method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"epoch-1"}}}""",
        )
        Thread.sleep(100)
        HermesWsClient.setSeqWatermark("s1", 10)
        assertEquals(10, HermesWsClient.getSeqWatermarks()["s1"])

        // Second gateway.ready with new epoch-2 -> backend restarted, watermarks cleared
        ws.send(
            """{"method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"epoch-2"}}}""",
        )
        Thread.sleep(100)
        assertTrue(HermesWsClient.getSeqWatermarks().isEmpty())
    }

    @Test
    fun testReplayOnReconnectFlow() {
        var serverWebSocket: WebSocket? = null
        val serverLatch = CountDownLatch(1)
        val requestLatch = CountDownLatch(1)
        var receivedMethod: String? = null

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverWebSocket = webSocket
                        serverLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (text.contains(WsMethods.SESSION_EVENTS_SINCE)) {
                            receivedMethod = WsMethods.SESSION_EVENTS_SINCE
                            // Extract ID from JSON-RPC request
                            val id = Regex(""""id":"([^"]+)"""").find(text)?.groupValues?.get(1) ?: "1"
                            webSocket.send(
                                """{"jsonrpc":"2.0","id":"$id","result":{"epoch":"ep1","events":[{"type":"message.token","session_id":"s1","seq":6,"payload":{"text":"replayed"}}]}}""",
                            )
                            requestLatch.countDown()
                        }
                    }
                },
            ),
        )

        HermesWsClient.setSeqWatermark("s1", 5)
        HermesWsClient.connect()
        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
        }
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS))
        assertTrue(requestLatch.await(5, TimeUnit.SECONDS))
        assertEquals(WsMethods.SESSION_EVENTS_SINCE, receivedMethod)

        // Wait for replay processing
        Thread.sleep(200)
        assertEquals(6, HermesWsClient.getSeqWatermarks()["s1"])
    }

    @Test
    fun testPingMethodConstant() {
        assertEquals("ping", WsMethods.PING)
    }

    @Test
    fun testPingMeasuresLatencyAndUpdatesTimestamp() {
        var serverWebSocket: WebSocket? = null
        val serverLatch = CountDownLatch(1)
        val pingLatch = CountDownLatch(1)
        var pingReceived = false

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverWebSocket = webSocket
                        serverLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (text.contains(""""method":"ping"""")) {
                            pingReceived = true
                            val id = Regex(""""id":"([^"]+)"""").find(text)?.groupValues?.get(1) ?: "1"
                            webSocket.send(
                                """{"jsonrpc":"2.0","id":"$id","result":{"pong":true,"timestamp":1700000000.0}}""",
                            )
                            pingLatch.countDown()
                        }
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
        }
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS))

        val latency =
            runBlocking {
                HermesWsClient.ping(timeoutMs = 5000)
            }

        assertTrue(pingLatch.await(5, TimeUnit.SECONDS))
        assertTrue(pingReceived)
        assertTrue(latency >= 0)
        assertNotNull(HermesWsClient.lastLatencyMs.value)
        assertEquals(latency, HermesWsClient.lastLatencyMs.value)
        assertTrue(HermesWsClient.lastPongTimestamp > 0)
    }
}
