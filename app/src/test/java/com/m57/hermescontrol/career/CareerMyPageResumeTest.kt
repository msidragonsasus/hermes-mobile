package com.m57.hermescontrol.career

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CareerMyPageResumeTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<CareerCompanyRepository>()
    private val local = mockk<CareerLocalStore>()
    private var latest = detail()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { local.enabled() } returns true
        coEvery { local.notificationsEnabled() } returns false
        coEvery { local.companyCache("company") } returns null
        coEvery { local.saveCompanyCache(any(), any()) } returns Unit
        coEvery { local.companyLoginAccounts(any()) } returns emptyList()
        coEvery { local.esCache(any()) } returns null
        coEvery { repository.company("company") } coAnswers { latest }
        coEvery { repository.connections() } returns CareerConnections()
    }

    @After
    fun teardown() = Dispatchers.resetMain()

    private fun detail(
        runId: String? = "saved-run-1",
        resumable: Boolean = true,
    ) = CareerCompanyDetail(
        company = CareerEntity("companies", "company", 1, buildJsonObject { put("name", "企業") }),
        thread_id = "company:company",
        mypage =
            CareerMyPage(
                supported = true,
                connection_version = 1,
                analysis_resumable = resumable,
                status =
                    buildJsonObject {
                        put("state", "partial")
                        runId?.let { put("run_id", it) }
                    },
            ),
    )

    @Test
    fun unavailableResumeDoesNotSendAMutation() =
        runTest(dispatcher) {
            latest = detail(resumable = false)
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            model.resumeMyPageAnalysis()
            advanceUntilIdle()
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
            assertFalse(model.state.value.loading)
        }

    @Test
    fun runningServerJobCannotBeResumedEvenWhenPendingBodiesExist() =
        runTest(dispatcher) {
            for (state in listOf("queued", "running", "collecting", "analyzing")) {
                val saved = detail()
                latest =
                    saved.copy(
                        mypage =
                            saved.mypage!!.copy(
                                status =
                                    buildJsonObject {
                                        put("run_id", "saved-run-1")
                                        put("state", state)
                                    },
                            ),
                    )
                val model = CareerCompanyViewModel(repository, local, "company")
                advanceUntilIdle()
                model.resumeMyPageAnalysis()
                advanceUntilIdle()
                assertFalse(model.state.value.loading)
            }
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @Test
    fun missingOrBlankRunIdDoesNotSendAMutation() =
        runTest(dispatcher) {
            for (runId in listOf(null, "", "  ")) {
                latest = detail(runId = runId)
                val model = CareerCompanyViewModel(repository, local, "company")
                advanceUntilIdle()
                model.resumeMyPageAnalysis()
                advanceUntilIdle()
                assertFalse(model.state.value.loading)
            }
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @Test
    fun resumeSendsTheSavedRunAndDeduplicatesRapidTaps() =
        runTest(dispatcher) {
            val receipt = CompletableDeferred<JsonObject>()
            val requests = mutableListOf<JsonObject>()
            coEvery { repository.mutate("mypage-resume-analysis", any()) } coAnswers {
                requests += secondArg<JsonObject>()
                receipt.await()
            }
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            model.resumeMyPageAnalysis()
            model.resumeMyPageAnalysis()
            runCurrent()
            assertEquals(1, requests.size)
            assertEquals("saved-run-1", requests.single().text("run_id"))
            assertTrue(
                requests
                    .single()
                    .text("idempotency_key")
                    .orEmpty()
                    .isNotBlank(),
            )
            assertTrue(model.state.value.loading)
            receipt.complete(buildJsonObject { put("run_id", "saved-run-1") })
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.mutate("mypage-resume-analysis", any()) }
            assertFalse(model.state.value.loading)
            assertEquals(null, model.state.value.collecting)
        }

    @Test
    fun lostResponseRetryKeepsTheSameRunAndIdempotencyKey() =
        runTest(dispatcher) {
            val requests = mutableListOf<JsonObject>()
            coEvery { repository.mutate("mypage-resume-analysis", any()) } coAnswers {
                requests += secondArg<JsonObject>()
                if (requests.size == 1) throw IOException()
                buildJsonObject { put("run_id", "saved-run-1") }
            }
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            model.resumeMyPageAnalysis()
            advanceUntilIdle()
            assertNotNull(model.state.value.error)
            assertFalse(model.state.value.loading)
            model.resumeMyPageAnalysis()
            advanceUntilIdle()
            assertEquals(2, requests.size)
            assertEquals(requests[0].text("run_id"), requests[1].text("run_id"))
            assertTrue(requests[0].text("idempotency_key").orEmpty().isNotBlank())
            assertEquals(requests[0].text("idempotency_key"), requests[1].text("idempotency_key"))
            coVerify(exactly = 2) { repository.mutate("mypage-resume-analysis", any()) }
            assertFalse(model.state.value.loading)
        }

    @Test
    fun acknowledgedResumeCreatesANewKeyForTheNextSavedRun() =
        runTest(dispatcher) {
            val requests = mutableListOf<JsonObject>()
            coEvery { repository.mutate("mypage-resume-analysis", any()) } coAnswers {
                val request = secondArg<JsonObject>()
                requests += request
                buildJsonObject { put("run_id", request.text("run_id")) }
            }
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            model.resumeMyPageAnalysis()
            advanceUntilIdle()
            latest = detail(runId = "saved-run-2")
            model.refresh()
            advanceUntilIdle()
            model.resumeMyPageAnalysis()
            advanceUntilIdle()
            assertEquals(2, requests.size)
            assertEquals("saved-run-1", requests[0].text("run_id"))
            assertEquals("saved-run-2", requests[1].text("run_id"))
            assertTrue(requests[1].text("idempotency_key").orEmpty().isNotBlank())
            assertNotEquals(requests[0].text("idempotency_key"), requests[1].text("idempotency_key"))
            coVerify(exactly = 2) { repository.mutate("mypage-resume-analysis", any()) }
        }

    @Test
    fun acceptedResumeSurvivesFailedResultReadAndRetainsSavedNotices() =
        runTest(dispatcher) {
            val savedNotice =
                buildJsonObject {
                    put("id", "saved-notice")
                    put("title", "保存済みの案内")
                }
            latest =
                latest.copy(
                    mypage =
                        latest.mypage!!.copy(
                            notices = listOf(savedNotice),
                            status =
                                buildJsonObject {
                                    put("run_id", "saved-run-1")
                                    put("state", "partial")
                                    put("last_success_at", "2026-10-01T11:00:00+00:00")
                                },
                        ),
                )
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            coEvery { repository.mutate("mypage-resume-analysis", any()) } returns
                buildJsonObject { put("run_id", "accepted-resume-run") }
            coEvery { repository.company("company") } throws IOException()
            model.resumeMyPageAnalysis()
            advanceUntilIdle()
            val state = model.state.value
            assertFalse(state.loading)
            assertEquals(null, state.collecting)
            assertTrue(state.stale)
            assertTrue(state.error.orEmpty().contains("サーバーで受け付けました"))
            assertTrue(state.error.orEmpty().contains("表示を更新できません"))
            assertFalse(state.error.orEmpty().contains("操作結果を確認できませんでした"))
            assertEquals(
                "accepted-resume-run",
                state.detail
                    ?.mypage
                    ?.status
                    ?.text("run_id"),
            )
            assertEquals(
                "queued",
                state.detail
                    ?.mypage
                    ?.status
                    ?.text("state"),
            )
            assertEquals(
                "2026-10-01T11:00:00+00:00",
                state.detail
                    ?.mypage
                    ?.status
                    ?.text("last_success_at"),
            )
            assertEquals(listOf(savedNotice), state.detail?.mypage?.notices)
            assertFalse(state.detail?.mypage?.analysis_resumable == true)
            model.resumeMyPageAnalysis()
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.mutate("mypage-resume-analysis", any()) }
        }
}
