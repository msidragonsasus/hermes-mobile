package com.m57.hermescontrol.home

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

class TodayPlanStateTest {
    private var scope = "first"
    private val api = mockk<HermesApiService>()
    private val dao = mockk<HomeDao>(relaxed = true)
    private val repository = mockk<HomeRepository>()

    @Before
    fun setup() {
        mockkObject(AuthManager, ApiClient)
        every { AuthManager.uiPreferenceScope() } answers { scope }
        every { AuthManager.activeProfileId } returns MutableStateFlow("default")
        every { AuthManager.isGatedMode() } returns true
        every { ApiClient.hermesApi } returns api
        every { repository.dao } returns dao
        every { repository.json } returns Json { ignoreUnknownKeys = true }
    }

    @After
    fun cleanup() = unmockkAll()

    @Test
    fun responseFromPreviousConnectionCannotEnterNewScopeOrCache() =
        runTest {
            val response = CompletableDeferred<TodayPlan>()
            coEvery { api.todayPlan("default") } coAnswers { response.await() }
            coEvery { api.todaySettings("default") } returns TodaySettings()
            val state = TodayPlanState(scope, repository)
            val job = launch { state.load() }
            runCurrent()
            scope = "second"
            response.complete(TodayPlan(day = "old"))
            job.join()
            assertNull(state.plan)
            coVerify(exactly = 0) { dao.cacheToday(any(), any()) }
        }

    @Test
    fun failedRefreshKeepsCachedPlanAndRetainsIdForAmbiguousRetry() =
        runTest {
            val cached = TodayPlan(plan_day = "2026-10-23", checked_at = "2026-10-23T09:00:00+09:00")
            coEvery { dao.provider("first", "today_home") } returns
                HomeProviderRow("first", "today_home", metadata = Json.encodeToString(cached))
            val ids = mutableListOf<TodayRefresh>()
            coEvery { api.refreshToday("default", capture(ids)) } throws IOException("synthetic")
            val state = TodayPlanState(scope, repository)
            state.restore()
            state.refresh()
            state.refresh()
            assertEquals(cached, state.plan)
            assertNotNull(state.error)
            assertEquals(ids[0].request_id, ids[1].request_id)
        }

    @Test
    fun accountChangeCannotRetainOldAccountsPlanWhenReloadFails() =
        runTest {
            coEvery { dao.provider("first", "today_home") } returns
                HomeProviderRow("first", "today_home", metadata = Json.encodeToString(TodayPlan(plan_day = "old")))
            val settings = TodaySettings(email = "other@example.org")
            coEvery { api.saveTodaySettings("default", settings) } returns settings
            coEvery { api.todayPlan("default") } throws IOException("synthetic")
            val state = TodayPlanState(scope, repository)
            state.restore()
            state.save(settings)
            assertEquals(emptyList<TodayTask>(), state.plan?.tasks)
            assertEquals("", state.plan?.plan_day)
            assertEquals(settings, state.settings)
        }
}
