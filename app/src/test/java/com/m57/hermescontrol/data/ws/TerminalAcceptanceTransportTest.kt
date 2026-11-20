package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.transport.DashboardRpcTransport
import com.m57.hermescontrol.domain.hermes.*
import com.m57.hermescontrol.ui.chat.RichAnswerSubmissionTransport
import com.m57.hermescontrol.ui.chat.components.quotedReplyMessage
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** SYNTHETIC frozen-contract frames; actual registry/listener/transport, never live traffic. */
class TerminalAcceptanceTransportTest {
    private val profile = MutableStateFlow<String?>("profile-a")
    private val epoch = MutableStateFlow(2L)
    private lateinit var socket: WebSocket
    private lateinit var listener: WebSocketListener
    private lateinit var scope: ConnectionScope
    private val session = SessionRef("runtime", "saved")
    private val peer = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val sent = mutableListOf<JsonObject>()
    private var duringSend: ((JsonObject) -> Unit)? = null

    private fun field(name: String) = HermesWsClient::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun pending() = field("pendingCalls").get(HermesWsClient) as Map<*, *>

    private fun request() = sent.last { it["method"]?.jsonPrimitive?.content == WsMethods.PROMPT_SUBMIT }

    private fun id(request: JsonObject = request()) = request["id"]!!.jsonPrimitive.content

    private fun nonce(request: JsonObject = request()) =
        request["params"]!!
            .jsonObject["terminal_acceptance"]!!
            .jsonObject["client_attempt_nonce"]!!
            .jsonPrimitive.content

    private fun response(
        request: JsonObject,
        body: String,
    ) = listener.onMessage(socket, """{"jsonrpc":"2.0","id":"${id(request)}","result":$body}""")

    private fun ack(request: JsonObject = request()) = response(request, """{"status":"streaming","user_row_id":41}""")

    private fun wire(
        payload: JsonObject,
        target: WebSocket = socket,
        source: WebSocketListener = listener,
    ) = source.onMessage(
        target,
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put(
                "params",
                buildJsonObject {
                    put("type", "message.complete")
                    put("session_id", "runtime")
                    put("payload", payload)
                },
            )
        }.toString(),
    )

    private fun terminal(request: JsonObject = request()): JsonObject =
        Json
            .parseToJsonElement(
                """{"text":"synthetic full body","status":"complete","partial":false,"response_reused":false,"response_transformed":false,"response_previewed":false,"persisted_turn":{"row_ids":[41,42],"complete":true,"user_row_id":41,"user_row_ids":[41],"final_assistant_row_id":42},"terminal_acceptance":{"version":1,"method":"prompt.submit","original_rpc_request_id":"${id(
                    request,
                )}","client_attempt_nonce":"${nonce(
                    request,
                )}","peer_id":"$peer","disposition":"streaming","runtime_session_id":"runtime","target_session_id":"saved","transcript_session_id":"saved","origin_user_row_id":41}}""",
            ).jsonObject

    private suspend fun negotiate(
        body: String = """{"terminal_acceptance":{"version":1,"mode":"local-ordinary-text-v1","peer_id":"$peer"}}""",
    ) {
        val read = HermesWsClient.request("gateway.capabilities")
        response(sent.last(), body)
        read.await()
    }

    private fun bind(target: WebSocket = socket): Pair<ConnectionScope, WebSocketListener> {
        val generation = (field("connectionGeneration").get(HermesWsClient) as AtomicInteger).get()
        val bound = ConnectionScope("synthetic-server", "connection", profile.value, "process", generation)
        field("webSocket").set(HermesWsClient, target)
        field("connectionBaseUrl").set(HermesWsClient, "https://synthetic.invalid/")
        (field("connected").get(HermesWsClient) as AtomicBoolean).set(true)
        (field("intentionalClose").get(HermesWsClient) as AtomicBoolean).set(false)
        @Suppress("UNCHECKED_CAST")
        (field("_connectionScope").get(HermesWsClient) as MutableStateFlow<ConnectionScope?>).value = bound
        val ctor =
            Class
                .forName(
                    "com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl",
                ).declaredConstructors
                .single()
                .apply {
                    isAccessible =
                        true
                }
        return bound to (ctor.newInstance(generation, bound) as WebSocketListener)
    }

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(AuthManager)
        every { AuthManager.activeProfileId } returns profile
        every { AuthManager.connectionEpoch } returns epoch
        every { AuthManager.getSelectedProfileId() } returns "connection"
        every { AuthManager.getBaseUrl() } returns "https://synthetic.invalid/"
        every { AuthManager.isAutoReconnect() } returns false
        HermesWsClient.disconnect(clearPendingMessages = true)
        HermesWsClient.rejectAllPending()
        HermesWsClient.setAppForeground(true)
        socket = mockk(relaxed = true)
        every { socket.send(any<String>()) } answers
            {
                val r = Json.parseToJsonElement(firstArg<String>()).jsonObject
                sent += r
                duringSend?.invoke(r)
                true
            }
        val pair = bind()
        scope = pair.first
        listener = pair.second
    }

    @After fun cleanup() {
        HermesWsClient.disconnect(clearPendingMessages = true)
        HermesWsClient.rejectAllPending()
        unmockkAll()
    }

    @Test fun surfaceOnlyAdvertisementSendsPlainTextWithoutEnablingTerminalAcceptance() =
        runBlocking {
            negotiate("""{"client_surfaces":["hud","mobile-v3"]}""")
            assertTrue(HermesWsClient.supportsClientSurface(scope, "mobile-v3"))
            duringSend = { r -> if (r["method"]?.jsonPrimitive?.content == WsMethods.PROMPT_SUBMIT) ack(r) }
            val adapter =
                com.m57.hermescontrol.ui.chat
                    .RichAnswerSubmissionTransport(DashboardRpcTransport())
            val result = adapter.submitPrompt(scope, session, "図で説明して")
            assertTrue(result is SubmissionResult.Confirmed)
            val params = request()["params"]!!.jsonObject
            assertEquals("図で説明して", params["text"]!!.jsonPrimitive.content)
            assertEquals("mobile-v3", params["surface"]!!.jsonPrimitive.content)
            assertFalse(params.containsKey("terminal_acceptance"))
        }

    @Test fun olderSurfaceAdvertisementUsesLegacySuffixAndNoSurface() =
        runBlocking {
            negotiate("""{"client_surfaces":["mobile-v1","mobile-v2"]}""")
            duringSend = { r -> if (r["method"]?.jsonPrimitive?.content == WsMethods.PROMPT_SUBMIT) ack(r) }
            val adapter =
                com.m57.hermescontrol.ui.chat
                    .RichAnswerSubmissionTransport(DashboardRpcTransport())
            adapter.submitPrompt(scope, session, "図で説明して")
            val params = request()["params"]!!.jsonObject
            assertTrue(params["text"]!!.jsonPrimitive.content.contains("<hermes-mobile-display-context>"))
            assertFalse(params.containsKey("surface"))
        }

    @Test fun attachmentPromptWaitsForInFlightNegotiationAndKeepsTheImageOnTheSameSession() =
        runBlocking {
            val adapter = RichAnswerSubmissionTransport(DashboardRpcTransport())
            val photo = Attachment(uri = "content://test/photo", name = "photo.jpg", mimeType = "image/jpeg", size = 16)
            duringSend = { r ->
                when (r["method"]?.jsonPrimitive?.content) {
                    WsMethods.IMAGE_ATTACH_BYTES -> response(r, """{"attached":true}""")
                    WsMethods.PROMPT_SUBMIT -> ack(r)
                }
            }
            listener.onMessage(socket, """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready"}}""")
            for (caption in listOf("画像を確認して", "")) {
                assertTrue(
                    adapter.attach(scope, session, photo, "data:image/jpeg;base64,AA==") is SubmissionResult.Confirmed,
                )
                val photoRequest = sent.last()
                assertEquals(
                    session.runtimeId,
                    photoRequest["params"]!!.jsonObject["session_id"]!!.jsonPrimitive.content,
                )
                val submitted =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        adapter.submitPrompt(scope, session, caption)
                    }
                if (caption.isNotEmpty()) {
                    assertFalse(submitted.isCompleted)
                    assertEquals(photoRequest, sent.last())
                    val capabilityRequest =
                        sent.single {
                            it["method"]?.jsonPrimitive?.content == "gateway.capabilities"
                        }
                    response(capabilityRequest, """{"client_surfaces":["mobile-v3"]}""")
                }
                assertTrue(submitted.await() is SubmissionResult.Confirmed)
                val params = request()["params"]!!.jsonObject
                assertEquals(session.runtimeId, params["session_id"]!!.jsonPrimitive.content)
                assertEquals(caption, params["text"]!!.jsonPrimitive.content)
                assertEquals("mobile-v3", params["surface"]!!.jsonPrimitive.content)
            }
        }

    @Test fun quotedFileFollowUpUsesTheSpecialistScopeAndPreservesQueueFlagAndText() =
        runBlocking {
            val adapter = RichAnswerSubmissionTransport(DashboardRpcTransport())
            val attachment =
                Attachment(uri = "content://test/report", name = "report.txt", mimeType = "text/plain", size = 16)
            val ref = "@file:/srv/.hermes/attachments/report.txt"
            duringSend = { r ->
                when (r["method"]?.jsonPrimitive?.content) {
                    "gateway.capabilities" -> response(r, """{"client_surfaces":["mobile-v3"]}""")
                    WsMethods.FILE_ATTACH -> response(r, """{"attached":true,"ref_text":"$ref"}""")
                    WsMethods.PROMPT_SUBMIT -> response(r, """{"status":"queued"}""")
                }
            }
            val uploaded = adapter.attach(scope, session, attachment, "data:text/plain;base64,AA==")
            assertEquals(ref, (uploaded as SubmissionResult.Confirmed).value)
            val text = "$ref\n\n" + quotedReplyMessage("前回の内容", "ファイルも確認して")
            val result = adapter.submitPrompt(scope, session, text, queued = true)
            assertEquals(PromptAcceptance.QUEUED, (result as SubmissionResult.Confirmed).value)
            val params = request()["params"]!!.jsonObject
            assertEquals(scope.serverProfileId, params["profile"]!!.jsonPrimitive.content)
            assertEquals(session.runtimeId, params["session_id"]!!.jsonPrimitive.content)
            assertEquals(text, params["text"]!!.jsonPrimitive.content)
            assertEquals("mobile-v3", params["surface"]!!.jsonPrimitive.content)
            assertTrue(params["queued"]!!.jsonPrimitive.boolean)
        }

    @Test fun surfaceAdvertisementExpiresWithAuthenticationProfileAndSocket() =
        runBlocking {
            negotiate("""{"client_surfaces":["mobile-v3"]}""")
            epoch.value = 4L
            assertFalse(HermesWsClient.supportsClientSurface(scope, "mobile-v3"))
            negotiate("""{"client_surfaces":["mobile-v3"]}""")
            assertTrue(HermesWsClient.supportsClientSurface(scope, "mobile-v3"))
            profile.value = "profile-b"
            assertFalse(HermesWsClient.supportsClientSurface(scope, "mobile-v3"))
            profile.value = "profile-a"
            bind(mockk(relaxed = true))
            assertFalse(HermesWsClient.supportsClientSurface(scope, "mobile-v3"))
        }

    @Test fun unadvertisedSurfaceIsRejectedBeforeSendingAnyPrompt() =
        runBlocking {
            negotiate("""{"client_surfaces":["mobile-v2"]}""")
            val before = sent.size
            assertEquals(
                SubmissionResult.NotSent,
                DashboardRpcTransport().submitPrompt(scope, session, "exact text", surface = "mobile-v3"),
            )
            assertEquals(before, sent.size)
        }

    @Test fun immediateTerminalInsideSend_isRegisteredBeforeHandoffAndNeverCompletesRpcResponse() =
        runBlocking {
            negotiate()
            var exact: Any? = null
            duringSend =
                { r ->
                    if (r["method"]?.jsonPrimitive?.content ==
                        WsMethods.PROMPT_SUBMIT
                    ) {
                        exact = pending()[id(r)]
                        wire(terminal(r))
                    }
                }
            val call =
                HermesWsClient.writePromptScoped(
                    scope,
                    session,
                    mapOf(
                        "session_id" to session.runtimeId,
                        "text" to "ordinary",
                    ),
                )
            assertNotNull(exact)
            assertTrue(call.accepted)
            assertFalse("No forged JSON-RPC ACK", call.response.isCompleted)
            val proof = withTimeout(2000) { call.terminalProof!!.await() }
            assertEquals("synthetic full body", (proof.observation.event as WsEvent.MessageComplete).text)
            assertEquals(id(), proof.receipt.originalRequestId)
            assertFalse(pending().containsKey(id()))
            val job =
                exact!!
                    .javaClass
                    .getDeclaredField("timeoutJob")
                    .apply { isAccessible = true }
                    .get(exact) as Job?
            assertTrue(job == null || !job.isActive)
            assertEquals(1, sent.count { it["method"]?.jsonPrimitive?.content == WsMethods.PROMPT_SUBMIT })
        }

    @Test fun receiptFirst_thenLateAck_keepsOriginalSourceAndRetainedObservation() =
        runBlocking {
            negotiate()
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    DashboardRpcTransport().submitPrompt(scope, session, "ordinary", false, false)
                }
            val original = request()
            wire(terminal(original))
            val accepted = withTimeout(2000) { result.await() } as SubmissionResult.Confirmed
            assertEquals(SubmissionProofSource.TERMINAL_RECEIPT, accepted.proofSource)
            assertEquals(PromptEvidence(41), accepted.promptEvidence)
            assertEquals("synthetic full body", (accepted.terminalObservation?.event as WsEvent.MessageComplete).text)
            ack(original)
            wire(terminal(original))
            assertEquals(accepted, result.await())
            assertTrue(pending().isEmpty())
        }

    @Test fun ackFirst_thenReceipt_keepsRpcSource() =
        runBlocking {
            negotiate()
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    DashboardRpcTransport().submitPrompt(scope, session, "ordinary", false, false)
                }
            val original = request()
            ack(original)
            wire(terminal(original))
            val accepted = withTimeout(2000) { result.await() } as SubmissionResult.Confirmed
            assertEquals(SubmissionProofSource.RPC_ACK, accepted.proofSource)
            assertNull(accepted.terminalObservation)
            assertTrue(pending().isEmpty())
        }

    @Test fun wrongCorrelationAndUnsafeMetadata_neverRetireTheExactPendingCall() =
        runBlocking {
            negotiate()
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    DashboardRpcTransport().submitPrompt(scope, session, "ordinary", false, false)
                }
            val original = request()
            val payload = terminal(original)
            val receipt = payload["terminal_acceptance"]!!.jsonObject
            val badFields =
                mapOf(
                    "original_rpc_request_id" to JsonPrimitive("wrong"),
                    "client_attempt_nonce" to JsonPrimitive("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                    "peer_id" to JsonPrimitive("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                    "method" to JsonPrimitive("session.redirect"),
                    "runtime_session_id" to JsonPrimitive("foreign"),
                    "target_session_id" to JsonPrimitive("foreign"),
                    "transcript_session_id" to JsonPrimitive("foreign"),
                    "origin_user_row_id" to JsonPrimitive(99),
                    "version" to JsonPrimitive("1"),
                    "disposition" to JsonPrimitive("queued"),
                )
            val exact = pending()[id()]
            for ((key, value) in badFields) {
                wire(JsonObject(payload + ("terminal_acceptance" to JsonObject(receipt + (key to value)))))
                assertFalse("wrong $key must not confirm", result.isCompleted)
                assertSame(exact, pending()[id()])
            }
            for (key in listOf(
                "persisted_turn",
                "partial",
                "status",
                "response_reused",
                "response_transformed",
                "response_previewed",
            )) {
                wire(JsonObject(payload - key))
                assertSame("missing $key must not retire the exact call", exact, pending()[id()])
                assertFalse("missing $key must not confirm", result.isCompleted)
            }
            for (key in listOf("partial", "response_reused", "response_transformed")) {
                wire(JsonObject(payload + (key to JsonPrimitive(true))))
                assertSame("unsafe $key must not retire", exact, pending()[id()])
                assertFalse("unsafe $key", result.isCompleted)
            }
            for (key in listOf("complete", "user_row_id", "user_row_ids", "final_assistant_row_id")) {
                val manifest = payload["persisted_turn"]!!.jsonObject
                wire(JsonObject(payload + ("persisted_turn" to JsonObject(manifest - key))))
                assertSame("manifest missing $key must not retire", exact, pending()[id()])
                assertFalse("manifest missing $key", result.isCompleted)
            }
            for (rows in listOf(listOf(42, 41), listOf(41, 42, 99))) {
                val manifest = payload["persisted_turn"]!!.jsonObject
                wire(
                    JsonObject(
                        payload +
                            (
                                "persisted_turn" to
                                    JsonObject(manifest + ("row_ids" to JsonArray(rows.map(::JsonPrimitive))))
                            ),
                    ),
                )
                assertSame("Origin/final must be actual first/last manifest addresses", exact, pending()[id()])
            }
            wire(JsonObject(payload + ("text" to JsonPrimitive("MEDIA:/synthetic/result.png"))))
            assertSame("Media must not retire", exact, pending()[id()])
            assertFalse("Media terminals are outside local ordinary text v1", result.isCompleted)
            com.m57.hermescontrol.data.session.ActiveSessionHolder
                .setScoped("runtime", "foreign", scope)
            wire(payload)
            assertSame("contradictory saved binding must not retire", exact, pending()[id()])
            assertFalse("A contradictory known saved binding must fail closed", result.isCompleted)
            com.m57.hermescontrol.data.session.ActiveSessionHolder
                .setScoped("runtime", "saved", scope)
            wire(payload, mockk(relaxed = true))
            assertFalse(result.isCompleted)
            wire(payload)
            assertTrue(withTimeout(2000) { result.await() } is SubmissionResult.Confirmed)
        }

    @Test fun authEpochAndProfileRoundTrip_cannotReuseNegotiationOrProof() =
        runBlocking {
            negotiate()
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    DashboardRpcTransport().submitPrompt(scope, session, "ordinary", false, false)
                }
            val original = request()
            profile.value = "profile-b"
            epoch.value = 3
            profile.value = "profile-a"
            epoch.value = 4
            wire(terminal(original))
            assertFalse(result.isCompleted)
            result.cancelAndJoin()
            assertTrue(pending().isEmpty())
            val fresh =
                async(start = CoroutineStart.UNDISPATCHED) {
                    DashboardRpcTransport().submitPrompt(scope, session, "fresh", false, false)
                }
            assertNull(request()["params"]!!.jsonObject["terminal_acceptance"])
            ack()
            assertEquals(SubmissionProofSource.RPC_ACK, (fresh.await() as SubmissionResult.Confirmed).proofSource)
        }

    @Test fun invalidMissingOldBackend_unknownTargetQueueAndControl_sendUntaggedOnlyOnce() =
        runBlocking {
            for (body in listOf(
                "{}",
                """{"terminal_acceptance":{"version":2,"mode":"local-ordinary-text-v1","peer_id":"$peer"}}""",
                """{"terminal_acceptance":{"version":1,"mode":"other","peer_id":"$peer"}}""",
                """{"terminal_acceptance":{"version":1,"mode":"local-ordinary-text-v1","peer_id":" $peer"}}""",
            )) {
                negotiate(body)
                val result =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        DashboardRpcTransport().submitPrompt(scope, session, "ordinary", false, false)
                    }
                assertNull(request()["params"]!!.jsonObject["terminal_acceptance"])
                ack()
                assertTrue(result.await() is SubmissionResult.Confirmed)
            }
            negotiate()
            for ((target, text, queued) in listOf(
                Triple(SessionRef("runtime"), "ordinary", false),
                Triple(session, "ordinary", true),
                Triple(session, "/command", false),
                Triple(session, "@file: synthetic", false),
            )) {
                val result =
                    async(
                        start = CoroutineStart.UNDISPATCHED,
                    ) { DashboardRpcTransport().submitPrompt(scope, target, text, false, queued) }
                assertNull(request()["params"]!!.jsonObject["terminal_acceptance"])
                ack()
                result.await()
            }
        }

    @Test fun actualShortDeadline_lateProofCannotResolveFreshCall() =
        runBlocking {
            negotiate()
            val params = mapOf("session_id" to "runtime", "text" to "ordinary")
            val call = HermesWsClient.writePromptScoped(scope, session, params, 40)
            val original = request()
            try {
                withTimeout(2000) { call.response.await() }
                fail("must expire")
            } catch (
                _: HermesWsClient.HermesRpcException,
            ) {
            }
            assertFalse(pending().containsKey(id(original)))
            val fresh = HermesWsClient.writePromptScoped(scope, session, params, 5000)
            wire(terminal(original))
            assertFalse(fresh.terminalProof!!.isCompleted)
            assertFalse(call.terminalProof!!.isCompleted)
            wire(terminal())
            assertEquals(
                id(),
                fresh.terminalProof!!
                    .await()
                    .receipt.originalRequestId,
            )
        }

    @Test fun ackReceiptRace_exactlyOneLocalWinnerAndNoRpcFabrication() =
        runBlocking {
            negotiate()
            repeat(40) {
                val call =
                    HermesWsClient.writePromptScoped(
                        scope,
                        session,
                        mapOf(
                            "session_id" to "runtime",
                            "text" to "race",
                        ),
                    )
                val original = request()
                val payload = terminal(original)
                val gate = CompletableDeferred<Unit>()
                val ackJob =
                    launch(Dispatchers.Default) {
                        gate.await()
                        ack(original)
                    }
                val proofJob =
                    launch(Dispatchers.Default) {
                        gate.await()
                        wire(payload)
                    }
                gate.complete(Unit)
                joinAll(ackJob, proofJob)
                assertTrue(
                    "Exactly ONE ACK/proof deferred completes",
                    call.response.isCompleted.xor(call.terminalProof!!.isCompleted),
                )
                assertFalse(pending().containsKey(id(original)))
                if (call.terminalProof!!.isCompleted) {
                    assertFalse(
                        "Proof winner must never fake the RPC ACK",
                        call.response.isCompleted,
                    )
                }
            }
            verify(exactly = 0) { socket.cancel() }
        }

    @Test fun cancellationRemovesOnlyItsCall_lateReplayCannotSettleTheNextAttempt() =
        runBlocking {
            negotiate()
            val transport = DashboardRpcTransport()
            val old =
                async(
                    start = CoroutineStart.UNDISPATCHED,
                ) { transport.submitPrompt(scope, session, "old", false, false) }
            val original = request()
            val proof = terminal(original)
            old.cancelAndJoin()
            assertFalse(pending().containsKey(id(original)))
            val fresh =
                async(
                    start = CoroutineStart.UNDISPATCHED,
                ) { transport.submitPrompt(scope, session, "fresh", false, false) }
            val exact = pending()[id()]
            wire(proof)
            ack(original)
            assertSame(exact, pending()[id()])
            assertFalse(fresh.isCompleted)
            wire(terminal())
            assertEquals(
                SubmissionProofSource.TERMINAL_RECEIPT,
                (fresh.await() as SubmissionResult.Confirmed).proofSource,
            )
        }

    @Test fun oddAuthEpochAndNegotiationAuthRaceStayUntagged() =
        runBlocking {
            negotiate()
            epoch.value = 3
            val odd =
                async(start = CoroutineStart.UNDISPATCHED) {
                    DashboardRpcTransport().submitPrompt(scope, session, "ordinary", false, false)
                }
            assertNull(request()["params"]!!.jsonObject["terminal_acceptance"])
            ack()
            odd.await()
            epoch.value = 4
            val capabilities = HermesWsClient.request("gateway.capabilities")
            val readRequest = sent.last()
            epoch.value = 6
            response(
                readRequest,
                """{"terminal_acceptance":{"version":1,"mode":"local-ordinary-text-v1","peer_id":"$peer"}}""",
            )
            capabilities.await()
            val fresh =
                async(start = CoroutineStart.UNDISPATCHED) {
                    DashboardRpcTransport().submitPrompt(scope, session, "ordinary", false, false)
                }
            assertNull(request()["params"]!!.jsonObject["terminal_acceptance"])
            ack()
            fresh.await()
            Unit
        }

    @Test fun scopeSocketGenerationFence_doesNotRetireFreshExpectation() =
        runBlocking {
            negotiate()
            val oldScope = scope
            val oldListener = listener
            val oldSocket = socket
            val old =
                HermesWsClient.writePromptScoped(
                    scope,
                    session,
                    mapOf("session_id" to "runtime", "text" to "old"),
                )
            val original = request()
            val oldProof = terminal(original)
            HermesWsClient.disconnect(clearPendingMessages = true)
            socket = mockk(relaxed = true)
            every { socket.send(any<String>()) } answers
                {
                    sent += Json.parseToJsonElement(firstArg<String>()).jsonObject
                    true
                }
            val pair = bind()
            scope = pair.first
            listener = pair.second
            assertNotEquals(oldScope, scope)
            negotiate()
            val fresh =
                HermesWsClient.writePromptScoped(
                    scope,
                    session,
                    mapOf("session_id" to "runtime", "text" to "fresh"),
                )
            val current = terminal()
            val exact = pending()[id()]
            wire(current, oldSocket, oldListener)
            wire(current, socket, oldListener)
            wire(current, oldSocket, listener)
            wire(oldProof)
            assertSame(exact, pending()[id()])
            assertFalse(fresh.terminalProof!!.isCompleted)
            wire(current)
            assertEquals(
                id(),
                fresh.terminalProof!!
                    .await()
                    .receipt.originalRequestId,
            )
            assertFalse(old.terminalProof!!.isCompleted)
        }

    @Test fun renegotiatedPeerOnSameSocket_invalidatesEarlierExpectation() =
        runBlocking {
            negotiate()
            val call =
                HermesWsClient.writePromptScoped(
                    scope,
                    session,
                    mapOf(
                        "session_id" to "runtime",
                        "text" to "ordinary",
                    ),
                )
            val original = request()
            val proof = terminal(original)
            val exact = pending()[id(original)]
            negotiate(
                """{"terminal_acceptance":{"version":1,"mode":"local-ordinary-text-v1","peer_id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}}""",
            )
            wire(proof)
            assertSame("Only the CURRENT negotiated peer can prove acceptance", exact, pending()[id(original)])
            assertFalse(call.terminalProof!!.isCompleted)
            call.release?.invoke()
            Unit
        }

    @Test fun gatewayReady_negotiatesOnceUsingExistingReadMethod() =
        runBlocking {
            val frame = """{"method":"event","params":{"type":"gateway.ready","payload":{}}}"""
            listener.onMessage(socket, frame)
            listener.onMessage(socket, frame)
            assertEquals(
                "One bounded existing capabilities read, never per-submit polling",
                1,
                sent.count {
                    it["method"]?.jsonPrimitive?.content ==
                        "gateway.capabilities"
                },
            )
            response(sent.single { it["method"]?.jsonPrimitive?.content == "gateway.capabilities" }, "{}")
        }
}
