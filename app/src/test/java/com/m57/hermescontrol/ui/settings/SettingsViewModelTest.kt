package com.m57.hermescontrol.ui.settings

import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.CredentialTransportConsent
import com.m57.hermescontrol.data.local.CredentialTransportState
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

/**
 * Tests for [SettingsViewModel] profile operations.
 *
 * TEST-05 (issue #292)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private var storedSelectedProfileId: String? = null

    private val testProfiles =
        listOf(
            ConnectionProfile(id = "prof-1", name = "Work", baseUrl = "http://10.0.0.1:9119/"),
            ConnectionProfile(id = "prof-2", name = "Home", baseUrl = "http://10.0.0.2:9220/"),
        )

    private fun createViewModel(): SettingsViewModel {
        val vm = SettingsViewModel(ioDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockkObject(AuthManager)
        mockkObject(ApiClient)

        // Default stubs
        storedSelectedProfileId = null

        every { AuthManager.getHost() } returns "127.0.0.1"
        every { AuthManager.getPort() } returns 9119
        every { AuthManager.getBaseUrl() } returns "https://127.0.0.1:9119/"
        every { AuthManager.getAppLanguage() } returns "system"
        every { AuthManager.getToken() } returns ""
        every { AuthManager.isAutoReconnect() } returns true
        every { AuthManager.getThemePreference() } returns ThemePreference.SYSTEM
        every { AuthManager.isUseDynamicColors() } returns true
        every { AuthManager.getThemePreset() } returns ThemePreset.DEFAULT
        every { AuthManager.isTypingEffectEnabled() } returns true
        every { AuthManager.getTypingEffectDelayMs() } returns 30
        every { AuthManager.getChatFontScale() } returns 1.0f
        every { AuthManager.getConnectionProfiles() } returns emptyList()
        every { AuthManager.getSelectedProfileId() } answers { storedSelectedProfileId }
        every { AuthManager.credentialTransportState() } returns CredentialTransportState("", null, 0, null)
        every { AuthManager.isGatedMode() } returns true
        every { AuthManager.baseUrl() } returns "http://127.0.0.1:9119/"
        every { AuthManager.setBaseUrl(any()) } returns Unit
        every { AuthManager.setToken(any()) } returns Unit
        every { AuthManager.setAutoReconnect(any()) } returns Unit
        every { AuthManager.setThemePreference(any()) } returns Unit
        every { AuthManager.setUseDynamicColors(any()) } returns Unit
        every { AuthManager.setThemePreset(any()) } returns Unit
        every { AuthManager.setTypingEffectEnabled(any()) } returns Unit
        every { AuthManager.setTypingEffectDelayMs(any()) } returns Unit
        every { AuthManager.setChatFontScale(any()) } returns Unit
        every { AuthManager.setSelectedProfileId(any()) } answers {
            storedSelectedProfileId = firstArg()
        }
        every { AuthManager.saveConnectionProfiles(any()) } returns Unit
        every { AuthManager.setProfileToken(any(), any()) } returns Unit
        every { AuthManager.getProfileToken(any()) } returns null
        every { AuthManager.ensureDefaultProfile() } returns Unit

        // selectProfile/deleteProfile route the switch through the coordinator
        // (selection + Retrofit + WebSocket re-dial). Mirror the selection
        // side-effect like the real coordinator's setSelectedProfileId does,
        // so loadSettings() observes the new selection.
        mockkObject(ProfileSwitchCoordinator)
        coEvery { ProfileSwitchCoordinator.switchConnectionProfile(any()) } answers {
            storedSelectedProfileId = firstArg()
            Unit
        }
        every { ApiClient.rebuild() } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun settingsGrantsMainWhileSpecialistIsSelectedWithoutChangingDrafts() =
        runTest {
            val fixture = configureCredentialTransport()
            val viewModel = createViewModel()
            assertEquals("default", viewModel.uiState.value.credentialTransportProfile)
            viewModel.onDialogProfileNameChange("unsaved name")
            viewModel.onDialogProfileBaseUrlChange("https://draft.example.test/")
            fixture.profile.value = "profile-b"
            val observer = launch { viewModel.observeCredentialTransport() }
            advanceUntilIdle()
            assertEquals("default", viewModel.uiState.value.credentialTransportProfile)
            assertEquals("unsaved name", viewModel.uiState.value.dialogProfileName)
            assertEquals("https://draft.example.test/", viewModel.uiState.value.dialogProfileBaseUrl)
            viewModel.onCredentialTransportChange(true)
            advanceUntilIdle()
            assertEquals(fixture.endpoint, viewModel.uiState.value.credentialTransportConfirmEndpoint)
            assertEquals("default", viewModel.uiState.value.credentialTransportConfirmProfile)
            viewModel.confirmCredentialTransport()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.credentialTransportAllowed)
            verify(exactly = 1) { AuthManager.enableCredentialTransport(match { it.serverProfile == "default" }) }
            verify(exactly = 0) { AuthManager.enableCredentialTransport(match { it.serverProfile == "profile-b" }) }
            assertEquals("unsaved name", viewModel.uiState.value.dialogProfileName)
            observer.cancel()
        }

    @Test
    fun connectionChangeAfterPromptDismissesItAndCannotGrantTheReplacementConnection() =
        runTest {
            val fixture = configureCredentialTransport()
            val viewModel = createViewModel()
            val observer = launch { viewModel.observeCredentialTransport() }
            advanceUntilIdle()
            viewModel.onCredentialTransportChange(true)
            advanceUntilIdle()
            assertEquals("default", viewModel.uiState.value.credentialTransportConfirmProfile)
            fixture.base.value = "http://100.64.10.21:9119/"
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.credentialTransportConfirmEndpoint)
            assertNull(viewModel.uiState.value.credentialTransportConfirmProfile)
            assertEquals("default", viewModel.uiState.value.credentialTransportProfile)
            viewModel.confirmCredentialTransport()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.credentialTransportAllowed)
            assertNull(fixture.saved)
            verify(exactly = 0) { AuthManager.enableCredentialTransport(any()) }
            observer.cancel()
        }

    @Test
    fun credentialTransportDefaultsOffAndCancelDoesNotPersist() =
        runTest {
            val fixture = configureCredentialTransport()
            val viewModel = createViewModel()
            assertFalse(viewModel.uiState.value.credentialTransportAllowed)
            viewModel.onCredentialTransportChange(true)
            advanceUntilIdle()
            assertEquals(fixture.endpoint, viewModel.uiState.value.credentialTransportConfirmEndpoint)
            assertNull(fixture.saved)
            viewModel.dismissCredentialTransportConfirmation()
            assertNull(viewModel.uiState.value.credentialTransportConfirmEndpoint)
            assertNull(fixture.saved)
        }

    @Test
    fun credentialTransportExplicitConfirmationEnablesAndOffRevokes() =
        runTest {
            val fixture = configureCredentialTransport()
            val viewModel = createViewModel()
            viewModel.onCredentialTransportChange(true)
            advanceUntilIdle()
            viewModel.confirmCredentialTransport()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.credentialTransportAllowed)
            assertTrue(fixture.saved != null)
            viewModel.onCredentialTransportChange(false)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.credentialTransportAllowed)
            assertNull(fixture.saved)
        }

    @Test
    fun credentialTransportStaleConfirmationAndPersistenceFailureRemainOff() =
        runTest {
            val fixture = configureCredentialTransport()
            val viewModel = createViewModel()
            viewModel.onCredentialTransportChange(true)
            advanceUntilIdle()
            fixture.consent.invalidateContext()
            fixture.consent.invalidateContext()
            viewModel.confirmCredentialTransport()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.credentialTransportAllowed)
            assertTrue(viewModel.uiState.value.credentialTransportError)
            assertNull(fixture.saved)
            viewModel.onCredentialTransportChange(true)
            advanceUntilIdle()
            fixture.failWrite = true
            viewModel.confirmCredentialTransport()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.credentialTransportAllowed)
            assertTrue(viewModel.uiState.value.credentialTransportError)
            assertNull(fixture.saved)
        }

    private class TransportFixture {
        val base = MutableStateFlow("http://100.64.10.20:9119/")
        val profile = MutableStateFlow("profile-a")
        val endpoint: String get() = base.value
        val scope: String get() = "$endpoint\nconnection-a\ndefault"
        var saved: String? = null
        var failWrite = false
        val consent =
            CredentialTransportConsent(
                read = { saved },
                write = {
                    check(!failWrite)
                    saved = it
                },
            )
    }

    private fun configureCredentialTransport(): TransportFixture {
        val fixture = TransportFixture()
        every { AuthManager.getBaseUrl() } answers { fixture.endpoint }
        every { AuthManager.baseUrlFlow } returns fixture.base
        every { AuthManager.selectedProfileFlow } returns MutableStateFlow("connection-a")
        every { AuthManager.activeProfileId } returns fixture.profile
        every { AuthManager.credentialTransportChanges } returns fixture.consent.changes
        every { AuthManager.credentialTransportState() } answers
            { fixture.consent.state(fixture.scope, fixture.endpoint) }
        every { AuthManager.enableCredentialTransport(any()) } answers {
            fixture.consent.enable(firstArg(), fixture.scope, fixture.endpoint)
        }
        every { AuthManager.disableCredentialTransport() } answers { fixture.consent.invalidateContext() }
        return fixture
    }

    @Test
    fun testLoadSettings_loadsProfiles() {
        every { AuthManager.getConnectionProfiles() } returns testProfiles
        storedSelectedProfileId = "prof-1"

        val viewModel = createViewModel()
        val state = viewModel.uiState.value

        assertEquals(2, state.profiles.size)
        assertEquals("prof-1", state.selectedProfileId)
        assertEquals("Work", state.renameProfileName)
    }

    @Test
    fun testLoadSettings_loadsAllProperties() =
        runTest {
            every { AuthManager.getSelectedProfileId() } answers { "prof-2" }
            every { AuthManager.getBaseUrl() } returns "https://192.168.1.10:9119/"
            every { AuthManager.getToken() } returns "dummy_token"
            every { AuthManager.isAutoReconnect() } returns false
            every { AuthManager.getThemePreference() } returns ThemePreference.DARK
            every { AuthManager.isUseDynamicColors() } returns false
            every { AuthManager.getThemePreset() } returns ThemePreset.CATPPUCCIN
            every { AuthManager.isTypingEffectEnabled() } returns false
            every { AuthManager.getTypingEffectDelayMs() } returns 15
            every { AuthManager.getChatFontScale() } returns 1.30f
            every { AuthManager.getConnectionProfiles() } returns testProfiles
            every { AuthManager.getAppLanguage() } returns "fr"

            val viewModel = SettingsViewModel(ioDispatcher = testDispatcher)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value

            assertEquals("https://192.168.1.10:9119/", state.baseUrl)
            assertEquals("dummy_token", state.token)
            assertEquals(false, state.autoReconnect)
            assertEquals(ThemePreference.DARK, state.themePreference)
            assertEquals(false, state.useDynamicColors)
            assertEquals(ThemePreset.CATPPUCCIN, state.themePreset)
            assertEquals(false, state.typingEffectEnabled)
            assertEquals(15, state.typingEffectDelayMs)
            assertEquals(1.30f, state.chatFontScale)
            assertEquals(testProfiles, state.profiles)
            assertEquals("prof-2", state.selectedProfileId)
            assertEquals("Home", state.renameProfileName)
            assertEquals("fr", state.appLanguage)
        }

    @Test
    fun testOnChatFontScaleChange_updatesStateAndAuthManager() {
        val viewModel = createViewModel()

        viewModel.onChatFontScaleChange(1.15f)

        assertEquals(1.15f, viewModel.uiState.value.chatFontScale)
        verify { AuthManager.setChatFontScale(1.15f) }
    }

    @Test
    fun testLoadSettings_noSelectedProfile_renameEmpty() {
        every { AuthManager.getConnectionProfiles() } returns testProfiles

        val viewModel = createViewModel()
        assertEquals("", viewModel.uiState.value.renameProfileName)
    }

    @Test
    fun testSelectProfile_updatesAndRebuildsApi() {
        every { AuthManager.getConnectionProfiles() } returns testProfiles

        val viewModel = createViewModel()

        viewModel.selectProfile("prof-2")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { ProfileSwitchCoordinator.switchConnectionProfile("prof-2") }
        assertEquals("prof-2", viewModel.uiState.value.selectedProfileId)
        assertEquals("Home", viewModel.uiState.value.renameProfileName)
    }

    @Test
    fun testSelectProfile_nullFallsBackToDefault() {
        every { AuthManager.getConnectionProfiles() } returns testProfiles
        storedSelectedProfileId = "prof-1"

        val viewModel = createViewModel()

        viewModel.selectProfile(AuthManager.DEFAULT_PROFILE_ID)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { ProfileSwitchCoordinator.switchConnectionProfile(AuthManager.DEFAULT_PROFILE_ID) }
        assertEquals(AuthManager.DEFAULT_PROFILE_ID, viewModel.uiState.value.selectedProfileId)
    }

    @Test
    fun testDeleteProfile_removesProfileAndToken() {
        every { AuthManager.getConnectionProfiles() } returns testProfiles
        every { AuthManager.getSelectedProfileId() } returns "prof-1"

        val viewModel = createViewModel()

        viewModel.deleteProfile("prof-1")
        testDispatcher.scheduler.advanceUntilIdle()

        verify { AuthManager.saveConnectionProfiles(any()) }
        verify { AuthManager.setProfileToken("prof-1", null) }
        // The deleted profile was selected — the fallback switch re-homes
        // everything (selection + Retrofit + WebSocket) to default.
        coVerify { ProfileSwitchCoordinator.switchConnectionProfile(AuthManager.DEFAULT_PROFILE_ID) }
    }

    @Test
    fun testDeleteProfile_nonSelected_doesNotChangeSelectedId() {
        every { AuthManager.getConnectionProfiles() } returns testProfiles
        every { AuthManager.getSelectedProfileId() } returns "prof-1"

        val viewModel = createViewModel()

        viewModel.deleteProfile("prof-2")
        testDispatcher.scheduler.advanceUntilIdle()

        verify { AuthManager.saveConnectionProfiles(any()) }
        verify { AuthManager.setProfileToken("prof-2", null) }
        // Selected profile (prof-1) was NOT deleted — should NOT change selection
        verify(exactly = 0) { AuthManager.setSelectedProfileId(any()) }
        coVerify(exactly = 0) { ProfileSwitchCoordinator.switchConnectionProfile(any()) }
        verify { ApiClient.rebuild() }
    }

    @Test
    fun testSaveProfileFromDialog_addsNewProfileAndTriggersLoginRedirection() {
        every { AuthManager.getConnectionProfiles() } returns emptyList()
        val viewModel = createViewModel()

        viewModel.openAddProfile()
        viewModel.onDialogProfileNameChange("Test Profile")
        viewModel.onDialogProfileBaseUrlChange("https://192.168.1.100:9999/")

        viewModel.saveProfileFromDialog()
        testDispatcher.scheduler.advanceUntilIdle()

        verify { AuthManager.saveConnectionProfiles(any()) }
        verify { AuthManager.setSelectedProfileId(any()) }
        assertEquals(true, viewModel.uiState.value.navigateToLogin)
    }
}
