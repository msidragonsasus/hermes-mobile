package com.m57.hermescontrol.ui.bots

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ActiveProfileResponse
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.model.SpecialistCapabilitiesResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.SpecialistCapabilitiesState
import com.m57.hermescontrol.data.remote.SpecialistsRepository
import com.m57.hermescontrol.data.ws.HermesWsClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class BotsViewModelCapabilitiesTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(AuthManager)
        every { AuthManager.getHiddenProfiles() } returns emptyList()
        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        mockkObject(HermesWsClient)
        val deferred = CompletableDeferred<Any?>()
        deferred.complete(null)
        every { HermesWsClient.request(any(), any(), any()) } returns deferred

        coEvery { mockApi.getProfiles() } returns
            Response.success(ProfilesResponse(listOf(ProfileInfo(name = "default", is_default = true))))
        coEvery { mockApi.getActiveProfile() } returns
            Response.success(ActiveProfileResponse(active = "default"))
    }

    @After
    fun tearDown() {
        unmockkObject(HermesWsClient)
        unmockkObject(ApiClient)
        unmockkObject(AuthManager)
        Dispatchers.resetMain()
    }

    @Test
    fun groupRetirementGateRemainsOpenWhenCapabilityFalseOr404AndClosesOnlyWhenTrue() =
        runTest(testDispatcher) {
            coEvery { mockApi.getSpecialistCapabilities() } returns
                Response.success(
                    SpecialistCapabilitiesResponse(
                        version = 1,
                        setup = true,
                        brainstorm_results = true,
                        group_retirement_ready = false,
                    ),
                )
            val vm =
                BotsViewModel(
                    ioDispatcher = testDispatcher,
                    autoLoad = false,
                    specialistsRepository = SpecialistsRepository { mockApi },
                )
            vm.loadBots()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.capabilitiesState is SpecialistCapabilitiesState.Supported)

            coEvery { mockApi.getSpecialistCapabilities() } returns
                Response.error(404, """{"detail":"Not Found"}""".toResponseBody("application/json".toMediaType()))
            vm.loadBots(isRefresh = true)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.capabilitiesState is SpecialistCapabilitiesState.LegacyUnsupported)

            coEvery { mockApi.getSpecialistCapabilities() } returns
                Response.success(
                    SpecialistCapabilitiesResponse(
                        version = 1,
                        setup = true,
                        brainstorm_results = true,
                        group_retirement_ready = true,
                    ),
                )
            vm.loadBots(isRefresh = true)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.capabilitiesState is SpecialistCapabilitiesState.Supported)
        }
}
