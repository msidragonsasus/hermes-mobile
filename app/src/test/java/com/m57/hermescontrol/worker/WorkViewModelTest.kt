package com.m57.hermescontrol.worker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private class FakeWorkGatewayClient : WorkGatewayClient {
        var escrows: List<EscrowRecord> = emptyList()
        var subagents: List<WorkerSubagent> = emptyList()
        var jobs: List<WorkerScheduledJob> = emptyList()

        val decidedCalls = mutableListOf<Pair<String, EscrowDecisionRequest>>()
        val interruptedIds = mutableListOf<String>()
        val manageCalls = mutableListOf<Pair<String, String>>()

        override suspend fun fetchPendingEscrows(): Result<List<EscrowRecord>> = Result.success(escrows)

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

        override suspend fun listScheduledJobs(): Result<List<WorkerScheduledJob>> = Result.success(jobs)

        override suspend fun manageScheduledJob(
            action: String,
            name: String,
        ): Result<Unit> {
            manageCalls += action to name
            jobs =
                when (action) {
                    "pause" -> jobs.map { if (it.name == name) it.copy(enabled = false) else it }
                    "resume" -> jobs.map { if (it.name == name) it.copy(enabled = true) else it }
                    "remove" -> jobs.filterNot { it.name == name }
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
            assertEquals(listOf("pause" to "daily-check"), gateway.manageCalls)
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
            assertEquals(listOf("pause" to "daily-check", "resume" to "daily-check"), gateway.manageCalls)
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
            assertEquals("remove" to "daily-check", gateway.manageCalls.last())
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
}
