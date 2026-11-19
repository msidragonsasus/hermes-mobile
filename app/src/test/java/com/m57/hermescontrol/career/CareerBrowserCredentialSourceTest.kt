package com.m57.hermescontrol.career

import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CareerBrowserCredentialSourceTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<OkHttpClient>(relaxed = true)
    private val call = mockk<Call>(relaxed = true)
    private val callback = slot<Callback>()
    private val request = slot<Request>()
    private val base = MutableStateFlow("https://gateway.example.test/")
    private val profile = MutableStateFlow<String?>("phone")
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
        every { AuthManager.getSessionCookie() } returns "fixture-session"
        every { client.newCall(capture(request)) } returns call
        every { call.enqueue(capture(callback)) } returns Unit
    }

    @After
    fun cleanup() {
        unmockkObject(AuthManager)
        Dispatchers.resetMain()
    }

    private fun source() = CareerBrowserCredentialSource(careerScope()) { client }

    private fun approval(expires: Long = System.nanoTime() + 60_000_000_000L) =
        CareerBrowserApproval(
            "a".repeat(64),
            "dummy-company",
            "default",
            1,
            policy.loginUrl.toString(),
            careerScope(),
            expires,
        )

    private fun respond(
        company: String = "dummy-company",
        extra: String = "",
        login: String? = "dummy-id",
        versionJson: String = "1",
    ) {
        val id = login?.let { "\"$it\"" } ?: "null"
        val json = """{"company_id":"$company","profile":"default","connection_version":$versionJson,
            "url":"https://portal.example.test/company/login","login_id":$id,"password":"dummy-password"$extra} """
        callback.captured.onResponse(
            call,
            Response
                .Builder()
                .request(request.captured)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody())
                .build(),
        )
    }

    private fun respondLogin(
        company: String = "dummy-company",
        returnedProfile: String = "default",
        version: String = "1",
        extra: String = "",
        url: String = "https://portal.example.test/company/login",
        status: Int = 200,
    ) {
        val body = """{"company_id":"$company","profile":"$returnedProfile","connection_version":$version,
            "url":"$url","login_id":"dummy-readable-id"$extra}"""
        callback.captured.onResponse(
            call,
            Response
                .Builder()
                .request(request.captured)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("fixture")
                .body(body.toResponseBody())
                .build(),
        )
    }

    @Test
    fun savedLoginUsesPasswordFreeEndpointAndReturnsPlainId() =
        runTest(dispatcher) {
            val operation = async { source().savedLoginId("dummy-company", 1, policy) }
            advanceUntilIdle()
            assertEquals("/api/plugins/career/mypage/login-id", request.captured.url.encodedPath)
            assertEquals("no-store", request.captured.header("Cache-Control"))
            respondLogin()
            advanceUntilIdle()
            assertEquals("dummy-readable-id", operation.await())
        }

    @Test
    fun savedLoginRejectsWrongCompanyProfileVersionAndPasswordBearingReply() =
        runTest(dispatcher) {
            val replies: List<() -> Unit> =
                listOf(
                    { respondLogin(company = "other") },
                    { respondLogin(returnedProfile = "other") },
                    { respondLogin(version = "2") },
                    { respondLogin(url = "https://other.example.test/login") },
                    { respondLogin(version = "\"1\"") },
                    { respondLogin(extra = ",\"password\":\"must-not-display\"") },
                    { respondLogin(status = 404) },
                    { respondLogin(status = 503) },
                )
            for (reply in replies) {
                val operation = async { source().savedLoginId("dummy-company", 1, policy) }
                advanceUntilIdle()
                reply()
                advanceUntilIdle()
                assertEquals(null, operation.await())
            }
        }

    @Test
    fun savedLoginRejectsConnectionSwitchBeforeReply() =
        runTest(dispatcher) {
            val operation = async { source().savedLoginId("dummy-company", 1, policy) }
            advanceUntilIdle()
            base.value = "https://other-gateway.example.test/"
            respondLogin()
            advanceUntilIdle()
            assertEquals(null, operation.await())
        }

    @Test
    fun savedLoginRejectsAccountSessionSwitchOnSameProfile() =
        runTest(dispatcher) {
            val operation = async { source().savedLoginId("dummy-company", 1, policy) }
            advanceUntilIdle()
            every { AuthManager.getSessionCookie() } returns "different-fixture-session"
            respondLogin()
            advanceUntilIdle()
            assertEquals(null, operation.await())
        }

    @Test
    fun savedLoginCancellationDiscardsLateResponse() =
        runTest(dispatcher) {
            val operation = async { source().savedLoginId("dummy-company", 1, policy) }
            advanceUntilIdle()
            operation.cancel()
            advanceUntilIdle()
            respondLogin()
            advanceUntilIdle()
            assertTrue(operation.isCancelled)
            verify(atLeast = 1) { call.cancel() }
        }

    @Test
    fun validReplyIsUsedTransientlyThenOwnedBuffersAreWiped() =
        runTest(dispatcher) {
            var retained: CareerBrowserCredentials? = null
            val operation =
                async {
                    source().use("dummy-company", 1, policy, approval()) {
                        retained = it
                        true
                    }
                }
            advanceUntilIdle()
            respond()
            advanceUntilIdle()
            assertTrue(operation.await())
            assertTrue(checkNotNull(retained).password.all { it == '\u0000' })
            assertFalse(
                request.captured.url
                    .toString()
                    .contains("dummy-password"),
            )
            assertEquals(null, request.captured.header("Authorization"))
        }

    @Test
    fun foreignReplyAndOversizedReplyCannotReachTheBrowser() =
        runTest(dispatcher) {
            var invoked = false
            val foreign =
                async {
                    source().use("dummy-company", 1, policy, approval()) {
                        invoked = true
                        true
                    }
                }
            advanceUntilIdle()
            respond(company = "other-company")
            advanceUntilIdle()
            assertFalse(foreign.await())
            val oversized =
                async {
                    source().use("dummy-company", 1, policy, approval()) {
                        invoked = true
                        true
                    }
                }
            advanceUntilIdle()
            respond(extra = ",\"padding\":\"${"x".repeat(17_000)}\"")
            advanceUntilIdle()
            assertFalse(oversized.await())
            assertFalse(invoked)
        }

    @Test
    fun leavingCancelsNetworkAndLateReplyCannotFill() =
        runTest(dispatcher) {
            var invoked = false
            val operation =
                async {
                    source().use("dummy-company", 1, policy, approval()) {
                        invoked = true
                        true
                    }
                }
            advanceUntilIdle()
            operation.cancel()
            advanceUntilIdle()
            verify(atLeast = 1) { call.cancel() }
            respond()
            advanceUntilIdle()
            assertFalse(invoked)
        }

    @Test
    fun rejectedActionAndJsonNullAreNotSuccessfulInput() =
        runTest(dispatcher) {
            val rejected = async { source().use("dummy-company", 1, policy, approval()) { false } }
            advanceUntilIdle()
            respond()
            advanceUntilIdle()
            assertFalse(rejected.await())
            val nullValue =
                async { source().use("dummy-company", 1, policy, approval()) { error("must not fill null") } }
            advanceUntilIdle()
            respond(login = null)
            advanceUntilIdle()
            assertFalse(nullValue.await())
        }

    @Test
    fun quotedNullBooleanAndFractionalConnectionVersionsAreRejected() =
        runTest(dispatcher) {
            for (versionJson in listOf("\"1\"", "null", "true", "1.0")) {
                val operation =
                    async { source().use("dummy-company", 1, policy, approval()) { error("must not fill") } }
                advanceUntilIdle()
                respond(versionJson = versionJson)
                advanceUntilIdle()
                assertFalse(operation.await())
            }
        }

    @Test
    fun confirmationPreparationCannotRetrieveCredentialsAndRejectsForeignTarget() =
        runTest(dispatcher) {
            val operation = async { source().prepare("dummy-company", 1, policy) }
            advanceUntilIdle()
            assertTrue(
                request.captured.url.encodedPath
                    .endsWith("/browser-access/prepare"),
            )
            assertEquals("once", request.captured.header("X-Hermes-Browser-Consent"))
            respond(company = "other-company", extra = ",\"approval_id\":\"${"a".repeat(64)}\",\"expires_in\":60")
            advanceUntilIdle()
            assertEquals(null, operation.await())
        }

    @Test
    fun approvedRequestCarriesOnceIdentifierAndCannotReplayOrOutliveDeadline() =
        runTest(dispatcher) {
            val consent = approval()
            val operation = async { source().use("dummy-company", 1, policy, consent) { true } }
            advanceUntilIdle()
            val body = okio.Buffer()
            request.captured.body?.writeTo(body)
            assertTrue(body.readUtf8().contains("\"approval_id\":\"${"a".repeat(64)}\""))
            respond()
            advanceUntilIdle()
            assertTrue(operation.await())
            assertFalse(source().use("dummy-company", 1, policy, consent) { error("cannot replay") })
            assertFalse(source().use("dummy-company", 1, policy, approval(0)) { error("expired") })
            verify(exactly = 1) { client.newCall(any()) }
        }

    @Test
    fun confirmationCannotMoveToAnotherCompanyOrConnectionScope() =
        runTest(dispatcher) {
            val consent = approval()
            assertFalse(source().use("other-company", 1, policy, consent) { error("foreign company") })
            base.value = "https://other-gateway.example.test/"
            assertFalse(source().use("dummy-company", 1, policy, consent) { error("foreign scope") })
            verify(exactly = 0) { client.newCall(any()) }
        }

    @Test
    fun currentTargetPermissionSkipsAnotherDialogAndUsesExistingPermissionMode() =
        runTest(dispatcher) {
            val prepared = async { source().prepare("dummy-company", 1, policy) }
            advanceUntilIdle()
            respond(extra = ",\"approval_id\":\"${"a".repeat(64)}\",\"expires_in\":60,\"requires_confirmation\":false")
            advanceUntilIdle()
            val consent = checkNotNull(prepared.await())
            assertFalse(consent.requiresConfirmation)
            val operation = async { source().use("dummy-company", 1, policy, consent) { true } }
            advanceUntilIdle()
            val body = okio.Buffer()
            request.captured.body?.writeTo(body)
            assertTrue(body.readUtf8().contains("\"consent_mode\":\"use_target\""))
            respond()
            advanceUntilIdle()
            assertTrue(operation.await())
        }

    @Test
    fun onStopCancelsWithoutDisposalAndDestroyDetachesBeforeDisposal() {
        val browser = mockk<CareerBrowserWebView>(relaxed = true)
        val owner = mockk<LifecycleOwner>()
        var current: CareerBrowserWebView? = browser
        var cancelled = false
        every { browser.dispose() } answers {
            assertTrue(cancelled)
            assertEquals(null, current)
        }
        val observer =
            careerBrowserLifecycleObserver(
                pauseSession = { cancelled = true },
                resumeSession = { true },
                browser = { current },
                disposeSession = {
                    cancelled = true
                    current.also { current = null }?.dispose()
                },
            )
        observer.onStateChanged(owner, Lifecycle.Event.ON_START)
        verify(exactly = 0) { browser.dispose() }
        observer.onStateChanged(owner, Lifecycle.Event.ON_PAUSE)
        assertTrue(cancelled)
        assertEquals(browser, current)
        observer.onStateChanged(owner, Lifecycle.Event.ON_STOP)
        assertEquals(browser, current)
        verify(exactly = 0) { browser.dispose() }
        observer.onStateChanged(owner, Lifecycle.Event.ON_DESTROY)
        assertEquals(null, current)
        verify(exactly = 1) { browser.dispose() }
    }

    @Test
    fun nativeHeadersReplaceCaseVariantsAndRetainTheOriginCsp() {
        val headers =
            careerBrowserResponseHeaders(
                mapOf(
                    "cache-control" to listOf("public"),
                    "CACHE-CONTROL" to listOf("max-age=3600"),
                    "referrer-policy" to listOf("unsafe-url"),
                    "server-timing" to listOf("hermes;desc=\"wrong-document\""),
                    "content-security-policy" to listOf("default-src 'self'"),
                    "set-cookie" to listOf("dummy=fixture"),
                    "content-encoding" to listOf("gzip"),
                    "content-length" to listOf("999"),
                ),
                "dummy-native-token",
            )
        for (name in listOf("Cache-Control", "Referrer-Policy", "Server-Timing", "Content-Security-Policy")) {
            assertEquals(1, headers.keys.count { it.equals(name, true) })
        }
        assertEquals("no-store", headers["Cache-Control"])
        assertEquals("unsafe-url", headers["referrer-policy"])
        assertEquals("hermes;desc=\"dummy-native-token\"", headers["Server-Timing"])
        assertEquals("default-src 'self'", headers["content-security-policy"])
        assertFalse(headers.keys.any { it.lowercase() in setOf("set-cookie", "content-encoding", "content-length") })
    }

    @Test
    fun connectionChangeFailsClosed() =
        runTest(dispatcher) {
            var invoked = false
            val operation =
                async {
                    source().use("dummy-company", 1, policy, approval()) {
                        invoked = true
                        true
                    }
                }
            advanceUntilIdle()
            base.value = "https://other-gateway.example.test/"
            respond()
            advanceUntilIdle()
            assertFalse(operation.await())
            assertFalse(invoked)
        }

    @Test
    fun rapidReentryWaitsForCookieCleanupAndDisposalRemovesQueuedView() {
        val first = mockk<WebView>()
        val second = mockk<WebView>()
        val cancelled = mockk<WebView>()
        val order = mutableListOf<Int>()
        try {
            CareerBrowserLease.acquire(first) { order += 1 }
            CareerBrowserLease.acquire(cancelled) { order += 99 }
            CareerBrowserLease.acquire(second) { order += 2 }
            assertEquals(listOf(1), order)
            CareerBrowserLease.cancel(cancelled)
            CareerBrowserLease.release(first)
            assertEquals(listOf(1, 2), order)
        } finally {
            CareerBrowserLease.cancel(cancelled)
            CareerBrowserLease.cancel(second)
            CareerBrowserLease.release(first)
            CareerBrowserLease.release(second)
        }
    }
}
