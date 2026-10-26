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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CareerCompanyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<CareerCompanyRepository>()
    private val local = mockk<CareerLocalStore>()
    private val saved = CareerCompanies(companies = listOf(CareerCompanyRow("company", "保存した企業")))

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { local.enabled() } returns true
        coEvery { local.notificationsEnabled() } returns false
        coEvery { local.companyCache(null) } returns Json.encodeToString(saved)
        coEvery { local.saveCompanyCache(any(), any()) } returns Unit
        coEvery { local.esCache(any()) } returns null
        coEvery { local.importedLoginAccounts() } returns emptyList()
        coEvery { local.companyLoginAccounts(any()) } returns emptyList()
        coEvery { repository.companies() } returns saved
        coEvery { repository.connections() } returns CareerConnections()
        coEvery { repository.mutate(any(), any()) } returns JsonObject(emptyMap())
    }

    @After
    fun teardown() = Dispatchers.resetMain()

    @Test
    fun companyAddRetainsOperationOnRetryAndDoesNotDuplicateRapidTaps() =
        runTest(dispatcher) {
            coEvery { repository.saveCompany(any(), any()) } throws IOException()
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            model.addCompany("  新しい企業  ", "stable-operation")
            model.addCompany("  新しい企業  ", "stable-operation")
            advanceUntilIdle()
            assertEquals(CompanyWritePhase.UNKNOWN, model.state.value.companyWrite.phase)
            coVerify(exactly = 1) { repository.saveCompany("save", any()) }
            coEvery { repository.saveCompany(any(), any()) } returns JsonObject(emptyMap())
            model.addCompany("  新しい企業  ", "stable-operation")
            advanceUntilIdle()
            coVerify(exactly = 2) {
                repository.saveCompany(
                    "save",
                    match {
                        it.text("operation_id") == "stable-operation" && it.text("id") == "company:stable-operation" &&
                            (it["data"] as JsonObject).text("name") == "新しい企業"
                    },
                )
            }
            assertEquals(CompanyWritePhase.SAVED, model.state.value.companyWrite.phase)
        }

    @Test
    fun addedCompanyAcknowledgementSurvivesListReadFailure() =
        runTest(dispatcher) {
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            coEvery { repository.saveCompany(any(), any()) } returns JsonObject(emptyMap())
            coEvery { repository.companies() } throws IOException()
            model.addCompany("追加", "add-operation")
            advanceUntilIdle()
            assertEquals(CompanyWritePhase.SAVED, model.state.value.companyWrite.phase)
            assertTrue(model.state.value.stale)
        }

    @Test
    fun companyDeleteConflictKeepsDetailAndCache() =
        runTest(dispatcher) {
            prepareCompany()
            coEvery { repository.saveCompany(any(), any()) } throws
                HttpException(
                    Response.error<JsonObject>(409, "{}".toResponseBody()),
                )
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            model.deleteCompany(1, "delete-operation")
            advanceUntilIdle()
            assertFalse(model.state.value.companyDeleted)
            assertNotNull(model.state.value.detail)
            assertEquals(CompanyWritePhase.CONFLICT, model.state.value.companyWrite.phase)
            coVerify(exactly = 0) { local.invalidateCompanyCache(any()) }
        }

    @Test
    fun companyDeleteOnlyInvalidatesCacheAfterAcknowledgement() =
        runTest(dispatcher) {
            prepareCompany()
            coEvery { repository.saveCompany(any(), any()) } returns JsonObject(emptyMap())
            coEvery { local.invalidateCompanyCache(any()) } returns Unit
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            model.deleteCompany(1, "delete-operation")
            advanceUntilIdle()
            assertTrue(model.state.value.companyDeleted)
            coVerify(exactly = 1) { local.invalidateCompanyCache("company") }
            coVerify(exactly = 1) {
                repository.saveCompany(
                    "delete-company",
                    match {
                        it.text("id") == "company" && it.text("expected_version") == "1" &&
                            it.text("confirmed_by_user") == "true"
                    },
                )
            }
        }

    @Test
    fun mailSaveRefreshWaitsForAnInFlightRead() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            coEvery { repository.companies() } coAnswers {
                gate.await()
                saved
            }
            val model = CareerCompanyViewModel(repository, local, null)
            runCurrent()
            model.refreshAfterMailSaved()
            runCurrent()
            coVerify(exactly = 1) { repository.companies() }
            gate.complete(Unit)
            advanceUntilIdle()
            coVerify(exactly = 2) { repository.companies() }
            assertFalse(model.state.value.loading)
        }

    @Test
    fun disabledAppDoesNotFetchPrivateData() =
        runTest(dispatcher) {
            coEvery { local.enabled() } returns false
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            assertFalse(model.state.value.enabled)
            coVerify(exactly = 0) { repository.companies() }
            coVerify(exactly = 0) { repository.connections() }
        }

    @Test
    fun connectionMetadataFailureDoesNotInvalidateSuccessfulDataRead() =
        runTest(dispatcher) {
            coEvery { repository.connections() } throws IOException()
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            assertEquals(saved, model.state.value.companies)
            assertFalse(model.state.value.stale)
            assertNotNull(model.state.value.connectionError)
        }

    @Test
    fun cacheWriteFailureStillPublishesFreshServerData() =
        runTest(dispatcher) {
            val fresh = CareerCompanies(companies = listOf(CareerCompanyRow("fresh", "新しい企業")))
            coEvery { repository.companies() } returns fresh
            coEvery { local.saveCompanyCache(any(), any()) } throws IOException()
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            assertEquals(fresh, model.state.value.companies)
            assertFalse(model.state.value.stale)
            assertNotNull(model.state.value.message)
        }

    @Test
    fun connectionFailureKeepsLastCompanyCache() =
        runTest(dispatcher) {
            coEvery { repository.companies() } throws IOException()
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            assertEquals(saved, model.state.value.companies)
            assertTrue(model.state.value.stale)
            assertNotNull(model.state.value.error)
            coVerify(exactly = 0) { local.saveCompanyCache(any(), any()) }
        }

    @Test
    fun oldServerCannotSilentlyEnableBothCollectionChannels() =
        runTest(dispatcher) {
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            model.configure(true, "gmail-account")
            advanceUntilIdle()
            assertTrue(
                model.state.value.error
                    .orEmpty()
                    .contains("サーバーの更新"),
            )
            coVerify(exactly = 0) { repository.mutate("settings", any()) }
        }

    @Test
    fun listCollectionOnlyRequestsMail() =
        runTest(dispatcher) {
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            model.collect()
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.mutate("check", match { it.text("channel") == "mail" }) }
        }

    @Test
    fun companyDetailCollectionAlsoRequestsOnlyMail() =
        runTest(dispatcher) {
            coEvery { local.companyCache("company") } returns null
            coEvery { repository.company("company") } returns
                CareerCompanyDetail(
                    company = CareerEntity("companies", "company", 1, buildJsonObject { put("name", "企業") }),
                    thread_id = "company:company",
                )
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            model.collect()
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.mutate("check", match { it.text("channel") == "mail" }) }
            coVerify(exactly = 0) { repository.mutate("portal-login", any()) }
        }

    @Test
    fun notificationSchedulingRequiresPersistedPreference() =
        runTest(dispatcher) {
            coEvery { local.setNotificationsEnabled(true) } throws IOException()
            var scheduled = false
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            model.notifications(true) { scheduled = true }
            advanceUntilIdle()
            assertFalse(scheduled)
            assertFalse(model.state.value.notifications)
        }

    private fun companyDetail(
        version: Int = 1,
        priority: String = "unknown",
    ) = CareerCompanyDetail(
        company =
            CareerEntity(
                "companies",
                "company",
                version,
                buildJsonObject {
                    put("name", "企業")
                    put("user_priority", priority)
                    put("user_stage", "unknown")
                },
            ),
        thread_id = "company:company",
    )

    private suspend fun prepareCompany() {
        coEvery { local.companyCache("company") } returns null
        coEvery { repository.company("company") } returns companyDetail()
    }

    @Test
    fun mypageQueuedRunReleasesClientAndRefreshShowsCompletionReason() =
        runTest(dispatcher) {
            prepareCompany()
            val ready = companyDetail().copy(mypage = CareerMyPage(supported = true, connection_version = 1))
            val running =
                ready.copy(
                    mypage =
                        ready.mypage?.copy(
                            status =
                                buildJsonObject {
                                    put("state", "running")
                                    put("run_id", "run-1")
                                },
                        ),
                )
            val failed =
                ready.copy(
                    mypage =
                        ready.mypage?.copy(
                            status =
                                buildJsonObject {
                                    put("state", "auth_required")
                                    put("reason", "authentication_challenge")
                                    put("run_id", "run-1")
                                },
                        ),
                )
            coEvery { repository.company("company") } returnsMany listOf(ready, running, failed)
            coEvery { repository.mutate("mypage-check", any()) } returns
                buildJsonObject {
                    put("state", "queued")
                    put("run_id", "run-1")
                }
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            model.collectMyPage()
            model.collectMyPage()
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.mutate("mypage-check", any()) }
            assertFalse(model.state.value.loading)
            assertEquals(
                "running",
                model.state.value.detail
                    ?.mypage
                    ?.status
                    ?.text("state"),
            )
            coVerify(exactly = 2) { repository.company("company") }
            model.refresh()
            advanceUntilIdle()
            assertTrue(
                companyCollectionReason(
                    model.state.value.detail
                        ?.mypage
                        ?.status
                        ?.text("reason"),
                ).orEmpty()
                    .contains("追加認証"),
            )
            assertEquals(
                "auth_required",
                model.state.value.detail
                    ?.mypage
                    ?.status
                    ?.text("state"),
            )
        }

    @Test
    fun mypageStateLabelsDistinguishSetupPartialAndAuthentication() {
        assertEquals("確認待ち", companyCollectionState("queued"))
        assertEquals("接続確認前", companyCollectionState("configured"))
        assertTrue(companyCollectionReason("mypage_bot_not_ready").orEmpty().contains("Bot"))
        assertTrue(companyCollectionReason("collection_incomplete").orEmpty().contains("確認できていない"))
    }

    @Test
    fun preferencesUseEditingVersionAndRejectDuplicateTaps() =
        runTest(dispatcher) {
            prepareCompany()
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            val updated = companyDetail(2, "high")
            coEvery { repository.mutate("company-preferences", any()) } returns
                buildJsonObject {
                    put("company", Json.encodeToJsonElement(CareerEntity.serializer(), updated.company))
                }
            coEvery { repository.company("company") } returns updated
            model.preferences("high", "unknown", 1)
            model.preferences("low", "unknown", 1)
            assertEquals(CompanyWritePhase.SAVING, model.state.value.preferenceWrite.phase)
            advanceUntilIdle()
            coVerify(exactly = 1) {
                repository.mutate(
                    "company-preferences",
                    match {
                        it.text("expected_version") == "1" &&
                            it.text("priority") == "high"
                    },
                )
            }
            assertEquals(
                updated.company,
                model.state.value.detail
                    ?.company,
            )
            assertEquals(CompanyWritePhase.SAVED, model.state.value.preferenceWrite.phase)
        }

    @Test
    fun acknowledgedSaveSurvivesFollowUpReadFailure() =
        runTest(dispatcher) {
            prepareCompany()
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            val updated = companyDetail(2, "high")
            coEvery { repository.mutate("company-preferences", any()) } returns
                buildJsonObject {
                    put("company", Json.encodeToJsonElement(CareerEntity.serializer(), updated.company))
                }
            coEvery { repository.company("company") } throws IOException()
            model.preferences("high", "unknown", 1)
            advanceUntilIdle()
            assertEquals(
                updated.company,
                model.state.value.detail
                    ?.company,
            )
            assertEquals(CompanyWritePhase.SAVED, model.state.value.preferenceWrite.phase)
            assertTrue(
                model.state.value.preferenceWrite.message
                    .orEmpty()
                    .contains("読み直し"),
            )
            coVerify { local.saveCompanyCache("company", match { it.contains("high") }) }
        }

    @Test
    fun conflictRetainsEditingVersionAndLoadsLatestRecord() =
        runTest(dispatcher) {
            prepareCompany()
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            coEvery { repository.mutate("company-preferences", any()) } throws
                HttpException(Response.error<Any>(409, "{}".toResponseBody()))
            coEvery { repository.company("company") } returns companyDetail(2, "low")
            model.preferences("high", "unknown", 1)
            advanceUntilIdle()
            assertEquals(CompanyWritePhase.CONFLICT, model.state.value.preferenceWrite.phase)
            assertEquals(1, model.state.value.preferenceWrite.requestVersion)
            assertEquals(
                "low",
                model.state.value.detail
                    ?.company
                    ?.data
                    ?.text("user_priority"),
            )
        }

    @Test
    fun lostSaveResponseNeverReportsSuccess() =
        runTest(dispatcher) {
            prepareCompany()
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            coEvery { repository.mutate("company-preferences", any()) } throws IOException()
            model.preferences("high", "unknown", 1)
            advanceUntilIdle()
            assertEquals(CompanyWritePhase.UNKNOWN, model.state.value.preferenceWrite.phase)
            assertFalse(model.state.value.loading)
            assertEquals(
                1,
                model.state.value.detail
                    ?.company
                    ?.version,
            )
        }

    @Test
    fun senderStopUsesAcknowledgedRuleAndPreservesMail() =
        runTest(dispatcher) {
            prepareCompany()
            val rule = CareerMailSenderRule("sender", 2, "company", "hr@example.com", false)
            val original = companyDetail().copy(mail_senders = listOf(rule.copy(version = 1, enabled = true)))
            coEvery { repository.company("company") } returns original
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            coEvery { repository.mutate("mail-sender", any()) } returns
                buildJsonObject {
                    put(
                        "sender",
                        Json.encodeToJsonElement(
                            CareerEntity.serializer(),
                            CareerEntity(
                                "company_mail_senders",
                                rule.id,
                                rule.version,
                                buildJsonObject {
                                    put("company_id", rule.company_id)
                                    put("emailAddress", rule.address)
                                    put("enabled", rule.enabled)
                                },
                            ),
                        ),
                    )
                }
            coEvery { repository.company("company") } throws IOException()
            model.setMailSender(rule.address, false)
            advanceUntilIdle()
            assertEquals(
                listOf(rule),
                model.state.value.detail
                    ?.mail_senders,
            )
            assertEquals(
                original.mail,
                model.state.value.detail
                    ?.mail,
            )
            assertEquals(CompanyWritePhase.SAVED, model.state.value.senderWrite.phase)
        }

    @Test
    fun draftKeepsOriginalVersionWhenServerChanges() {
        val draft = CompanyManageDraft()
        draft.select(companyDetail().company, newPriority = "high")
        draft.select(companyDetail(2, "low").company, newStage = "interview")
        assertEquals(1, draft.version)
        assertEquals("high", draft.priority)
        assertTrue(draft.dirty)
        draft.reset()
        draft.select(companyDetail(2, "low").company, newStage = "offer")
        assertEquals(2, draft.version)
    }

    @Test
    fun mailConnectionDoesNotInferAuthenticationExpiryOrDeviceFailure() {
        val state = CareerCompanyState(connections = CareerConnections(mail_status = "authentication_required"))
        assertEquals("サーバーの自動取得にはGoogle認証の確認が必要です。", companyMailConnectionLabel(state))
        assertEquals("サーバーの接続状態を確認できません。", companyMailConnectionLabel(state.copy(connectionError = "error")))
        assertFalse(validCompanySender("name <hr@example.com>"))
        assertFalse(validCompanySender("hr@"))
        assertTrue(validCompanySender(" hr@example.com "))
    }
}
