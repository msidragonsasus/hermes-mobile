package com.m57.hermescontrol.home

import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class TodayPlanStateTest {
    private var scope = "first"
    private val epochs = MutableStateFlow(0L)
    private val api = mockk<HermesApiService>()
    private val dao = mockk<HomeDao>(relaxed = true)
    private val repository = mockk<HomeRepository>()

    @Before
    fun setup() {
        mockkObject(AuthManager, ApiClient)
        mockkObject(AppScope)
        every { AppScope.connectionKey() } answers { scope }
        every { AuthManager.activeProfileId } returns MutableStateFlow("specialist")
        every { AuthManager.isGatedMode() } returns true
        every { AuthManager.connectionEpoch } returns epochs
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
            assertEquals(HomeFailure.Offline, state.failure)
            assertEquals(ids[0].request_id, ids[1].request_id)
        }

    private val open =
        TodayTask("one", "課題を提出する", "r", "manaba", "s", "https://example.org", 1, actionability = "actionable")
    private val other = open.copy(id = "two", title = "別の課題")

    private fun planWith(vararg tasks: TodayTask) = TodayPlan(plan_day = "2026-10-28", tasks = tasks.toList())

    private suspend fun restored(plan: TodayPlan): TodayPlanState {
        coEvery { dao.provider("first", "today_home") } returns
            HomeProviderRow("first", "today_home", metadata = Json.encodeToString(plan))
        return TodayPlanState(scope, repository).also { it.restore() }
    }

    @Test
    fun completionShowsRowPendingThenAcceptsCanonicalPlan() =
        runTest {
            val state = restored(planWith(open, other))
            val response = CompletableDeferred<TodayPlan>()
            val body = slot<TodayCompletion>()
            coEvery { api.completeTodayTask("default", "one", capture(body)) } coAnswers { response.await() }
            val job = launch { state.setDone(open, true) }
            runCurrent()
            assertEquals(mapOf("one" to TodayChange.Done(true)), state.pending.toMap())
            // Pending is scoped: the list is not globally busy and the plan still says not done.
            assertFalse(state.busy)
            assertFalse(
                state.plan!!
                    .tasks
                    .first()
                    .done,
            )
            response.complete(planWith(open.copy(done = true), other))
            job.join()
            assertEquals(TodayCompletion(true), body.captured)
            assertTrue(
                state.plan!!
                    .tasks
                    .first()
                    .done,
            )
            assertTrue(state.pending.isEmpty())
            assertEquals(TodayRecent("one", TodayChange.Done(true), wasActionable = true), state.recent)
            coVerify(exactly = 1) { dao.cacheToday("first", any()) }
        }

    @Test
    fun failedCompletionKeepsTaskOpenAndReportsOnlyOnThatRow() =
        runTest {
            val cached = planWith(open, other)
            val state = restored(cached)
            coEvery { api.completeTodayTask("default", "one", any()) } throws IOException("synthetic")
            state.setDone(open, true)
            assertEquals(cached, state.plan)
            assertTrue(state.pending.isEmpty())
            val failure = state.failures["one"]!!
            assertEquals(TodayChange.Done(true), failure.change)
            assertFalse(failure.unavailable)
            assertNull(state.failures["two"])
            assertNull(state.failure)
            assertNull(state.recent)
            coVerify(exactly = 0) { dao.cacheToday(any(), any()) }
        }

    @Test
    fun repeatedTapWhilePendingSendsOneRequest() =
        runTest {
            val state = restored(planWith(open))
            val response = CompletableDeferred<TodayPlan>()
            coEvery { api.completeTodayTask("default", "one", any()) } coAnswers { response.await() }
            val first = launch { state.setDone(open, true) }
            runCurrent()
            state.setDone(open, true)
            // A different action on the same row also waits for the first to settle.
            state.setInterest(open, false)
            response.complete(planWith(open.copy(done = true)))
            first.join()
            coVerify(exactly = 1) { api.completeTodayTask(any(), any(), any()) }
            coVerify(exactly = 0) { api.setTodayInterest(any(), any(), any()) }
        }

    @Test
    fun completionDuringPollIsAcceptedImmediatelyAndAppliedAfterThePoll() =
        runTest {
            val state = restored(planWith(open))
            val poll = CompletableDeferred<TodayPlan>()
            coEvery { api.todayPlan("default") } coAnswers { poll.await() }
            coEvery { api.todaySettings("default") } returns TodaySettings()
            coEvery { api.completeTodayTask("default", "one", any()) } returns planWith(open.copy(done = true))
            val loading = launch { state.load() }
            runCurrent()
            val completing = launch { state.setDone(open, true) }
            runCurrent()
            assertEquals(mapOf("one" to TodayChange.Done(true)), state.pending.toMap())
            assertTrue(state.loading)
            assertFalse(state.busy)
            poll.complete(planWith(open))
            loading.join()
            completing.join()
            // The poll that started earlier cannot overwrite the later completion.
            assertTrue(
                state.plan!!
                    .tasks
                    .single()
                    .done,
            )
        }

    @Test
    fun restoringCompletionSendsExplicitFalse() =
        runTest {
            val done = open.copy(done = true)
            val state = restored(planWith(done))
            val body = slot<TodayCompletion>()
            coEvery { api.completeTodayTask("default", "one", capture(body)) } returns planWith(open)
            state.setDone(done, false)
            assertEquals(TodayCompletion(false), body.captured)
            assertFalse(
                state.plan!!
                    .tasks
                    .single()
                    .done,
            )
            assertNull(state.recent)
        }

    @Test
    fun completionResponseFromPreviousConnectionIsDiscarded() =
        runTest {
            val state = restored(planWith(open))
            val response = CompletableDeferred<TodayPlan>()
            coEvery { api.completeTodayTask("default", "one", any()) } coAnswers { response.await() }
            val job = launch { state.setDone(open, true) }
            runCurrent()
            scope = "second"
            response.complete(planWith(open.copy(done = true)))
            job.join()
            assertFalse(
                state.plan!!
                    .tasks
                    .single()
                    .done,
            )
            coVerify(exactly = 0) { dao.cacheToday(any(), any()) }
        }

    @Test
    fun missingTaskReportsOnRowAndReloadsCanonicalPlan() =
        runTest {
            val state = restored(planWith(open))
            coEvery { api.completeTodayTask("default", "one", any()) } throws
                httpError(404, """{"detail":{"code":"task_missing"}}""")
            coEvery { api.todayPlan("default") } returns planWith(other)
            coEvery { api.todaySettings("default") } returns TodaySettings()
            state.setDone(open, true)
            assertEquals("この項目は最新の一覧にありません。", state.failures["one"]!!.message)
            assertEquals(listOf("two"), state.plan!!.tasks.map { it.id })
        }

    private val candidate =
        open.copy(id = "ad", title = "説明会のご案内", actionability = "review", review_reason = "optional")

    private fun httpError(
        code: Int,
        body: String,
    ) = HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    @Test
    fun dismissPersistsThroughInterestEndpointAndAcceptsServerPlacement() =
        runTest {
            val state = restored(planWith(open).copy(review_tasks = listOf(candidate)))
            val response = CompletableDeferred<TodayPlan>()
            val body = slot<TodayInterest>()
            coEvery { api.setTodayInterest("default", "ad", capture(body)) } coAnswers { response.await() }
            val job = launch { state.setInterest(candidate, false) }
            runCurrent()
            assertEquals(mapOf("ad" to TodayChange.Interest(false)), state.pending.toMap())
            // Nothing moves before the server answers.
            assertEquals(listOf("ad"), state.plan!!.review_tasks.map { it.id })
            val dismissed =
                candidate.copy(actionability = "dismissed", review_reason = "dismissed", user_interest = false)
            response.complete(planWith(open).copy(dismissed_tasks = listOf(dismissed)))
            job.join()
            assertEquals(TodayInterest(false), body.captured)
            assertEquals(listOf("ad"), state.plan!!.dismissed_tasks.map { it.id })
            assertTrue(state.plan!!.review_tasks.isEmpty())
            assertEquals(TodayRecent("ad", TodayChange.Interest(false), wasActionable = false), state.recent)
            coVerify(exactly = 1) { dao.cacheToday("first", any()) }
        }

    @Test
    fun acknowledgedInterestStaysSavedIfOfflineCacheWriteFails() =
        runTest {
            val state = restored(planWith(open).copy(review_tasks = listOf(candidate)))
            val added = candidate.copy(actionability = "actionable", review_reason = null, user_interest = true)
            val canonical = planWith(open, added)
            coEvery { api.setTodayInterest("default", "ad", TodayInterest(true)) } returns canonical
            coEvery { dao.cacheToday("first", any()) } throws IOException("synthetic cache failure") andThen Unit
            coEvery { api.todayPlan("default") } returns canonical
            coEvery { api.todaySettings("default") } returns TodaySettings()

            state.setInterest(candidate, true)

            assertEquals(canonical, state.plan)
            assertEquals(TodayRecent("ad", TodayChange.Interest(true), wasActionable = false), state.recent)
            assertTrue(state.cacheWriteFailed)
            assertTrue(state.failures.isEmpty())
            assertNull(state.failure)
            state.retry(candidate)
            coVerify(exactly = 1) { api.setTodayInterest(any(), any(), any()) }

            state.load(allowAutomatic = false)

            assertFalse(state.cacheWriteFailed)
            assertEquals(canonical, state.plan)
        }

    @Test
    fun olderServerWithoutInterestRouteReportsUnavailableAndChangesNothing() =
        runTest {
            val cached = planWith(open.copy(actionability = ""))
            val state = restored(cached)
            coEvery { api.setTodayInterest("default", "one", any()) } throws
                httpError(404, """{"detail":"Not Found"}""")
            state.setInterest(cached.tasks.single(), false)
            val failure = state.failures["one"]!!
            assertTrue(failure.unavailable)
            assertEquals("このサーバーは「対象外」「やることに追加」の保存に未対応です。サーバーの更新後に使えます。", failure.message)
            assertEquals(cached, state.plan)
            assertNull(state.recent)
            // Not a missing task: the list is not reloaded and nothing is cached.
            coVerify(exactly = 0) { api.todayPlan(any()) }
            coVerify(exactly = 0) { dao.cacheToday(any(), any()) }
        }

    @Test
    fun profileUnavailableLeavesRowChangeRetryable() =
        runTest {
            val cached = planWith(open)
            val state = restored(cached)
            coEvery { api.completeTodayTask("default", open.id, any()) } throws
                httpError(403, """{"detail":{"code":"profile_mismatch"}}""")

            state.setDone(open, true)

            assertEquals(cached, state.plan)
            assertNull(state.failure)
            assertEquals(
                TodayRowFailure(
                    TodayChange.Done(true),
                    "保存できませんでした。接続プロファイルを確認してもう一度お試しください。",
                    unavailable = false,
                ),
                state.failures[open.id],
            )
        }

    @Test
    fun failedInterestIsRetriedWithTheSameAction() =
        runTest {
            val state = restored(planWith(open).copy(review_tasks = listOf(candidate)))
            val bodies = mutableListOf<TodayInterest>()
            val added = candidate.copy(actionability = "actionable", review_reason = null, user_interest = true)
            coEvery { api.setTodayInterest("default", "ad", capture(bodies)) } throws
                IOException("synthetic") andThen planWith(open, added)
            state.setInterest(candidate, true)
            val failure = state.failures["ad"]!!
            assertFalse(failure.unavailable)
            assertEquals(TodayChange.Interest(true), failure.change)
            assertEquals(listOf("ad"), state.plan!!.review_tasks.map { it.id })
            state.retry(candidate)
            assertEquals(listOf(TodayInterest(true), TodayInterest(true)), bodies)
            assertNull(state.failures["ad"])
            assertEquals(listOf("one", "ad"), state.plan!!.tasks.map { it.id })
        }

    @Test
    fun interestResponseFromPreviousConnectionIsDiscarded() =
        runTest {
            val state = restored(planWith(open))
            val response = CompletableDeferred<TodayPlan>()
            coEvery { api.setTodayInterest("default", "one", any()) } coAnswers { response.await() }
            val job = launch { state.setInterest(open, false) }
            runCurrent()
            scope = "second"
            response.complete(planWith().copy(dismissed_tasks = listOf(open.copy(actionability = "dismissed"))))
            job.join()
            assertEquals(listOf("one"), state.plan!!.tasks.map { it.id })
            assertNull(state.failures["one"])
            assertNull(state.recent)
            coVerify(exactly = 0) { dao.cacheToday(any(), any()) }
        }

    @Test
    fun interestOnMissingTaskReloadsInsteadOfClaimingSaved() =
        runTest {
            val state = restored(planWith(open, other))
            coEvery { api.setTodayInterest("default", "one", any()) } throws
                httpError(404, """{"detail":{"code":"task_missing"}}""")
            coEvery { api.todayPlan("default") } returns planWith(other)
            coEvery { api.todaySettings("default") } returns TodaySettings()
            state.setInterest(open, false)
            assertFalse(state.failures["one"]!!.unavailable)
            assertEquals(listOf("two"), state.plan!!.tasks.map { it.id })
            assertNull(state.recent)
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
            assertEquals(TodaySaveResult.Ok, state.save(settings))
            assertEquals(HomeFailure.Offline, state.failure)
            assertEquals(emptyList<TodayTask>(), state.plan?.tasks)
            assertEquals("", state.plan?.plan_day)
            assertEquals(settings, state.settings)
        }

    @Test
    fun todaysNonFreshPlanDoesNotStartAutomaticSynthesis() =
        runTest {
            val day = LocalDate.now(ZoneId.of("Asia/Tokyo")).toString()
            coEvery { api.todayPlan("default") } returns TodayPlan(plan_day = day, fresh = false)
            coEvery { api.todaySettings("default") } returns TodaySettings(email = "set@example.org", enabled = true)
            val state = TodayPlanState(scope, repository)
            state.load()
            coVerify(exactly = 0) { api.refreshToday(any(), any()) }
        }

    @Test
    fun stalePlanAttemptsOnlyOneAutomaticPostPerDayEvenAfterTransportFailure() =
        runTest {
            val day = LocalDate.now(ZoneId.of("Asia/Tokyo")).toString()
            coEvery { api.todayPlan("default") } returns TodayPlan(plan_day = "2025-01-01")
            coEvery { api.todaySettings("default") } returns TodaySettings(email = "set@example.org", enabled = true)
            coEvery { api.refreshToday("default", TodayRefresh("auto:$day", automatic = true)) } throws
                IOException("synthetic")
            val state = TodayPlanState(scope, repository)
            repeat(3) { state.load() }
            coVerify(exactly = 1) { api.refreshToday("default", TodayRefresh("auto:$day", automatic = true)) }
            coVerify(exactly = 1) { api.refreshToday(any(), any()) }
        }

    @Test
    fun userReloadReadsStalePlanWithoutStartingSynthesis() =
        runTest {
            coEvery { api.todayPlan("default") } returns TodayPlan(plan_day = "2025-01-01")
            coEvery { api.todaySettings("default") } returns TodaySettings(email = "set@example.org", enabled = true)
            val state = TodayPlanState(scope, repository)
            state.load(userInitiated = true)
            coVerify(exactly = 1) { api.todayPlan("default") }
            coVerify(exactly = 0) { api.refreshToday(any(), any()) }
        }

    @Test
    fun nonGatedConnectionDoesNotAccessApi() =
        runTest {
            every { AuthManager.isGatedMode() } returns false
            val state = TodayPlanState(scope, repository)
            state.load()
            state.refresh()
            assertEquals(TodaySaveResult.Failed(HomeFailure.NotGated), state.save(TodaySettings()))
            assertEquals(HomeFailure.NotGated, state.failure)
            coVerify { api wasNot Called }
        }

    @Test
    fun oddEpochDoesNotAccessApi() =
        runTest {
            epochs.value = 1L
            val state = TodayPlanState(scope, repository)
            state.load()
            coVerify { api wasNot Called }
        }

    @Test
    fun classifiesServerErrorsFromBufferedBody() {
        val cases =
            listOf(
                Triple(409, "setup_required", HomeFailure.SetupRequired),
                Triple(409, "refresh_running", HomeFailure.RunInProgress),
                Triple(404, "profile_missing", HomeFailure.ProfileUnavailable),
                Triple(403, "profile_mismatch", HomeFailure.ProfileUnavailable),
                Triple(403, "dashboard_session_required", HomeFailure.AuthRequired),
                Triple(403, "cross_origin_write", HomeFailure.Generic),
                Triple(503, "standard_auth_unavailable", HomeFailure.ServerOutdated("standard_auth_unavailable")),
                Triple(503, "storage_unavailable", HomeFailure.ServerUnavailable("storage_unavailable")),
                Triple(503, "gateway_unavailable", HomeFailure.ServerUnavailable("gateway_unavailable")),
                Triple(502, "bad_gateway", HomeFailure.ServerUnavailable("bad_gateway")),
                Triple(504, "gateway_timeout", HomeFailure.ServerUnavailable("gateway_timeout")),
                Triple(422, "invalid_settings", HomeFailure.InvalidInput("invalid_settings")),
                Triple(422, "source_required", HomeFailure.InvalidInput("source_required")),
            )
        for ((status, code, failure) in cases) {
            assertEquals(failure, homeFailureOf(httpError(status, """{"detail":{"code":"$code"}}""")))
        }
        assertEquals(HomeFailure.ServerOutdated(null), homeFailureOf(httpError(404, "<html>not found</html>")))
    }

    @Test
    fun swapsTheApiClientBetweenReadsWithoutReplacingState() =
        runTest {
            val second = mockk<HermesApiService>()
            coEvery { api.todayPlan("default") } returns TodayPlan(day = "first")
            coEvery { api.todaySettings("default") } returns TodaySettings()
            coEvery { second.todayPlan("default") } returns TodayPlan(day = "second")
            coEvery { second.todaySettings("default") } returns TodaySettings()
            val state = TodayPlanState(scope, repository)
            state.load()
            assertEquals("first", state.plan?.day)
            every { ApiClient.hermesApi } returns second
            state.load()
            assertEquals("second", state.plan?.day)
            coVerify(exactly = 1) { api.todayPlan("default") }
            coVerify(exactly = 1) { second.todayPlan("default") }
        }

    @Test
    fun runInProgressReloadsThePlanWithoutShowingFailure() =
        runTest {
            val running = TodayPlan(state = "running")
            coEvery { api.refreshToday("default", any()) } throws
                httpError(409, """{"detail":{"code":"refresh_running"}}""")
            coEvery { api.todayPlan("default") } returns running
            val state = TodayPlanState(scope, repository)
            state.refresh()
            assertEquals(running, state.plan)
            assertNull(state.failure)
        }

    @Test
    fun setupRequiredReloadsSettingsAndReflectsDisabledState() =
        runTest {
            val settings = TodaySettings()
            coEvery { api.refreshToday("default", any()) } throws
                httpError(409, """{"detail":{"code":"setup_required"}}""")
            coEvery { api.todaySettings("default") } returns settings
            val state = TodayPlanState(scope, repository)
            state.refresh()
            assertEquals(settings, state.settings)
            assertNull(state.failure)
        }

    @Test
    fun failedSettingsSaveLeavesFeedAndCacheUntouched() =
        runTest {
            val value = TodaySettings(enabled = true)
            val cached = planWith(open)
            val state = restored(cached)
            val invalidSettings = httpError(422, """{"detail":{"code":"invalid_settings"}}""")
            val sourceRequired = httpError(422, """{"detail":{"code":"source_required"}}""")
            val cases =
                listOf(
                    invalidSettings to TodaySaveResult.InvalidInput("invalid_settings"),
                    sourceRequired to TodaySaveResult.InvalidInput("source_required"),
                    httpError(409, """{"detail":{"code":"refresh_running"}}""") to TodaySaveResult.RunInProgress,
                    IOException("synthetic") to TodaySaveResult.Failed(HomeFailure.Offline),
                )

            for ((error, expected) in cases) {
                coEvery { api.saveTodaySettings("default", value) } throws error
                assertEquals(expected, state.save(value))
                assertNull(state.failure)
                assertEquals(cached, state.plan)
            }
            coVerify(exactly = 0) { dao.cacheToday(any(), any()) }
        }

    @Test
    fun automaticRefreshFailureIsPublishedAfterSettingsSaveCommits() =
        runTest {
            val value = TodaySettings(enabled = true)
            val refreshed = planWith(other)
            val state = restored(planWith(open))
            coEvery { api.saveTodaySettings("default", value) } returns value
            coEvery { api.todayPlan("default") } returns refreshed
            coEvery { api.refreshToday("default", any()) } throws IOException("synthetic")

            assertEquals(TodaySaveResult.Ok, state.save(value))
            assertEquals(HomeFailure.Offline, state.failure)
            assertEquals(refreshed, state.plan)
            assertEquals(value, state.settings)
        }
}
