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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

/** Synthetic OkHttp response fixtures only; no live server, cookie or credential access. */
@OptIn(ExperimentalCoroutinesApi::class)
class CareerBrowserCredentialDiagnosticsTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<OkHttpClient>(relaxed = true)
    private val call = mockk<Call>(relaxed = true)
    private val callback = slot<Callback>()
    private val request = slot<Request>()
    private val base = MutableStateFlow("https://gateway.example.test/")
    private val profile = MutableStateFlow<String?>("phone")
    private var session: String? = "synthetic-session"
    private val policy = checkNotNull(CareerBrowserPolicy.create("https://portal.example.test/company/login"))

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(AuthManager)
        every { AuthManager.getBaseUrl() } answers { base.value }
        every { AuthManager.baseUrlFlow } returns base
        every { AuthManager.activeProfileId } returns profile
        every { AuthManager.selectedProfileFlow } returns profile
        every { AuthManager.isGatedMode() } returns true
        every { AuthManager.getSessionCookie() } answers { session }
        every { client.newCall(capture(request)) } returns call
        every { call.enqueue(capture(callback)) } returns Unit
    }

    @After
    fun cleanup() {
        unmockkObject(AuthManager)
        Dispatchers.resetMain()
    }

    private fun source(trustedEndpoint: String = "") =
        CareerBrowserCredentialSource(careerScope(), trustedEndpoint) { client }

    private fun respond(
        status: Int,
        body: String,
        responseRequest: Request = request.captured,
    ) {
        callback.captured.onResponse(
            call,
            Response
                .Builder()
                .request(responseRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("Synthetic response")
                .body(body.toResponseBody())
                .build(),
        )
    }

    private fun target(extra: String) =
        """{"company_id":"dummy-company","profile":"default","connection_version":1,
            "url":"https://portal.example.test/company/login",$extra} """

    @Test
    fun serverFailuresHaveSpecificSafeMessages() =
        runTest(dispatcher) {
            val cases =
                listOf(
                    Triple(403, "browser_credentials_disabled", CareerBrowserCredentialFailure.DISABLED),
                    Triple(404, "missing", CareerBrowserCredentialFailure.UNSUPPORTED_ENDPOINT),
                    Triple(405, "missing", CareerBrowserCredentialFailure.UNSUPPORTED_ENDPOINT),
                    Triple(403, "browser_tls_required", CareerBrowserCredentialFailure.SERVER_HTTPS_REQUIRED),
                    Triple(401, "dashboard_session_required", CareerBrowserCredentialFailure.SESSION_REQUIRED),
                    Triple(409, "version_conflict", CareerBrowserCredentialFailure.TARGET_CHANGED),
                    Triple(409, "mypage_not_connected", CareerBrowserCredentialFailure.SAVED_CONNECTION_REQUIRED),
                    Triple(403, "browser_authorization_required", CareerBrowserCredentialFailure.APPROVAL_REQUIRED),
                    Triple(503, "browser_worker_unavailable", CareerBrowserCredentialFailure.SERVER_UNAVAILABLE),
                    Triple(403, "unknown-secret-marker", CareerBrowserCredentialFailure.REQUEST_REJECTED),
                )
            val source = source()
            for ((status, code, expected) in cases) {
                val result = async { source.prepare("dummy-company", 1, policy) }
                advanceUntilIdle()
                respond(status, """{"detail":{"code":"$code","message":"secret-marker"}}""")
                advanceUntilIdle()
                assertNull(result.await())
                assertEquals(expected, source.lastFailure)
                assertFalse(checkNotNull(source.lastFailure).userMessage.contains("secret-marker"))
            }
        }

    @Test
    fun serverTlsRefusalIsDistinctFromLocalHttpConfiguration() =
        runTest(dispatcher) {
            val source = source()
            val result = async { source.prepare("dummy-company", 1, policy) }
            advanceUntilIdle()
            assertTrue(request.captured.url.isHttps)
            respond(403, """{"detail":{"code":"browser_tls_required"}}""")
            advanceUntilIdle()
            assertNull(result.await())
            assertEquals(CareerBrowserCredentialFailure.SERVER_HTTPS_REQUIRED, source.lastFailure)
            assertFalse(
                CareerBrowserCredentialFailure.HTTPS_REQUIRED.userMessage ==
                    CareerBrowserCredentialFailure.SERVER_HTTPS_REQUIRED.userMessage,
            )
        }

    @Test
    fun cleartextBaseIsUsedWithoutAnyHttpsOrSettingsOptIn() =
        runTest(dispatcher) {
            base.value = "http://gateway.example.test:9119/"
            val source = source()
            val result = async { source.prepare("dummy-company", 1, policy) }
            advanceUntilIdle()
            assertEquals("http", request.captured.url.scheme)
            assertEquals("gateway.example.test", request.captured.url.host)
            respond(200, target(""""approval_id":"${"a".repeat(64)}","expires_in":60,"requires_confirmation":false"""))
            advanceUntilIdle()
            assertFalse(checkNotNull(result.await()).requiresConfirmation)
            assertTrue(checkNotNull(source.transportWarning).contains("HTTP"))
        }

    @Test
    fun cleartextEndpointRequiresAuthenticatedSession() =
        runTest(dispatcher) {
            base.value = "http://100.64.10.20:9119/"
            session = null
            val signedOut = source(base.value)
            assertNull(signedOut.prepare("dummy-company", 1, policy))
            assertEquals(CareerBrowserCredentialFailure.SESSION_REQUIRED, signedOut.lastFailure)
            verify(exactly = 0) { client.newCall(any()) }
            session = "synthetic-session"
            val approved = source(base.value)
            val result = async { approved.prepare("dummy-company", 1, policy) }
            advanceUntilIdle()
            assertEquals("100.64.10.20", request.captured.url.host)
            assertEquals(9119, request.captured.url.port)
            assertEquals(listOf("default"), request.captured.url.queryParameterValues("profile"))
            assertEquals(null, request.captured.header("Authorization"))
            respond(200, target(""""approval_id":"${"a".repeat(64)}","expires_in":60,"requires_confirmation":true"""))
            advanceUntilIdle()
            assertTrue(checkNotNull(result.await()).requiresConfirmation)
            assertTrue(checkNotNull(approved.transportWarning).contains("Tailscale"))
        }

    @Test
    fun tailnetServerRejectionsExplainTheMissingTailnetPermissionWithoutSuggestingMigration() =
        runTest(dispatcher) {
            base.value = "http://100.64.10.20:9119/"
            val source = source(base.value)
            for (code in listOf("browser_tls_required", "browser_tailnet_unverified")) {
                val result = async { source.prepare("dummy-company", 1, policy) }
                advanceUntilIdle()
                respond(403, """{"detail":{"code":"$code","message":"secret-marker"}}""")
                advanceUntilIdle()
                assertNull(result.await())
                assertEquals(CareerBrowserCredentialFailure.TRUSTED_TRANSPORT_REJECTED, source.lastFailure)
                assertFalse(checkNotNull(source.lastFailure).userMessage.contains("secret-marker"))
            }
        }

    @Test
    fun tailnetSessionChangesDuringRequestRejectReplyAndCannotReviveTheSource() =
        runTest(dispatcher) {
            base.value = "http://100.64.10.20:9119/"
            val source = source(base.value)
            val result = async { source.prepare("dummy-company", 1, policy) }
            advanceUntilIdle()
            session = "other-session"
            respond(200, target(""""approval_id":"${"a".repeat(64)}","expires_in":60,"requires_confirmation":false"""))
            advanceUntilIdle()
            assertNull(result.await())
            assertEquals(CareerBrowserCredentialFailure.SCOPE_CHANGED, source.lastFailure)
            session = "synthetic-session"
            assertNull(source.prepare("dummy-company", 1, policy))
            assertEquals(CareerBrowserCredentialFailure.SCOPE_CHANGED, source.lastFailure)
            verify(exactly = 1) { client.newCall(any()) }
        }

    @Test
    fun tailnetRedirectsAndForeignResponseUrlsCannotYieldApproval() =
        runTest(dispatcher) {
            base.value = "http://100.64.10.20:9119/"
            val source = source(base.value)
            val body = target(""""approval_id":"${"a".repeat(64)}","expires_in":60,"requires_confirmation":false""")
            val redirect = async { source.prepare("dummy-company", 1, policy) }
            advanceUntilIdle()
            respond(302, body)
            advanceUntilIdle()
            assertNull(redirect.await())
            assertEquals(CareerBrowserCredentialFailure.REQUEST_REJECTED, source.lastFailure)
            val foreign = async { source.prepare("dummy-company", 1, policy) }
            advanceUntilIdle()
            respond(
                200,
                body,
                request.captured
                    .newBuilder()
                    .url("http://foreign.example.test/")
                    .build(),
            )
            advanceUntilIdle()
            assertNull(foreign.await())
            assertEquals(CareerBrowserCredentialFailure.REQUEST_REJECTED, source.lastFailure)
            verify(exactly = 2) { client.newCall(any()) }
        }

    @Test
    fun redirectEnabledClientIsRejectedBeforeCreatingAnyCall() =
        runTest(dispatcher) {
            base.value = "http://100.64.10.20:9119/"
            every { client.followRedirects } returns true
            val source = source(base.value)
            assertNull(source.prepare("dummy-company", 1, policy))
            assertEquals(CareerBrowserCredentialFailure.REQUEST_REJECTED, source.lastFailure)
            verify(exactly = 0) { client.newCall(any()) }
        }

    @Test
    fun htmlEndpointResponseAndMalformedSuccessDoNotLeakBodies() =
        runTest(dispatcher) {
            val source = source()
            for (status in listOf(404, 200)) {
                val result = async { source.prepare("dummy-company", 1, policy) }
                advanceUntilIdle()
                respond(status, "<html>secret-marker</html>")
                advanceUntilIdle()
                assertNull(result.await())
                assertEquals(
                    if (status == 404) {
                        CareerBrowserCredentialFailure.UNSUPPORTED_ENDPOINT
                    } else {
                        CareerBrowserCredentialFailure.INVALID_RESPONSE
                    },
                    source.lastFailure,
                )
            }
        }

    @Test
    fun successfulPrepareAndUseClearPreviousFailureAndWipeBuffers() =
        runTest(dispatcher) {
            val source = source()
            val failed = async { source.prepare("dummy-company", 1, policy) }
            advanceUntilIdle()
            respond(403, """{"detail":{"code":"browser_credentials_disabled"}}""")
            advanceUntilIdle()
            assertNull(failed.await())
            val prepared = async { source.prepare("dummy-company", 1, policy) }
            advanceUntilIdle()
            assertNull(source.lastFailure)
            respond(200, target(""""approval_id":"${"a".repeat(64)}","expires_in":60,"requires_confirmation":false"""))
            advanceUntilIdle()
            val approval = checkNotNull(prepared.await())
            var owned: CareerBrowserCredentials? = null
            val used =
                async {
                    source.use("dummy-company", 1, policy, approval) {
                        owned = it
                        true
                    }
                }
            advanceUntilIdle()
            assertTrue(
                request.captured.url.encodedPath
                    .endsWith("/browser-credentials"),
            )
            respond(200, target(""""login_id":"synthetic-user","password":"synthetic-password""""))
            advanceUntilIdle()
            assertTrue(used.await())
            assertNull(source.lastFailure)
            assertTrue(checkNotNull(owned).password.all { it == '\u0000' })
            assertFalse(source.use("dummy-company", 1, policy, approval) { error("must not replay") })
            assertEquals(CareerBrowserCredentialFailure.APPROVAL_REQUIRED, source.lastFailure)
        }

    @Test
    fun changedResponseTargetNeverReachesInput() =
        runTest(dispatcher) {
            val source = source()
            val prepared = async { source.prepare("dummy-company", 1, policy) }
            advanceUntilIdle()
            respond(
                200,
                target(""""approval_id":"${"a".repeat(64)}","expires_in":60,"requires_confirmation":false"""),
            )
            advanceUntilIdle()
            val approval = checkNotNull(prepared.await())
            val used =
                async {
                    source.use("dummy-company", 1, policy, approval) { error("must not fill a changed target") }
                }
            advanceUntilIdle()
            val credentials = target(""""login_id":"synthetic-user","password":"synthetic-password"""")
            respond(200, credentials.replace("\"connection_version\":1", "\"connection_version\":2"))
            advanceUntilIdle()
            assertFalse(used.await())
            assertEquals(CareerBrowserCredentialFailure.TARGET_CHANGED, source.lastFailure)
        }

    @Test
    fun networkAndTlsErrorsAreSafeAndDistinct() =
        runTest(dispatcher) {
            val source = source()
            for (tls in listOf(false, true)) {
                val result = async { source.prepare("dummy-company", 1, policy) }
                advanceUntilIdle()
                val failure = if (tls) SSLHandshakeException("secret-marker") else IOException("secret-marker")
                callback.captured.onFailure(call, failure)
                advanceUntilIdle()
                assertNull(result.await())
                assertEquals(
                    if (tls) {
                        CareerBrowserCredentialFailure.TLS_FAILED
                    } else {
                        CareerBrowserCredentialFailure.NETWORK_FAILED
                    },
                    source.lastFailure,
                )
            }
        }

    @Test
    fun cancellationRemainsCancellationAndLateFailureDoesNotBecomeUiError() =
        runTest(dispatcher) {
            val source = source()
            val result = async { source.prepare("dummy-company", 1, policy) }
            advanceUntilIdle()
            result.cancel()
            advanceUntilIdle()
            callback.captured.onFailure(call, IOException("secret-marker"))
            advanceUntilIdle()
            assertTrue(result.isCancelled)
            assertNull(source.lastFailure)
            verify(atLeast = 1) { call.cancel() }
        }
}
