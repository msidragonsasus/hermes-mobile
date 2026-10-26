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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CareerCompanyInitializationTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<CareerCompanyRepository>()
    private val local = mockk<CareerLocalStore>()
    private val saved = CareerCompanies(companies = listOf(CareerCompanyRow("company", "保存した企業")))
    private val detail =
        CareerCompanyDetail(
            company = CareerEntity("companies", "company", 1, buildJsonObject { put("name", "保存した企業") }),
            thread_id = "company:company",
            mail_next_cursor = "next-page",
        )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { local.enabled() } returns true
        coEvery { local.companyCache(null) } returns Json.encodeToString(saved)
        coEvery { local.companyCache("company") } returns Json.encodeToString(detail)
        coEvery { local.notificationsEnabled() } returns false
        coEvery { local.companyLoginAccounts(any()) } returns emptyList()
        coEvery { repository.companies() } throws IOException("offline")
        coEvery { repository.company(any()) } throws IOException("offline")
        coEvery { repository.connections() } throws IOException("offline")
    }

    @After
    fun teardown() = Dispatchers.resetMain()

    @Test
    fun companyCacheReadFailurePreservesKnownEnabledState() =
        runTest(dispatcher) {
            coEvery { local.companyCache(null) } throws IOException("unreadable cache")
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            assertTrue(model.state.value.enabled)
            assertFalse(model.state.value.initialized)
            assertNotNull(model.state.value.initializationError)
            assertNoWrites()
            coVerify(exactly = 0) { repository.companies() }
        }

    @Test
    fun unreadableEnabledFlagDoesNotBecomeCompletedDisabledOnboarding() =
        runTest(dispatcher) {
            coEvery { local.enabled() } throws IOException("unreadable settings")
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            assertFalse(model.state.value.initialized)
            assertNotNull(model.state.value.initializationError)
            assertNoWrites()
            coVerify(exactly = 0) { local.setEnabled(any()) }
        }

    @Test
    fun notificationReadFailureKeepsAlreadyRestoredCompanyCache() =
        runTest(dispatcher) {
            coEvery { local.notificationsEnabled() } throws IOException("unreadable notifications")
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            assertTrue(model.state.value.enabled)
            assertEquals(saved, model.state.value.companies)
            assertFalse(model.state.value.initialized)
            assertNotNull(model.state.value.initializationError)
            assertNoWrites()
        }

    @Test
    fun accountReadFailureKeepsAlreadyRestoredCompanyDetail() =
        runTest(dispatcher) {
            coEvery { local.companyLoginAccounts("company") } throws IOException("unreadable accounts")
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            assertTrue(model.state.value.enabled)
            assertEquals(detail, model.state.value.detail)
            assertFalse(model.state.value.initialized)
            assertNotNull(model.state.value.initializationError)
            assertNoWrites()
        }

    @Test
    fun malformedListCacheIsRecoverableWithoutEnablingOrReplacingLocalData() =
        runTest(dispatcher) {
            coEvery { local.companyCache(null) } returns "{malformed"
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            assertTrue(model.state.value.enabled)
            assertFalse(model.state.value.initialized)
            assertNotNull(model.state.value.initializationError)
            assertNoWrites()
            coVerify(exactly = 0) { repository.companies() }
        }

    @Test
    fun malformedDetailCacheDoesNotBecomeDisabledOnboarding() =
        runTest(dispatcher) {
            coEvery { local.companyCache("company") } returns "{malformed"
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            assertTrue(model.state.value.enabled)
            assertFalse(model.state.value.initialized)
            assertNotNull(model.state.value.initializationError)
            assertNoWrites()
            coVerify(exactly = 0) { repository.company(any()) }
        }

    @Test
    fun retryRestoresExistingCacheAndKeepsOfflineFailureSeparate() =
        runTest(dispatcher) {
            coEvery { local.companyCache(null) } throws IOException("unreadable cache")
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            coEvery { local.companyCache(null) } returns Json.encodeToString(saved)
            model.retryInitialization()
            advanceUntilIdle()
            assertTrue(model.state.value.initialized)
            assertTrue(model.state.value.enabled)
            assertNull(model.state.value.initializationError)
            assertEquals(saved, model.state.value.companies)
            assertTrue(model.state.value.stale)
            assertNotNull(model.state.value.error)
            assertNotNull(model.state.value.connectionError)
            assertNoWrites()
            coVerify(exactly = 2) { local.enabled() }
            coVerify(exactly = 1) { repository.companies() }
        }

    @Test
    fun failedRetryKeepsPreviouslyDecodedCacheAndKnownEnabledFlag() =
        runTest(dispatcher) {
            coEvery { local.notificationsEnabled() } throws IOException("unreadable notifications")
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            assertEquals(saved, model.state.value.companies)
            coEvery { local.companyCache(null) } returns "{malformed"
            model.retryInitialization()
            advanceUntilIdle()
            assertEquals(saved, model.state.value.companies)
            assertTrue(model.state.value.enabled)
            assertFalse(model.state.value.initialized)
            assertNotNull(model.state.value.initializationError)
            assertNoWrites()
        }

    @Test
    fun duplicateRetriesShareOneRestoreAndDoNotEnableOrStartWork() =
        runTest(dispatcher) {
            coEvery { local.companyLoginAccounts("company") } throws IOException("unreadable accounts")
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            coEvery { local.enabled() } coAnswers {
                gate.await()
                true
            }
            coEvery { local.companyLoginAccounts("company") } returns emptyList()
            model.retryInitialization()
            model.retryInitialization()
            runCurrent()
            assertNull(model.state.value.initializationError)
            assertFalse(model.state.value.initialized)
            assertTrue(model.state.value.enabled)
            assertEquals(detail, model.state.value.detail)
            model.enable()
            model.collectMyPage()
            model.refresh()
            runCurrent()
            assertNoWrites()
            coVerify(exactly = 0) { repository.company(any()) }
            coVerify(exactly = 2) { local.enabled() }
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(model.state.value.initialized)
            assertEquals(detail, model.state.value.detail)
            assertNull(model.state.value.initializationError)
            model.retryInitialization()
            advanceUntilIdle()
            coVerify(exactly = 2) { local.enabled() }
            assertNoWrites()
        }

    @Test
    fun genuineDisabledScopeCompletesWithoutFetchingOrChangingSettings() =
        runTest(dispatcher) {
            coEvery { local.enabled() } returns false
            coEvery { local.companyCache(null) } returns null
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            assertTrue(model.state.value.initialized)
            assertFalse(model.state.value.enabled)
            assertNull(model.state.value.initializationError)
            assertNull(model.state.value.companies)
            assertNoWrites()
            coVerify(exactly = 0) { repository.companies() }
            coVerify(exactly = 0) { repository.connections() }
        }

    @Test
    fun retryCanConfirmGenuinelyDisabledScopeWithoutEnablingIt() =
        runTest(dispatcher) {
            coEvery { local.enabled() } throws IOException("unreadable settings")
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            coEvery { local.enabled() } returns false
            coEvery { local.companyCache(null) } returns null
            model.retryInitialization()
            advanceUntilIdle()
            assertTrue(model.state.value.initialized)
            assertFalse(model.state.value.enabled)
            assertNull(model.state.value.initializationError)
            assertNoWrites()
            coVerify(exactly = 0) { repository.companies() }
            coVerify(exactly = 0) { repository.connections() }
        }

    @Test
    fun ordinaryOfflineReadRetainsRestoredCompanies() =
        runTest(dispatcher) {
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            assertTrue(model.state.value.initialized)
            assertTrue(model.state.value.enabled)
            assertNull(model.state.value.initializationError)
            assertEquals(saved, model.state.value.companies)
            assertTrue(model.state.value.stale)
            assertNotNull(model.state.value.error)
            assertNoWrites()
        }

    @Test
    fun failedListRestoreBlocksEnabledSettingCacheWritesAndMutations() =
        runTest(dispatcher) {
            coEvery { local.notificationsEnabled() } throws IOException("unreadable settings")
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            model.enable()
            model.addCompany("追加", "operation")
            model.saveCompanyOrder(listOf("company"), 1, "order")
            model.collect()
            model.collectMail()
            model.configure(true, "account")
            model.notifications(true) { error("must not schedule notifications") }
            model.refresh()
            model.refreshAfterMailSaved()
            advanceUntilIdle()
            assertNoWrites()
            assertNotNull(model.state.value.initializationError)
            coVerify(exactly = 0) { repository.companies() }
            coVerify(exactly = 0) { repository.connections() }
        }

    @Test
    fun failedDetailRestoreBlocksManagementAndCollectionWithCachePresent() =
        runTest(dispatcher) {
            coEvery { local.companyLoginAccounts("company") } throws IOException("unreadable accounts")
            val model = CareerCompanyViewModel(repository, local, "company")
            advanceUntilIdle()
            assertEquals(detail, model.state.value.detail)
            model.deleteCompany(1, "delete")
            model.preferences("high", "preparing", 1)
            model.setMailSender("recruiting@example.test", true)
            model.collectMyPage()
            model.collectMail()
            model.loadMoreMail()
            model.refreshAfterMailSaved()
            advanceUntilIdle()
            assertNoWrites()
            assertEquals(detail, model.state.value.detail)
            coVerify(exactly = 0) { repository.company(any()) }
            coVerify(exactly = 0) { repository.mailPage(any(), any()) }
        }

    private fun assertNoWrites() {
        coVerify(exactly = 0) { local.setEnabled(any()) }
        coVerify(exactly = 0) { local.setNotificationsEnabled(any()) }
        coVerify(exactly = 0) { local.saveCompanyCache(any(), any()) }
        coVerify(exactly = 0) { local.invalidateCompanyCache(any()) }
        coVerify(exactly = 0) { repository.mutate(any(), any()) }
        coVerify(exactly = 0) { repository.saveCompany(any(), any()) }
        coVerify(exactly = 0) { repository.saveCompanyOrder(any(), any(), any()) }
    }
}
