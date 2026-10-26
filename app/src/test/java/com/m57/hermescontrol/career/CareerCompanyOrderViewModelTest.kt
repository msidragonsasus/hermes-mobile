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
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CareerCompanyOrderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<CareerCompanyRepository>()
    private val local = mockk<CareerLocalStore>()
    private val saved =
        CareerCompanies(companies = listOf("a", "b", "c").map { CareerCompanyRow(it, it) }, order_version = 0)
    private val ids = listOf("c", "a", "b")

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { local.enabled() } returns true
        coEvery { local.notificationsEnabled() } returns false
        coEvery { local.companyCache(null) } returns Json.encodeToString(saved)
        coEvery { local.saveCompanyCache(any(), any()) } returns Unit
        coEvery { local.importedLoginAccounts() } returns emptyList()
        coEvery { repository.companies() } returns saved
        coEvery { repository.connections() } returns CareerConnections()
        coEvery { repository.saveCompanyOrder(any(), any(), any()) } returns CareerCompanyOrder(1, ids)
    }

    @After
    fun teardown() = Dispatchers.resetMain()

    @Test
    fun acknowledgedOrderIsCachedEvenWhenListRefreshFails() =
        runTest(dispatcher) {
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            coEvery { repository.companies() } throws IOException()
            model.saveCompanyOrder(ids, 0, "stable-order")
            advanceUntilIdle()
            assertEquals(CompanyWritePhase.SAVED, model.state.value.orderWrite.phase)
            assertEquals(
                ids,
                model.state.value.companies
                    ?.companies
                    ?.map { it.id },
            )
            assertEquals(
                1,
                model.state.value.companies
                    ?.order_version,
            )
            assertTrue(model.state.value.stale)
            coVerify {
                local.saveCompanyCache(
                    null,
                    match { Json.decodeFromString<CareerCompanies>(it).order_version == 1 },
                )
            }
        }

    @Test
    fun unknownRetryKeepsExactRequestAndRejectsChangedPayload() =
        runTest(dispatcher) {
            coEvery { repository.saveCompanyOrder(any(), any(), any()) } throws IOException()
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            model.saveCompanyOrder(ids, 0, "stable-order")
            advanceUntilIdle()
            assertEquals(CompanyWritePhase.UNKNOWN, model.state.value.orderWrite.phase)
            model.saveCompanyOrder(ids.reversed(), 0, "stable-order")
            model.saveCompanyOrder(ids, 0, "new-order")
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.saveCompanyOrder(any(), any(), any()) }
            coEvery { repository.saveCompanyOrder(any(), any(), any()) } returns CareerCompanyOrder(1, ids)
            coEvery { repository.companies() } returns
                saved.copy(companies = orderedCompanies(saved.companies, ids), order_version = 1)
            model.saveCompanyOrder(ids, 0, "stable-order")
            advanceUntilIdle()
            coVerify(exactly = 2) { repository.saveCompanyOrder(ids, 0, "stable-order") }
            assertEquals(CompanyWritePhase.SAVED, model.state.value.orderWrite.phase)
        }

    @Test
    fun savingBlocksRapidDuplicateAndRefresh() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<CareerCompanyOrder>()
            coEvery { repository.saveCompanyOrder(any(), any(), any()) } coAnswers { gate.await() }
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            model.saveCompanyOrder(ids, 0, "stable-order")
            model.saveCompanyOrder(ids, 0, "stable-order")
            runCurrent()
            model.refresh()
            runCurrent()
            coVerify(exactly = 1) { repository.companies() }
            coVerify(exactly = 1) { repository.saveCompanyOrder(any(), any(), any()) }
            gate.complete(CareerCompanyOrder(1, ids))
            advanceUntilIdle()
        }

    @Test
    fun conflictKeepsPreviouslySavedOrderUntilReload() =
        runTest(dispatcher) {
            coEvery { repository.saveCompanyOrder(any(), any(), any()) } throws
                HttpException(Response.error<CareerCompanyOrder>(409, "{}".toResponseBody()))
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            model.saveCompanyOrder(ids, 0, "stable-order")
            advanceUntilIdle()
            assertEquals(CompanyWritePhase.CONFLICT, model.state.value.orderWrite.phase)
            assertEquals(
                saved.companies,
                model.state.value.companies
                    ?.companies,
            )
        }

    @Test
    fun missingVersionOrIncompletePermutationIsRejectedBeforeWrite() =
        runTest(dispatcher) {
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            model.saveCompanyOrder(listOf("a", "b"), 0, "stable-order")
            model.saveCompanyOrder(ids, 2, "stable-order")
            model.saveCompanyOrder(listOf("a", "a", "c"), 0, "stable-order")
            advanceUntilIdle()
            coVerify(exactly = 0) { repository.saveCompanyOrder(any(), any(), any()) }
        }

    @Test
    fun supersededRetryAckNeverOverwritesNewerLoadedOrder() =
        runTest(dispatcher) {
            coEvery { repository.saveCompanyOrder(any(), any(), any()) } throws IOException()
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            model.saveCompanyOrder(ids, 0, "stable-order")
            advanceUntilIdle()
            val newer =
                saved.copy(
                    companies = orderedCompanies(saved.companies, listOf("b", "c", "a")),
                    order_version = 2,
                )
            coEvery { repository.companies() } returns newer
            model.refresh()
            advanceUntilIdle()
            coEvery { repository.saveCompanyOrder(any(), any(), any()) } returns CareerCompanyOrder(1, ids)
            coEvery { repository.companies() } throws IOException()
            model.saveCompanyOrder(ids, 0, "stable-order")
            advanceUntilIdle()
            assertEquals(newer, model.state.value.companies)
            assertTrue(
                model.state.value.orderWrite.message
                    .orEmpty()
                    .contains("その後に別の順序"),
            )
            assertEquals(CompanyWritePhase.SAVED, model.state.value.orderWrite.phase)
            assertTrue(model.state.value.stale)
            coVerify(exactly = 0) {
                local.saveCompanyCache(
                    null,
                    match {
                        Json.decodeFromString<CareerCompanies>(it).order_version ==
                            1
                    },
                )
            }
        }

    @Test
    fun deterministicUnavailable422IsFailedRatherThanUnknown() =
        runTest(dispatcher) {
            coEvery { repository.saveCompanyOrder(any(), any(), any()) } throws
                HttpException(Response.error<CareerCompanyOrder>(422, "{}".toResponseBody()))
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            model.saveCompanyOrder(ids, 0, "stable-order")
            advanceUntilIdle()
            assertEquals(CompanyWritePhase.FAILED, model.state.value.orderWrite.phase)
        }

    @Test
    fun ackCacheFailureStillReadsServerAndReportsUnconfirmedLocalSave() =
        runTest(dispatcher) {
            val model = CareerCompanyViewModel(repository, local, null)
            advanceUntilIdle()
            coEvery { local.saveCompanyCache(any(), any()) } throws IOException()
            coEvery { repository.companies() } returns
                saved.copy(companies = orderedCompanies(saved.companies, ids), order_version = 1)
            model.saveCompanyOrder(ids, 0, "stable-order")
            advanceUntilIdle()
            coVerify(exactly = 2) { repository.companies() }
            assertEquals(CompanyWritePhase.SAVED, model.state.value.orderWrite.phase)
            assertTrue(
                model.state.value.orderWrite.message
                    .orEmpty()
                    .contains("端末への保存は未確認"),
            )
        }
}
