package com.m57.hermescontrol.career

import com.m57.hermescontrol.data.local.AuthManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs the screen's production prepare/use orchestration with synthetic HTTP responses. */
@OptIn(ExperimentalCoroutinesApi::class)
class CareerBrowserAutofillTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<OkHttpClient>(relaxed = true)
    private val call = mockk<Call>(relaxed = true)
    private val callback = slot<Callback>()
    private val requests = mutableListOf<Request>()
    private val base = MutableStateFlow("https://gateway.example.test/")
    private val profile = MutableStateFlow<String?>("phone")
    private val policy = checkNotNull(CareerBrowserPolicy.create("https://portal.example.test/company/login"))
    private val gate = CareerBrowserSessionGate(initiallyForeground = true)
    private val initialEpoch = gate.epoch

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(AuthManager)
        every { AuthManager.getBaseUrl() } answers { base.value }
        every { AuthManager.baseUrlFlow } returns base
        every { AuthManager.activeProfileId } returns profile
        every { AuthManager.selectedProfileFlow } returns profile
        every { AuthManager.isGatedMode() } returns true
        every { client.newCall(capture(requests)) } returns call
        every { call.enqueue(capture(callback)) } returns Unit
    }

    @After
    fun cleanup() {
        unmockkObject(AuthManager)
        Dispatchers.resetMain()
    }

    private fun source() = CareerBrowserCredentialSource(careerScope()) { client }

    private fun documentCurrent() = gate.isCurrent(initialEpoch)

    private fun target(extra: String) =
        """{"company_id":"dummy-company","profile":"phone","connection_version":1,
            "url":"https://portal.example.test/company/login",$extra} """

    private fun prepared(requiresConfirmation: Boolean) =
        target(""""approval_id":"${"a".repeat(64)}","expires_in":60,"requires_confirmation":$requiresConfirmation""")

    private fun credentials() = target("\"login_id\":\"dummy-id\",\"password\":\"dummy-password\"")

    private fun respond(body: String) {
        callback.captured.onResponse(
            call,
            Response
                .Builder()
                .request(requests.last())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("Synthetic response")
                .body(body.toResponseBody())
                .build(),
        )
    }

    @Test
    fun newAndExistingTargetGrantsFillWithoutAnotherUiDecisionOrDelay() =
        runTest(dispatcher) {
            for (requiresConfirmation in listOf(true, false)) {
                requests.clear()
                var retained: CareerBrowserCredentials? = null
                val operation =
                    async {
                        source().fillCurrentDocument("dummy-company", 1, policy, ::documentCurrent) {
                            retained = it
                            true
                        }
                    }
                runCurrent()
                assertEquals(1, requests.size)
                assertTrue(requests[0].url.encodedPath.endsWith("/browser-access/prepare"))
                respond(prepared(requiresConfirmation))
                runCurrent()
                // Only HTTP completion is needed: no dialog callback or virtual-clock advance.
                assertEquals(0L, currentTime)
                assertEquals(2, requests.size)
                assertTrue(requests[1].url.encodedPath.endsWith("/browser-credentials"))
                val body = Buffer().also { requests[1].body?.writeTo(it) }.readUtf8()
                val mode = if (requiresConfirmation) "allow_target" else "use_target"
                assertTrue(body.contains("\"consent_mode\":\"$mode\""))
                assertTrue(body.contains("\"approval_id\":\"${"a".repeat(64)}\""))
                assertEquals("once", requests[1].header("X-Hermes-Browser-Consent"))
                assertEquals(null, retained)
                respond(credentials())
                runCurrent()
                assertTrue(operation.await())
                assertTrue(checkNotNull(retained).password.all { it == '\u0000' })
                assertEquals(0L, currentTime)
            }
        }

    @Test
    fun backgroundBeforeStartDoesNotPrepare() =
        runTest(dispatcher) {
            gate.pause()
            assertFalse(
                source().fillCurrentDocument("dummy-company", 1, policy, ::documentCurrent) { error("background") },
            )
            assertTrue(requests.isEmpty())
        }

    @Test
    fun staleDocumentAfterPrepareCannotFetchCredentials() =
        runTest(dispatcher) {
            val operation =
                async {
                    source().fillCurrentDocument("dummy-company", 1, policy, ::documentCurrent) { error("stale") }
                }
            runCurrent()
            respond(prepared(true))
            gate.invalidate()
            runCurrent()
            assertFalse(operation.await())
            assertEquals(1, requests.size)
        }

    @Test
    fun backgroundAndResumeAfterPrepareCannotReviveInput() =
        runTest(dispatcher) {
            val operation =
                async {
                    source().fillCurrentDocument("dummy-company", 1, policy, ::documentCurrent) { error("background") }
                }
            runCurrent()
            respond(prepared(false))
            gate.pause()
            gate.resume()
            runCurrent()
            assertFalse(operation.await())
            assertEquals(1, requests.size)
            assertFalse(gate.automaticFillAllowed)
        }

    @Test
    fun cancellationDuringPrepareCancelsHttpAndLateReplyCannotFetchCredentials() =
        runTest(dispatcher) {
            val operation =
                async {
                    source().fillCurrentDocument("dummy-company", 1, policy, ::documentCurrent) { error("cancelled") }
                }
            runCurrent()
            operation.cancel()
            runCurrent()
            respond(prepared(true))
            runCurrent()
            assertTrue(operation.isCancelled)
            assertEquals(1, requests.size)
            verify(atLeast = 1) { call.cancel() }
        }

    @Test
    fun cancellationAfterPrepareReplyBeforeResumptionCannotFetchCredentials() =
        runTest(dispatcher) {
            val operation =
                async {
                    source().fillCurrentDocument("dummy-company", 1, policy, ::documentCurrent) { error("cancelled") }
                }
            runCurrent()
            respond(prepared(true))
            operation.cancel()
            runCurrent()
            assertTrue(operation.isCancelled)
            assertEquals(1, requests.size)
        }

    @Test
    fun backgroundAfterCredentialFetchCannotEvaluateInput() =
        runTest(dispatcher) {
            val operation =
                async {
                    source().fillCurrentDocument("dummy-company", 1, policy, ::documentCurrent) { error("background") }
                }
            runCurrent()
            respond(prepared(true))
            runCurrent()
            assertEquals(2, requests.size)
            gate.pause()
            respond(credentials())
            runCurrent()
            assertFalse(operation.await())
        }

    @Test
    fun cancellationDuringCredentialFetchCannotEvaluateLateReply() =
        runTest(dispatcher) {
            val operation =
                async {
                    source().fillCurrentDocument("dummy-company", 1, policy, ::documentCurrent) { error("cancelled") }
                }
            runCurrent()
            respond(prepared(false))
            runCurrent()
            operation.cancel()
            runCurrent()
            respond(credentials())
            runCurrent()
            assertTrue(operation.isCancelled)
            assertEquals(2, requests.size)
            verify(atLeast = 2) { call.cancel() }
        }

    @Test
    fun changedDocumentAfterEvaluationIsNotReportedAsQueued() =
        runTest(dispatcher) {
            val operation =
                async {
                    source().fillCurrentDocument("dummy-company", 1, policy, ::documentCurrent) {
                        gate.invalidate()
                        true
                    }
                }
            runCurrent()
            respond(prepared(false))
            runCurrent()
            respond(credentials())
            runCurrent()
            assertFalse(operation.await())
        }

    private fun foreignTargets(body: String) =
        listOf(
            body.replace("dummy-company", "another-company"),
            body.replace("phone", "another-profile"),
            body.replace("\"connection_version\":1", "\"connection_version\":2"),
            body.replace("/company/login", "/other/login"),
        )

    @Test
    fun everyPrepareTargetDimensionStillRejectsBeforeCredentialFetch() =
        runTest(dispatcher) {
            for (body in foreignTargets(prepared(true))) {
                requests.clear()
                val source = source()
                val operation =
                    async {
                        source.fillCurrentDocument("dummy-company", 1, policy, ::documentCurrent) { error("foreign") }
                    }
                runCurrent()
                respond(body)
                runCurrent()
                assertFalse(operation.await())
                assertEquals(1, requests.size)
                assertEquals(CareerBrowserCredentialFailure.TARGET_CHANGED, source.lastFailure)
            }
        }

    @Test
    fun everyCredentialTargetDimensionStillRejectsBeforeEvaluation() =
        runTest(dispatcher) {
            for (body in foreignTargets(credentials())) {
                requests.clear()
                val source = source()
                val operation =
                    async {
                        source.fillCurrentDocument("dummy-company", 1, policy, ::documentCurrent) { error("foreign") }
                    }
                runCurrent()
                respond(prepared(false))
                runCurrent()
                respond(body)
                runCurrent()
                assertFalse(operation.await())
                assertEquals(2, requests.size)
                assertEquals(CareerBrowserCredentialFailure.TARGET_CHANGED, source.lastFailure)
            }
        }

    @Test
    fun profileSwitchAfterPrepareReplyStillRejectsBeforeCredentialFetch() =
        runTest(dispatcher) {
            val source = source()
            val operation =
                async {
                    source.fillCurrentDocument("dummy-company", 1, policy, ::documentCurrent) { error("foreign") }
                }
            runCurrent()
            respond(prepared(true))
            profile.value = "another-profile"
            runCurrent()
            assertFalse(operation.await())
            assertEquals(1, requests.size)
            assertEquals(CareerBrowserCredentialFailure.SCOPE_CHANGED, source.lastFailure)
        }
}
