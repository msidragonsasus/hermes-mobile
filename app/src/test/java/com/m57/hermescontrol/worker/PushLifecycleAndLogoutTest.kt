package com.m57.hermescontrol.worker

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.CredentialTransportState
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import com.m57.hermescontrol.ui.settings.SettingsViewModel
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PushLifecycleAndLogoutTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(AuthManager, ApiClient, HermesWsClient, ProfileSwitchCoordinator)

        every { AuthManager.getHost() } returns "127.0.0.1"
        every { AuthManager.getPort() } returns 9119
        every { AuthManager.getBaseUrl() } returns "https://127.0.0.1:9119/"
        every { AuthManager.getAppLanguage() } returns "system"
        every { AuthManager.getToken() } returns "auth-token"
        every { AuthManager.isAutoReconnect() } returns true
        every { AuthManager.getThemePreference() } returns ThemePreference.SYSTEM
        every { AuthManager.isUseDynamicColors() } returns true
        every { AuthManager.getThemePreset() } returns ThemePreset.DEFAULT
        every { AuthManager.isTypingEffectEnabled() } returns true
        every { AuthManager.getTypingEffectDelayMs() } returns 30
        every { AuthManager.getChatFontScale() } returns 1.0f
        every { AuthManager.getConnectionProfiles() } returns emptyList()
        every { AuthManager.getSelectedProfileId() } returns null
        every { AuthManager.credentialTransportState() } returns CredentialTransportState("", null, 0, null)
        every { AuthManager.setToken(any()) } returns Unit
        every { AuthManager.setSessionCookie(any()) } returns Unit
        every { AuthManager.setWsAuthParam(any()) } returns Unit
        every { HermesWsClient.disconnect(any()) } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun pushCoordinator_skipsWhenUnconfiguredOrUnauthenticatedAndRegistersWhenReady() =
        runTest {
            var configured = false
            var authenticated = false
            var cachedToken: String? = null
            val registered = mutableListOf<PushRegisterRequest>()
            val unregistered = mutableListOf<PushUnregisterRequest>()

            val coordinator =
                PushTokenCoordinator(
                    isPushConfigured = { configured },
                    fetchToken = { "fcm-token-1" },
                    readCachedToken = { cachedToken },
                    saveCachedToken = { cachedToken = it },
                    isAuthenticated = { authenticated },
                    deviceLabel = { "Pixel Test" },
                    registerApi = { registered += it },
                    unregisterApi = { unregistered += it },
                )

            assertFalse(coordinator.registerCurrentToken())
            assertTrue(registered.isEmpty())

            configured = true
            assertFalse(coordinator.registerCurrentToken())
            assertTrue(registered.isEmpty())

            // onNewToken caches token even before authentication completes
            assertFalse(coordinator.onNewToken("fcm-token-2"))
            assertEquals("fcm-token-2", cachedToken)
            assertTrue(registered.isEmpty())

            authenticated = true
            assertTrue(coordinator.registerCurrentToken())
            assertEquals(listOf(PushRegisterRequest("fcm-token-1", "Pixel Test")), registered)
            assertEquals("fcm-token-1", cachedToken)

            assertTrue(coordinator.unregisterBeforeLogout())
            assertEquals(listOf(PushUnregisterRequest("fcm-token-1")), unregistered)
            assertNull(cachedToken)
        }

    @Test
    fun pushCoordinator_unregisterContinuesSafelyWhenApiFails() =
        runTest {
            var cachedToken: String? = "fcm-token-1"
            val coordinator =
                PushTokenCoordinator(
                    isPushConfigured = { true },
                    fetchToken = { null },
                    readCachedToken = { cachedToken },
                    saveCachedToken = { cachedToken = it },
                    isAuthenticated = { true },
                    deviceLabel = { "Pixel Test" },
                    registerApi = {},
                    unregisterApi = { error("network failure") },
                )

            assertFalse(coordinator.unregisterBeforeLogout())
            assertEquals("fcm-token-1", cachedToken)
        }

    @Test
    fun logout_unregistersPushBeforeWsDisconnectAndTokenClearing() =
        runTest {
            val pushLifecycle = mockk<WorkerPushLifecycle>()
            every { pushLifecycle.isConfigured() } returns true
            coEvery { pushLifecycle.unregisterBeforeLogout() } returns Unit

            val viewModel = SettingsViewModel(ioDispatcher = testDispatcher, pushLifecycle = pushLifecycle)
            advanceUntilIdle()

            var completed = false
            viewModel.logout { completed = true }
            advanceUntilIdle()

            assertTrue(completed)
            coVerifyOrder {
                pushLifecycle.unregisterBeforeLogout()
                HermesWsClient.disconnect(clearPendingMessages = true)
                AuthManager.setToken(null)
                AuthManager.setSessionCookie(null)
                AuthManager.setWsAuthParam("token")
            }
        }

    @Test
    fun logout_stillDisconnectsAndClearsCredentialsWhenUnregisterThrows() =
        runTest {
            val pushLifecycle = mockk<WorkerPushLifecycle>()
            every { pushLifecycle.isConfigured() } returns false
            coEvery { pushLifecycle.unregisterBeforeLogout() } throws IllegalStateException("server down")

            val viewModel = SettingsViewModel(ioDispatcher = testDispatcher, pushLifecycle = pushLifecycle)
            advanceUntilIdle()

            var completed = false
            viewModel.logout { completed = true }
            advanceUntilIdle()

            assertTrue(completed)
            coVerifyOrder {
                pushLifecycle.unregisterBeforeLogout()
                HermesWsClient.disconnect(clearPendingMessages = true)
                AuthManager.setToken(null)
                AuthManager.setSessionCookie(null)
                AuthManager.setWsAuthParam("token")
            }
        }
}
