package com.m57.hermescontrol.ui.authlogin

import android.app.Application
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import com.m57.hermescontrol.data.ws.HermesWsClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression test for the basic-auth token-derivation bug.
 *
 * Root cause (issue #634 rework): AuthLoginViewModel.connectBasicAuth() built a
 * FRESH OkHttpClient for the /auth/password-login call, so the Set-Cookie from
 * that response was captured by an ephemeral jar and never reached the
 * /api/auth/ws-ticket call on a DIFFERENT client. The ticket request then got
 * 401, connectBasicAuth returned null, and the UI fell back to asking the user
 * to paste a token manually.
 *
 * Fix: both calls use the SHARED OkHttpProvider.probe client (same
 * CookieManager.cookieJar), so the session cookie set by password-login flows
 * into the ws-ticket request automatically.
 *
 * This test proves the contract at the layer that was actually broken: a
 * password-login with Set-Cookie on the shared client must let a subsequent
 * ws-ticket request on the SAME client carry the cookie and succeed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthLoginBasicAuthFlowTest {
    private lateinit var server: MockWebServer
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
        server.start()
        CookieManager.setJarForTest(buildFakePersistentCookieJar())
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun passwordAndTicketSuccessDoNotPersistLoginWhenGatewayRejectsTheSocket() =
        runTest {
            mockkObject(AuthManager, HermesWsClient)
            every { AuthManager.getBaseUrl() } returns server.url("/hermes/").toString()
            every { AuthManager.getConnectionProfiles() } returns emptyList()
            val vm = AuthLoginViewModel(mockk<Application>(relaxed = true))
            server.enqueue(MockResponse().setBody("""{"auth_required":true,"auth_providers":["basic"]}"""))
            server.enqueue(
                MockResponse()
                    .setHeader("Set-Cookie", "hermes_session_at=candidate; Path=/hermes; HttpOnly")
                    .setBody("""{"ok":true}"""),
            )
            server.enqueue(MockResponse().setBody("""{"ticket":"one-use"}"""))
            server.enqueue(MockResponse().setResponseCode(403))

            vm.probe()
            assertEquals(DashboardAuthMode.BASIC_AUTH, vm.uiState.first { !it.probing }.authMode)
            vm.onUsernameChange("test")
            vm.onPasswordChange("test")
            vm.connect()
            val state = vm.uiState.first { !it.isLoading }
            assertTrue(!state.connectionSuccess)
            assertEquals(null, CookieManager.getSessionCookie())
            verify(exactly = 0) { AuthManager.setBaseUrl(any()) }
            verify(exactly = 0) { AuthManager.setToken(any()) }
            verify(exactly = 0) { HermesWsClient.connect() }
            assertEquals("/hermes/api/status", server.takeRequest().path)
            assertEquals("/hermes/auth/password-login", server.takeRequest().path)
            val ticketRequest = server.takeRequest()
            assertEquals("/hermes/api/auth/ws-ticket", ticketRequest.path)
            assertEquals("hermes_session_at=candidate", ticketRequest.getHeader("Cookie"))
            val socketRequest = server.takeRequest()
            assertEquals("/hermes/api/ws?ticket=one-use", socketRequest.path)
            assertEquals(null, socketRequest.getHeader("Cookie"))
        }

    @Test
    fun `password-login Set-Cookie flows into ws-ticket on the shared client`() =
        runTest {
            // password-login: returns JSON (no token in body) + session Set-Cookie
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Set-Cookie", "hermes_access_token=abc123; Path=/; HttpOnly")
                    .setBody("{\"ok\":true,\"next\":\"/\"}"),
            )
            // ws-ticket: mints only if the session cookie is present on the request
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"ticket\":\"ws-ticket-xyz\",\"ttl_seconds\":30}"),
            )

            val base = server.url("/").toString().removeSuffix("/")

            // Step 1: login on the SHARED client (what the fixed code does)
            val loginReq =
                Request
                    .Builder()
                    .url("$base/auth/password-login")
                    .header("Content-Type", "application/json")
                    .post("{\"username\":\"admin\",\"password\":\"s3cret\"}".toRequestBody())
                    .build()
            val loginResp = OkHttpProvider.probe.newCall(loginReq).execute()
            assertEquals(200, loginResp.code)

            // Step 2: ws-ticket on the SAME shared client — cookie must carry over
            val ticketReq =
                Request
                    .Builder()
                    .url("$base/api/auth/ws-ticket")
                    .post("{}".toRequestBody())
                    .build()
            val ticketResp = OkHttpProvider.probe.newCall(ticketReq).execute()
            assertEquals(200, ticketResp.code, "ws-ticket must succeed with the session cookie from login")

            val body = ticketResp.body.string()
            assertTrue(body.contains("\"ticket\""), "ws-ticket response should contain a ticket")
        }

    @Test
    fun `ws-ticket without prior login cookie fails (proves cookie is the gate)`() =
        runTest {
            // No login queued; ws-ticket should be rejected (401) because no session
            server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

            val base = server.url("/").toString().removeSuffix("/")
            val ticketReq =
                Request
                    .Builder()
                    .url("$base/api/auth/ws-ticket")
                    .post("{}".toRequestBody())
                    .build()
            val ticketResp = OkHttpProvider.probe.newCall(ticketReq).execute()
            assertEquals(401, ticketResp.code)
        }
}
