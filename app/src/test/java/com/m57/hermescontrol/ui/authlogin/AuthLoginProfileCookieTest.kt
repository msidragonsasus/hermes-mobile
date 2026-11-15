package com.m57.hermescontrol.ui.authlogin

import android.app.Application
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import com.m57.hermescontrol.data.ws.GatewayWebSocketProbe
import com.m57.hermescontrol.data.ws.HermesWsClient
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
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
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** Real ticket HTTP request with mocked persistence/socket boundaries only. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthLoginProfileCookieTest {
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
    fun savedProfileMintsTicketWithItsSessionCookieOnAnAlternateAddress() =
        runTest {
            val profileId = "saved-dashboard"
            val profile = ConnectionProfile(id = profileId, name = "Saved", baseUrl = server.url("/hermes/").toString())
            val jar = CookieManager.cookieJar
            jar.useStore(profileId)
            jar.saveFromResponse(
                "http://previous-address.example/hermes/".toHttpUrl(),
                listOf(
                    Cookie
                        .Builder()
                        .name(
                            "hermes_session_at",
                        ).value(
                            "saved-session",
                        ).hostOnlyDomain("previous-address.example")
                        .path("/hermes")
                        .httpOnly()
                        .build(),
                ),
            )
            jar.useStore("active-other-profile")
            mockkObject(AuthManager, ApiClient, HermesWsClient, GatewayWebSocketProbe)
            every { AuthManager.getBaseUrl() } returns server.url("/hermes/").toString()
            every { AuthManager.getConnectionProfiles() } returns listOf(profile)
            every { AuthManager.getProfileToken(profileId) } returns "consumed-old-ticket"
            every { AuthManager.setSelectedProfileId(any()) } just Runs
            coEvery { AuthManager.syncCookieStoreForProfile(any()) } just Runs
            every { AuthManager.setWsAuthParam(any()) } just Runs
            every { ApiClient.rebuild() } just Runs
            every { HermesWsClient.connect() } just Runs
            every { HermesWsClient.connectInBackground() } just Runs
            coEvery { GatewayWebSocketProbe.check(any(), any(), any(), any()) } returns false
            coEvery { GatewayWebSocketProbe.check(any(), "ticket", "fresh-one-use", any()) } returns true
            server.enqueue(MockResponse().setBody("""{"ticket":"fresh-one-use"}"""))
            val vm = AuthLoginViewModel(mockk<Application>(relaxed = true))

            vm.useExistingProfile(profileId)
            val state = vm.uiState.first { !it.isLoading }

            assertTrue(state.connectionSuccess, "The same saved dashboard profile must survive an address change")
            val request = server.takeRequest(2, TimeUnit.SECONDS)
            assertEquals("/hermes/api/auth/ws-ticket", request?.path)
            assertEquals("hermes_session_at=saved-session", request?.getHeader("Cookie"))
            assertEquals("active-other-profile", jar.currentServer(), "Probing must not change the active cookie scope")
            coVerify(exactly = 1) { GatewayWebSocketProbe.check(any(), "ticket", "fresh-one-use", any()) }
            coVerify(exactly = 0) { GatewayWebSocketProbe.check(any(), "token", any(), any()) }
            verify(exactly = 1) { AuthManager.setSelectedProfileId(profileId) }
        }
}
