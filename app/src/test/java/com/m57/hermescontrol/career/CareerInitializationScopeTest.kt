package com.m57.hermescontrol.career

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CareerInitializationScopeTest {
    private val dispatcher = StandardTestDispatcher()
    private val baseUrl = MutableStateFlow("https://first.invalid")
    private val connection = MutableStateFlow<String?>("local-first")
    private val profile = MutableStateFlow<String?>("profile-first")
    private val api = mockk<HermesApiService>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(AuthManager, ApiClient)
        every { AuthManager.baseUrlFlow } returns baseUrl
        every { AuthManager.selectedProfileFlow } returns connection
        every { AuthManager.activeProfileId } returns profile
        every { AuthManager.isGatedMode() } returns true
        every { ApiClient.hermesApi } returns api
        coEvery { api.careerSnapshot() } returns CareerSnapshot(1)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun urlAndConnectionIdScopeAppDataWhileActiveBotDoesNot() {
        val first = careerScope()
        assertEquals("https://first.invalid\nlocal-first\ndefault", first)
        baseUrl.value = "https://second.invalid"
        val secondUrl = careerScope()
        baseUrl.value = "https://first.invalid"
        connection.value = "local-second"
        val secondConnection = careerScope()
        connection.value = "local-first"
        profile.value = "profile-second"
        val secondProfile = careerScope()
        assertEquals(first, secondProfile)
        assertEquals(3, setOf(first, secondUrl, secondConnection).size)
    }

    @Test
    fun returningToDifferentScopeNeverEnablesItOrReusesOtherScopeCache() =
        runTest(dispatcher) {
            val firstScope = careerScope()
            val firstLocal = mockk<CareerLocalStore>()
            coEvery { firstLocal.enabled() } returns true
            coEvery { firstLocal.cached() } returns CareerSnapshot(1)
            coEvery { firstLocal.pending() } returns null
            coEvery { firstLocal.cache(any()) } returns Unit
            val first = CareerViewModel(DashboardCareerRepository(firstScope), firstLocal)
            advanceUntilIdle()
            connection.value = "local-second"
            assertNotEquals(firstScope, careerScope())
            val secondLocal = mockk<CareerLocalStore>()
            coEvery { secondLocal.enabled() } returns false
            val second = CareerViewModel(DashboardCareerRepository(careerScope()), secondLocal)
            advanceUntilIdle()
            assertTrue(first.state.value.enabled)
            assertFalse(second.state.value.enabled)
            assertTrue(second.state.value.initialized)
            first.refresh()
            advanceUntilIdle()
            assertTrue(first.state.value.stale)
            coVerify(exactly = 1) { api.careerSnapshot() }
            coVerify(exactly = 0) { api.careerMutation(any(), any()) }
            coVerify(exactly = 0) { firstLocal.setEnabled(any()) }
            coVerify(exactly = 0) { secondLocal.setEnabled(any()) }
            coVerify(exactly = 0) { secondLocal.cached() }
            coVerify(exactly = 0) { secondLocal.cache(any()) }
            coVerify(exactly = 0) { secondLocal.savePending(any()) }
        }
}
