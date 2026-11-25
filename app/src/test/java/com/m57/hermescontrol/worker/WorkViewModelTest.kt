package com.m57.hermescontrol.worker

import com.m57.hermescontrol.home.HomeFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class WorkViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private class FakeWorkGatewayClient : WorkGatewayClient {
        var escrows: List<EscrowRecord> = emptyList()
        var subagents: List<WorkerSubagent> = emptyList()
        var jobs: List<WorkerScheduledJob> = emptyList()

        var escrowFailure: Throwable? = null
        var beforeEscrows: suspend () -> Unit = {}
        var routineReads = 0

        val decidedCalls = mutableListOf<Pair<String, EscrowDecisionRequest>>()
        val interruptedIds = mutableListOf<String>()
        val manageCalls = mutableListOf<Pair<String, String>>()

        override suspend fun fetchPendingEscrows(): Result<List<EscrowRecord>> {
            val snapshot = escrows
            beforeEscrows()
            return escrowFailure?.let { Result.failure(it) } ?: Result.success(snapshot)
        }

        override suspend fun decideEscrow(
            escrowId: String,
            request: EscrowDecisionRequest,
        ): EscrowDecisionOutcome {
            decidedCalls += escrowId to request
            return EscrowDecisionOutcome.ACCEPTED
        }

        override suspend fun listSubagents(): Result<List<WorkerSubagent>> = Result.success(subagents)

        override suspend fun interruptSubagent(subagentId: String): Result<Unit> {
            interruptedIds += subagentId
            subagents = subagents.filterNot { it.subagentId == subagentId }
            return Result.success(Unit)
        }

        override suspend fun listScheduledJobs(): Result<List<WorkerScheduledJob>> {
            routineReads++
            return Result.success(jobs)
        }

        override suspend fun manageScheduledJob(
            action: String,
            jobId: String,
        ): Result<Unit> {
            manageCalls += action to jobId
            jobs =
                when (action) {
                    "pause" -> jobs.map { if (it.id == jobId) it.copy(enabled = false) else it }
                    "resume" -> jobs.map { if (it.id == jobId) it.copy(enabled = true) else it }
                    "remove" -> jobs.filterNot { it.id == jobId }
                    else -> jobs
                }
            return Result.success(Unit)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun decideEscrow_passesDigestAndBlocksApproveOnDenyOnly() =
        runTest {
            val gateway =
                FakeWorkGatewayClient().apply {
                    escrows =
                        listOf(
                            EscrowRecord(
                                id = "esc-1",
                                digest = "sha256:one",
                                display = EscrowDisplay(title = "Confirm", deny_only = true),
                            ),
                            EscrowRecord(
                                id = "esc-2",
                                digest = "sha256:two",
                                display = EscrowDisplay(title = "Confirm 2", deny_only = false),
                            ),
                        )
                }
            val vm = WorkViewModel(gateway = gateway, subscribeCronChanges = false)
            vm.refresh()
            advanceUntilIdle()

            // deny_only blocks APPROVE without calling the API
            vm.decideEscrow(gateway.escrows[0], EscrowDecision.APPROVE)
            advanceUntilIdle()
            assertTrue(gateway.decidedCalls.isEmpty())

            // APPROVE succeeds for normal item with exact digest passthrough
            vm.decideEscrow(gateway.escrows[1], EscrowDecision.APPROVE)
            advanceUntilIdle()
            assertEquals(listOf("esc-2" to EscrowDecisionRequest("approve", "sha256:two")), gateway.decidedCalls)
            val remainingIds =
                vm.uiState.value.escrows
                    .map { it.id }
            assertEquals(listOf("esc-1"), remainingIds)
        }

    @Test
    fun scheduleActions_requireConfirmationForDeleteAndTogglePauseResume() =
        runTest {
            val job =
                WorkerScheduledJob(
                    id = "j1",
                    name = "daily-check",
                    scheduleText = "0 8 * * *",
                    enabled = true,
                    nextRunAt = null,
                    lastRunStatus = null,
                )
            val gateway = FakeWorkGatewayClient().apply { jobs = listOf(job) }
            val vm = WorkViewModel(gateway = gateway, subscribeCronChanges = false)
            vm.refresh()
            advanceUntilIdle()

            // Pause then resume
            val firstJob =
                vm.uiState.value.scheduledJobs
                    .first()
            vm.toggleJobPause(firstJob)
            advanceUntilIdle()
            assertEquals(listOf("pause" to "j1"), gateway.manageCalls)
            assertEquals(
                false,
                vm.uiState.value.scheduledJobs
                    .first()
                    .enabled,
            )

            vm.toggleJobPause(
                vm.uiState.value.scheduledJobs
                    .first(),
            )
            advanceUntilIdle()
            assertEquals(listOf("pause" to "j1", "resume" to "j1"), gateway.manageCalls)
            assertEquals(
                true,
                vm.uiState.value.scheduledJobs
                    .first()
                    .enabled,
            )

            // Delete waits for explicit confirmation
            vm.requestDeleteJob(
                vm.uiState.value.scheduledJobs
                    .first(),
            )
            assertEquals(
                "daily-check",
                vm.uiState.value.deleteTarget
                    ?.name,
            )
            assertEquals(2, gateway.manageCalls.size)

            vm.dismissDeleteJob()
            assertNull(vm.uiState.value.deleteTarget)
            assertEquals(2, gateway.manageCalls.size)

            vm.requestDeleteJob(
                vm.uiState.value.scheduledJobs
                    .first(),
            )
            vm.confirmDeleteJob()
            advanceUntilIdle()
            assertEquals("remove" to "j1", gateway.manageCalls.last())
            assertTrue(
                vm.uiState.value.scheduledJobs
                    .isEmpty(),
            )
        }

    @Test
    fun interruptSubagent_forwardsIdAndRefreshesList() =
        runTest {
            val gateway =
                FakeWorkGatewayClient().apply {
                    subagents =
                        listOf(
                            WorkerSubagent(subagentId = "sub-42", status = "running", goal = "Research", model = null),
                        )
                }
            val vm = WorkViewModel(gateway = gateway, subscribeCronChanges = false)
            vm.refresh()
            advanceUntilIdle()

            vm.interruptSubagent("sub-42")
            advanceUntilIdle()

            assertEquals(listOf("sub-42"), gateway.interruptedIds)
            assertTrue(
                vm.uiState.value.subagents
                    .isEmpty(),
            )
        }

    @Test
    fun refreshesInFlightCoalesceIntoExactlyOneRerun() =
        runTest {
            val response = CompletableDeferred<Unit>()
            val gateway = FakeWorkGatewayClient().apply { beforeEscrows = { response.await() } }
            val vm =
                WorkViewModel(
                    gateway,
                    subscribeCronChanges = false,
                    scopeKey = { "main" },
                    epochs = MutableStateFlow(0L),
                )
            vm.refresh()
            // Also covers a second request before the refresh coroutine has been dispatched.
            vm.refresh()
            runCurrent()
            vm.refresh()
            vm.refresh()
            assertEquals(1, gateway.routineReads)
            response.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, gateway.routineReads)
        }

    @Test
    fun connectionChangeClearsAllListsBeforeRejectingOldInFlightResults() =
        runTest {
            var key = "first"
            val epochs = MutableStateFlow(0L)
            val gateway =
                FakeWorkGatewayClient().apply {
                    escrows = listOf(EscrowRecord(id = "old", digest = "sha256:old"))
                    subagents =
                        listOf(
                            WorkerSubagent(subagentId = "old", status = "running", goal = "old", model = null),
                        )
                    jobs = listOf(WorkerScheduledJob("old", "old", "0 8 * * *", true, null, null))
                }
            val vm = WorkViewModel(gateway, subscribeCronChanges = false, scopeKey = { key }, epochs = epochs)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(1, vm.uiState.value.escrows.size)
            assertEquals(1, vm.uiState.value.subagents.size)
            assertEquals(1, vm.uiState.value.scheduledJobs.size)

            val oldResponse = CompletableDeferred<Unit>()
            gateway.beforeEscrows = { withContext(NonCancellable) { oldResponse.await() } }
            vm.refresh()
            runCurrent()
            val newResponse = CompletableDeferred<Unit>()
            gateway.beforeEscrows = { newResponse.await() }
            gateway.escrows = emptyList()
            gateway.subagents = emptyList()
            gateway.jobs = emptyList()
            key = "second"
            epochs.value = 2L
            runCurrent()
            assertTrue(vm.uiState.value.isAllEmpty)
            assertTrue(vm.uiState.value.isLoading)

            oldResponse.complete(Unit)
            runCurrent()
            assertTrue(vm.uiState.value.isAllEmpty)
            assertTrue(vm.uiState.value.isLoading)
            newResponse.complete(Unit)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isAllEmpty)
            assertEquals(3, gateway.routineReads)
        }

    @Test
    fun approvals404MeansUnavailableWithoutWorkErrors() =
        runTest {
            val gateway = FakeWorkGatewayClient().apply { escrowFailure = httpError(404) }
            val vm =
                WorkViewModel(
                    gateway,
                    subscribeCronChanges = false,
                    scopeKey = { "main" },
                    epochs = MutableStateFlow(0L),
                )
            vm.refresh()
            advanceUntilIdle()
            assertEquals(WorkerSourceStatus.Unavailable, vm.uiState.value.approvalsStatus)
            assertEquals(WorkerSourceStatus.Ok, vm.uiState.value.routinesStatus)
            assertNull(vm.uiState.value.fatalErrorRes)
            assertNull(vm.uiState.value.partialErrorRes)
        }

    @Test
    fun approvals401MeansAuthRequired() =
        runTest {
            val gateway = FakeWorkGatewayClient().apply { escrowFailure = httpError(401) }
            val vm =
                WorkViewModel(
                    gateway,
                    subscribeCronChanges = false,
                    scopeKey = { "main" },
                    epochs = MutableStateFlow(0L),
                )
            vm.refresh()
            advanceUntilIdle()
            assertEquals(WorkerSourceStatus.Failed(HomeFailure.AuthRequired), vm.uiState.value.approvalsStatus)
            assertNull(vm.uiState.value.fatalErrorRes)
            assertEquals(com.m57.hermescontrol.R.string.worker_work_error_partial, vm.uiState.value.partialErrorRes)
        }

    private fun httpError(status: Int) = HttpException(Response.error<Any>(status, "{}".toResponseBody()))
}
