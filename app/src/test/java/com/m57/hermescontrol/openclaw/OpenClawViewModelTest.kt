package com.m57.hermescontrol.openclaw

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.ProfileContext
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class OpenClawViewModelTest {
    private class Gateway(
        private val deviceToken: String? = null,
        private val supportsWait: Boolean = false,
        private val scopes: List<String> = listOf("operator.read", "operator.write"),
        private val payload: (String, JsonObject) -> JsonObject? = { _, _ -> null },
        private val reject: (String, JsonObject) -> Boolean = { _, _ -> false },
        private val intercept: (JsonObject, WebSocket) -> Boolean = { _, _ -> false },
    ) : WebSocketListener() {
        val requests = CopyOnWriteArrayList<JsonObject>()

        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            webSocket.send("""{"type":"event","event":"connect.challenge","payload":{"nonce":"n","ts":1}}""")
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            val request = Json.parseToJsonElement(text) as JsonObject
            requests += request
            if (intercept(request, webSocket)) return
            val method = request.string("method").orEmpty()
            val params = request.obj("params") ?: obj()
            val responsePayload =
                payload(method, params) ?: when (method) {
                    "connect" -> {
                        obj(
                            "type" to value("hello-ok"),
                            "features" to
                                obj(
                                    "methods" to
                                        JsonArray(
                                            (
                                                listOf("sessions.list", "chat.history", "chat.send") +
                                                    if (supportsWait) listOf("agent.wait") else emptyList()
                                            ).map(::JsonPrimitive),
                                        ),
                                ),
                            "auth" to
                                obj(
                                    "scopes" to JsonArray(scopes.map(::JsonPrimitive)),
                                    "deviceToken" to value(deviceToken),
                                ),
                        )
                    }

                    "chat.send" -> {
                        obj("runId" to value(request.obj("params")?.string("idempotencyKey")))
                    }

                    else -> {
                        obj("sessions" to JsonArray(emptyList()), "messages" to JsonArray(emptyList()))
                    }
                }
            webSocket.send(
                obj(
                    "type" to value("res"),
                    "id" to value(request.string("id")),
                    "ok" to JsonPrimitive(!reject(method, params)),
                    "payload" to responsePayload,
                    "error" to obj("code" to value("UNAVAILABLE")),
                ).toString(),
            )
        }
    }

    private suspend fun fixture(
        gateway: Gateway = Gateway(),
        pending: Map<String, ClawPendingRun> = emptyMap(),
        dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
        test:
            suspend (
                OpenClawViewModel,
                MutableStateFlow<String?>,
                MutableStateFlow<ProfileContext?>,
                MockWebServer,
                OpenClawStore,
                MutableMap<String, String>,
            ) -> Unit,
    ) {
        Dispatchers.setMain(dispatcher)
        mockkObject(AuthManager)
        mockkObject(ApiClient)
        val api = mockk<HermesApiService>()
        every { ApiClient.hermesApi } returns api
        coEvery { api.getEnvVars() } returns retrofit2.Response.success(emptyMap())
        val tokens = MutableStateFlow<String?>("hermes-token")
        val contexts = MutableStateFlow<ProfileContext?>(ProfileContext("https://hermes.test", "hermes-token", "p"))
        every { AuthManager.baseUrlFlow } returns MutableStateFlow("https://hermes.test")
        every { AuthManager.selectedProfileFlow } returns MutableStateFlow("p")
        every { AuthManager.contextFlow } returns contexts
        every { AuthManager.tokenFlow } returns tokens
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(gateway))
        server.start()
        val settings = ConcurrentHashMap<String, String>()
        settings["url"] = OpenClawEndpoint.parse(server.url("/").toString()).webSocketUrl
        if (pending.isNotEmpty()) {
            settings["pending-runs:all"] = encodeClawPendingRuns(pending)
            settings["last-session"] = pending.keys.first()
            settings["notifications"] = "false"
        }
        val store = mockk<OpenClawStore>()
        every { store.read(any()) } answers { settings[firstArg()] }
        every { store.write(any(), any()) } answers {
            val key = firstArg<String>()
            val value = secondArg<String?>()
            if (value == null) settings.remove(key) else settings[key] = value
        }
        every { store.identity() } returns ClawIdentity(ByteArray(32))
        every { store.saveConnection(any(), any()) } answers {
            settings["url"] = firstArg()
            settings["token"] = secondArg()
        }
        every { store.sessionName(any(), any()) } answers { "${firstArg<String>()}:${secondArg<String>()}" }
        val vm = OpenClawViewModel(mockk<Application>(relaxed = true), OpenClawViewModel.ownerScope(), store)
        val models = ViewModelStore()
        models.put("test", vm)
        try {
            vm.ensureConnected()
            withTimeout(5000) { vm.uiState.first { it.connection == ClawConnection.CONNECTED && !it.loading } }
            test(vm, tokens, contexts, server, store, settings)
        } finally {
            models.clear()
            server.shutdown()
            unmockkObject(AuthManager)
            unmockkObject(ApiClient)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun logoutRetiresSocketAndResumeCannotReconnectTheOldOwner() =
        runBlocking {
            fixture { vm, tokens, contexts, server, _, _ ->
                tokens.value = null
                contexts.value = ProfileContext("https://hermes.test", null, "p")
                withTimeout(1000) { vm.uiState.first { !it.configured } }
                vm.ensureConnected()
                delay(50)
                assertEquals(ClawConnection.DISCONNECTED, vm.uiState.value.connection)
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun anotherThreadCanOpenWhileRunRemainsPendingInItsOriginalThread() =
        runBlocking {
            fixture { vm, _, _, _, _, _ ->
                val original = vm.uiState.value.sessionKey
                val accepted = CompletableDeferred<Unit>()
                vm.send("first task") { accepted.complete(Unit) }
                withTimeout(5000) { accepted.await() }
                assertTrue(vm.uiState.value.busy)
                vm.newSession()
                assertNotEquals(original, vm.uiState.value.sessionKey)
                assertFalse(vm.uiState.value.busy)
                vm.selectSession(original)
                withTimeout(5000) { vm.uiState.first { !it.loading } }
                assertEquals(original, vm.uiState.value.sessionKey)
                assertTrue(vm.uiState.value.busy)
            }
        }

    @Test
    fun restoredPendingRunRecoversFromItsDurableHistoryWithoutReplayingSend() =
        runBlocking {
            val key = "agent:main:restored"
            val pending = ClawPendingRun("missed-ack", "same prompt")
            val gateway =
                Gateway(payload = { method, _ ->
                    if (method == "chat.history") {
                        Json.parseToJsonElement(
                            """{"messages":[{"role":"assistant","content":"finished","__openclaw":
                            {"runId":"missed-ack","runTerminal":true,"id":"receipt"}}]}""",
                        ) as JsonObject
                    } else {
                        null
                    }
                })
            fixture(gateway, mapOf(key to pending)) { vm, _, _, _, _, settings ->
                withTimeout(5000) {
                    while (clawPendingRuns(settings["pending-runs:all"]).isNotEmpty()) delay(1)
                }
                assertFalse(vm.uiState.value.busy)
                assertTrue(gateway.requests.none { it.string("method") == "chat.send" })
            }
        }

    @Test
    fun onePendingReadFailureDoesNotPreventAnotherExactWaitRecovery() =
        runBlocking {
            val unresolved = "agent:main:unresolved"
            val completed = "agent:main:completed"
            val restored =
                linkedMapOf(
                    unresolved to ClawPendingRun("unknown", "same prompt"),
                    completed to ClawPendingRun("completed", "same prompt"),
                )
            val unknownHistoryReads = AtomicInteger()
            val gateway =
                Gateway(
                    supportsWait = true,
                    payload = { method, params ->
                        if (method == "agent.wait") {
                            val runId = params.string("runId")
                            if (runId == "completed") {
                                obj("runId" to value(runId), "status" to value("ok"), "endedAt" to JsonPrimitive(2))
                            } else {
                                obj("runId" to value(runId), "status" to value("timeout"))
                            }
                        } else {
                            null
                        }
                    },
                    reject = { method, params ->
                        (
                            method == "chat.history" && params.string("sessionKey") == unresolved &&
                                unknownHistoryReads.incrementAndGet() > 1
                        ) ||
                            (method == "agent.wait" && params.string("runId") == "unknown")
                    },
                )
            fixture(gateway, restored) { vm, _, _, _, _, settings ->
                withTimeout(5000) {
                    while (clawPendingRuns(settings["pending-runs:all"]).size != 1) delay(1)
                }
                assertEquals(
                    mapOf(unresolved to restored.getValue(unresolved)),
                    clawPendingRuns(settings["pending-runs:all"]),
                )
                assertTrue(vm.uiState.value.busy)
                val waits = gateway.requests.filter { it.string("method") == "agent.wait" }
                assertEquals(setOf("unknown", "completed"), waits.map { it.obj("params")?.string("runId") }.toSet())
                assertTrue(waits.all { it.obj("params")?.string("timeoutMs") == "0" })
                assertTrue(gateway.requests.none { it.string("method") == "chat.send" })
            }
        }

    @Test
    fun logoutDuringPendingHistoryPreventsFallbackAndFurtherPendingRequests() =
        runBlocking {
            val first = "agent:main:first"
            val restored =
                linkedMapOf(
                    first to ClawPendingRun("first", "task"),
                    "agent:main:second" to ClawPendingRun("second", "task"),
                )
            val historyReads = AtomicInteger()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val gateway =
                Gateway(
                    supportsWait = true,
                    payload = { method, _ ->
                        if (method == "chat.history" && historyReads.incrementAndGet() == 2) {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                        null
                    },
                )
            try {
                fixture(gateway, restored) { _, tokens, _, _, _, settings ->
                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        tokens.value = null
                        val requestsBeforeLogout = gateway.requests.size
                        release.countDown()
                        delay(50)
                        assertEquals(requestsBeforeLogout, gateway.requests.size)
                        assertEquals(restored, clawPendingRuns(settings["pending-runs:all"]))
                        assertTrue(gateway.requests.none { it.string("method") == "agent.wait" })
                    } finally {
                        release.countDown()
                    }
                }
            } finally {
                release.countDown()
            }
        }

    @Test
    fun lostAcknowledgementRecoversUsingThePersistedIdWithoutResending() =
        runBlocking {
            val dispatcher = UnconfinedTestDispatcher()
            val sentId = CompletableDeferred<String>()
            val gateway =
                Gateway(intercept = { request, socket ->
                    if (request.string("method") == "chat.send") {
                        sentId.complete(request.obj("params")!!.string("idempotencyKey")!!)
                        socket.cancel()
                        true
                    } else {
                        false
                    }
                })
            fixture(gateway, dispatcher = dispatcher) { vm, _, _, server, _, settings ->
                settings["notifications"] = "false"
                vm.setNotifications(false)
                val recoveredGateway =
                    Gateway(supportsWait = true, payload = { method, params ->
                        if (method == "agent.wait") {
                            obj(
                                "runId" to value(params.string("runId")),
                                "status" to value("ok"),
                                "endedAt" to JsonPrimitive(2),
                            )
                        } else {
                            null
                        }
                    })
                server.enqueue(MockResponse().withWebSocketUpgrade(recoveredGateway))
                vm.send("task with lost acknowledgement") { fail("Lost acknowledgement must not be accepted") }
                val runId = withTimeout(5000) { sentId.await() }
                withTimeout(5000) { vm.uiState.first { it.connection == ClawConnection.CONNECTING } }
                dispatcher.scheduler.runCurrent()
                dispatcher.scheduler.advanceTimeBy(1000)
                dispatcher.scheduler.runCurrent()
                withTimeout(5000) {
                    while (recoveredGateway.requests.none { it.string("method") == "agent.wait" } ||
                        clawPendingRuns(settings["pending-runs:all"]).isNotEmpty()
                    ) {
                        delay(1)
                    }
                }
                assertEquals(
                    runId,
                    recoveredGateway.requests
                        .single { it.string("method") == "agent.wait" }
                        .obj("params")
                        ?.string("runId"),
                )
                assertFalse(vm.uiState.value.busy)
                assertEquals(
                    1,
                    (gateway.requests + recoveredGateway.requests).count { it.string("method") == "chat.send" },
                )
            }
        }

    @Test
    fun missingReadScopeOrUnadvertisedWaitPreservesRestoredUnknownRuns() =
        runBlocking {
            for (scopes in listOf(listOf("operator.read", "operator.write"), listOf("operator.write"))) {
                val key = "agent:main:unknown"
                val restored = mapOf(key to ClawPendingRun("unknown", "task"))
                val gateway = Gateway(scopes = scopes, supportsWait = "operator.read" !in scopes)
                fixture(gateway, restored) { vm, _, _, _, _, settings ->
                    delay(50)
                    assertTrue(vm.uiState.value.busy)
                    assertEquals(restored, clawPendingRuns(settings["pending-runs:all"]))
                    assertTrue(gateway.requests.none { it.string("method") == "agent.wait" })
                    vm.newSession()
                    assertFalse(vm.uiState.value.busy)
                    assertEquals(restored, clawPendingRuns(settings["pending-runs:all"]))
                }
            }
        }

    @Test
    fun explicitSameConnectionSaveRetiresOnlyTheDerivedDeviceCredentials() =
        runBlocking {
            fixture { vm, _, _, server, store, settings ->
                settings["token"] = "shared"
                settings["device-token"] = "revoked"
                settings["device-scopes"] = "operator.read"
                settings["identity"] = "retained identity"
                settings["title:history"] = "retained title"
                vm.saveConnection(server.url("/").toString(), null)
                assertNull(settings["device-token"])
                assertNull(settings["device-scopes"])
                assertEquals("shared", settings["token"])
                assertEquals("retained identity", settings["identity"])
                assertEquals("retained title", settings["title:history"])
                io.mockk.verify(exactly = 1) { store.saveConnection(any(), "shared") }
            }
        }

    @Test
    fun explicitSaveCannotBeUndoneByAnOldHandshakeCredentialWrite() =
        runBlocking {
            fixture { vm, _, _, server, store, settings ->
                settings["token"] = "shared"
                settings["identity"] = "retained identity"
                settings["title:history"] = "retained title"
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val finished = CountDownLatch(1)
                val reads = AtomicInteger()
                every { store.read("device-token") } answers {
                    val value = settings["device-token"]
                    if (reads.incrementAndGet() == 2) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    value
                }
                server.enqueue(MockResponse().withWebSocketUpgrade(Gateway("retired-issued")))
                server.enqueue(MockResponse().withWebSocketUpgrade(Gateway()))
                vm.connect()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val explicitSave =
                    Thread {
                        try {
                            vm.saveConnection(server.url("/").toString(), null)
                        } finally {
                            finished.countDown()
                        }
                    }
                try {
                    explicitSave.start()
                    withTimeout(5000) {
                        while (explicitSave.state != Thread.State.BLOCKED && finished.count > 0) delay(1)
                    }
                    assertEquals(Thread.State.BLOCKED, explicitSave.state)
                    release.countDown()
                    assertTrue(finished.await(5, TimeUnit.SECONDS))
                    withTimeout(5000) { vm.uiState.first { it.connection == ClawConnection.CONNECTED && !it.loading } }
                    assertNull(settings["device-token"])
                    assertNull(settings["device-scopes"])
                    assertEquals("shared", settings["token"])
                    assertEquals("retained identity", settings["identity"])
                    assertEquals("retained title", settings["title:history"])
                } finally {
                    release.countDown()
                    explicitSave.join(5000)
                }
            }
        }

    @Test
    fun disablingAutomaticTitlesPersistsWithoutChangingTheDefaultOrSavedKey() =
        runBlocking {
            fixture { vm, _, _, _, _, settings ->
                assertTrue(vm.uiState.value.automaticTitlesEnabled)
                vm.saveTitleKey("synthetic title key")
                vm.setAutomaticTitles(false)
                vm.newSession()
                val accepted = CompletableDeferred<Unit>()
                vm.send("local fallback title") { accepted.complete(Unit) }
                withTimeout(5000) { accepted.await() }
                assertFalse(vm.uiState.value.automaticTitlesEnabled)
                assertEquals("false", settings["title-enabled"])
                assertEquals("synthetic title key", settings["title-api-key"])
                coVerify(exactly = 0) { ApiClient.hermesApi.getEnvVars() }
            }
        }

    @Test
    fun disablingAutomaticTitlesRejectsALateGeneratedTitle() =
        runBlocking {
            mockkConstructor(OpenClawTitles::class)
            val started = CompletableDeferred<Unit>()
            val late = CompletableDeferred<String>()
            coEvery { anyConstructed<OpenClawTitles>().generate(any(), any()) } coAnswers {
                started.complete(Unit)
                withContext(NonCancellable) { late.await() }
            }
            try {
                fixture { vm, _, _, _, store, settings ->
                    vm.saveTitleKey("synthetic title key")
                    vm.newSession()
                    vm.send("local title") {}
                    withTimeout(5000) { started.await() }
                    val key = vm.uiState.value.sessionKey
                    vm.setAutomaticTitles(false)
                    late.complete("late external title")
                    delay(50)
                    assertEquals("local title", settings[store.sessionName("title", key)])
                    assertNull(settings[store.sessionName("title-generated", key)])
                    assertFalse(vm.uiState.value.automaticTitlesEnabled)
                }
            } finally {
                late.complete("late external title")
                unmockkConstructor(OpenClawTitles::class)
            }
        }
}
