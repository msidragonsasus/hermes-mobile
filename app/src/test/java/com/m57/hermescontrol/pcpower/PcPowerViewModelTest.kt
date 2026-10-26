package com.m57.hermescontrol.pcpower

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class PcPowerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val awake = PcPowerStatus(1, "desktop-ryzen7", "awake", wake_available = true, sleep_available = true)
    private val asleep = awake.copy(power_state = "asleep_or_unreachable")
    private val repository = FakePcPowerRepository(awake)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test
    fun statusIsReadOnceOnEachResumeAndNotDuringConstruction() =
        runTest(dispatcher) {
            val model = PcPowerViewModel(repository)
            runCurrent()
            assertEquals(0, repository.statusCalls)
            model.onScreenResumed()
            model.onScreenResumed()
            runCurrent()
            assertEquals(1, repository.statusCalls)
            model.onScreenPaused()
            assertFalse(model.state.value.canSleep)
            model.onScreenResumed()
            runCurrent()
            assertEquals(2, repository.statusCalls)
            assertTrue(model.state.value.canSleep)
        }

    @Test
    fun wakeIsOneDeterministicCallAndStatusRefreshesUntilAwake() =
        runTest(dispatcher) {
            repository.statusRead = { if (repository.statusCalls < 4) asleep else awake }
            val model = resumedModel()
            runCurrent()
            model.wake()
            model.wake()
            model.sleep()
            runCurrent()
            assertEquals(1, repository.wakeCalls)
            assertEquals(2, repository.statusCalls)
            assertTrue(model.state.value.checkingTransition)
            assertFalse(model.state.value.canSleep)
            advanceTimeBy(4_999)
            runCurrent()
            assertEquals(2, repository.statusCalls)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(3, repository.statusCalls)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(4, repository.statusCalls)
            assertEquals(
                "awake",
                model.state.value.status
                    ?.power_state,
            )
            assertTrue(model.state.value.canSleep)
            assertNotNull(model.state.value.message)
            advanceUntilIdle()
            assertEquals(4, repository.statusCalls)
            assertEquals(1, repository.wakeCalls)
            assertTrue(repository.sleepOperationIds.isEmpty())
        }

    @Test
    fun sleepStillChecksWhenFirstPostRequestStatusIsAwake() =
        runTest(dispatcher) {
            repository.statusRead = { if (repository.statusCalls < 3) awake else asleep }
            val model = resumedModel()
            runCurrent()
            model.sleep()
            model.sleep()
            model.wake()
            runCurrent()
            assertTrue(model.state.value.checkingTransition)
            assertFalse(model.state.value.canSleep)
            assertEquals(
                "awake",
                model.state.value.status
                    ?.power_state,
            )
            assertEquals(1, repository.sleepOperationIds.size)
            advanceTimeBy(5_000)
            runCurrent()
            assertFalse(model.state.value.checkingTransition)
            assertEquals(
                "asleep_or_unreachable",
                model.state.value.status
                    ?.power_state,
            )
            assertFalse(model.state.value.canSleep)
            assertTrue(model.state.value.canWake)
            assertEquals(1, repository.sleepOperationIds.size)
            assertEquals(0, repository.wakeCalls)
            UUID.fromString(repository.sleepOperationIds.single())
        }

    @Test
    fun automaticStatusReadsAreBoundedWhenWakeIsNeverObserved() =
        runTest(dispatcher) {
            repository.statusRead = { asleep }
            val model = resumedModel()
            runCurrent()
            model.wake()
            advanceUntilIdle()
            assertEquals(8, repository.statusCalls)
            assertEquals(30_000L, testScheduler.currentTime)
            assertEquals(asleep, model.state.value.status)
            assertEquals(1, repository.wakeCalls)
            assertFalse(model.state.value.checkingTransition)
            assertFalse(model.state.value.canSleep)
            assertTrue(model.state.value.canRefresh)
            assertNotNull(model.state.value.refreshNote)
        }

    @Test
    fun sleepPollingLimitPreservesLastObservedAwakeStateAndShowsUnconfirmedChange() =
        runTest(dispatcher) {
            val model = resumedModel()
            runCurrent()
            model.sleep()
            advanceUntilIdle()
            assertEquals(8, repository.statusCalls)
            assertEquals(1, repository.sleepOperationIds.size)
            assertEquals(awake, model.state.value.status)
            assertFalse(model.state.value.checkingTransition)
            assertNotNull(model.state.value.refreshNote)
            assertNotNull(model.state.value.message)
            assertTrue(model.state.value.canSleep)
        }

    @Test
    fun sleepUsesOneOperationIdAndPreservesUnknownResultAfterSuccessfulStatusReads() =
        runTest(dispatcher) {
            repository.onSleep = { throw IOException() }
            val model = resumedModel()
            runCurrent()
            model.sleep()
            advanceUntilIdle()
            assertEquals(1, repository.sleepOperationIds.size)
            UUID.fromString(repository.sleepOperationIds.single())
            assertEquals(8, repository.statusCalls)
            assertNotNull(model.state.value.error)
            assertFalse(model.state.value.busy)
            model.refresh()
            runCurrent()
            assertNotNull(model.state.value.error)
            assertEquals(1, repository.sleepOperationIds.size)
        }

    @Test
    fun failedStatusReadCannotReuseOldAwakeStateForSleep() =
        runTest(dispatcher) {
            val model = resumedModel()
            runCurrent()
            assertTrue(model.state.value.canSleep)
            repository.statusRead = { throw IOException() }
            model.refresh()
            assertFalse(model.state.value.canSleep)
            runCurrent()
            assertTrue(model.state.value.statusStale)
            assertNotNull(model.state.value.statusError)
            assertFalse(model.state.value.canSleep)
            model.sleep()
            model.wake()
            runCurrent()
            assertTrue(repository.sleepOperationIds.isEmpty())
            assertEquals(0, repository.wakeCalls)
            repository.statusRead = { awake }
            model.refresh()
            runCurrent()
            assertTrue(model.state.value.canSleep)
            assertNull(model.state.value.statusError)
        }

    @Test
    fun manualRefreshCoalescesConcurrentReadsAndCannotDuplicateAutomaticPolls() =
        runTest(dispatcher) {
            repository.statusRead = {
                delay(1_000)
                asleep
            }
            val model = resumedModel()
            repeat(10) { model.refresh() }
            runCurrent()
            assertEquals(1, repository.statusCalls)
            assertFalse(model.state.value.canSleep)
            advanceTimeBy(1_000)
            runCurrent()
            model.wake()
            runCurrent()
            repeat(10) { model.refresh() }
            assertEquals(2, repository.statusCalls)
            advanceTimeBy(1_000)
            runCurrent()
            repeat(10) { model.refresh() }
            advanceUntilIdle()
            assertEquals(8, repository.statusCalls)
            assertEquals(1, repository.wakeCalls)
        }

    @Test
    fun postActionReadFailureStopsPollingWithoutLosingAcknowledgement() =
        runTest(dispatcher) {
            val model = resumedModel()
            runCurrent()
            repository.statusRead = { throw IOException() }
            model.sleep()
            advanceUntilIdle()
            assertEquals(2, repository.statusCalls)
            assertEquals(1, repository.sleepOperationIds.size)
            assertNotNull(model.state.value.message)
            assertNotNull(model.state.value.statusError)
            assertFalse(model.state.value.canSleep)
            assertTrue(model.state.value.canRefresh)
            assertFalse(model.state.value.checkingTransition)
        }

    @Test
    fun hungStatusReadTimesOutAndAllowsManualRecovery() =
        runTest(dispatcher) {
            repository.statusRead = { awaitCancellation() }
            val model = resumedModel()
            advanceUntilIdle()
            assertEquals(5_000L, testScheduler.currentTime)
            assertNotNull(model.state.value.statusError)
            assertFalse(model.state.value.loading)
            assertTrue(model.state.value.canRefresh)
            repository.statusRead = { awake }
            model.refresh()
            runCurrent()
            assertTrue(model.state.value.canSleep)
        }

    @Test
    fun leavingScreenCancelsDelayedPollingAndIgnoresRefreshCallbacks() =
        runTest(dispatcher) {
            repository.statusRead = { asleep }
            val model = resumedModel()
            runCurrent()
            model.wake()
            runCurrent()
            assertEquals(2, repository.statusCalls)
            model.onScreenPaused()
            model.refresh()
            model.wake()
            model.sleep()
            advanceUntilIdle()
            assertEquals(2, repository.statusCalls)
            assertEquals(1, repository.wakeCalls)
            assertFalse(model.state.value.checkingTransition)
            assertFalse(model.state.value.loading)
            assertTrue(model.state.value.statusStale)
        }

    @Test
    fun cancelledOldReadCannotOverwriteNewResumeResultOrLoadingFlags() =
        runTest(dispatcher) {
            repository.statusRead = {
                withContext(NonCancellable) {
                    delay(10_000)
                    asleep
                }
            }
            val model = resumedModel()
            runCurrent()
            model.onScreenPaused()
            repository.statusRead = { awake }
            model.onScreenResumed()
            runCurrent()
            assertTrue(model.state.value.canSleep)
            advanceUntilIdle()
            assertEquals(2, repository.statusCalls)
            assertEquals(awake, model.state.value.status)
            assertTrue(model.state.value.canSleep)
            assertNull(model.state.value.statusError)
        }

    @Test
    fun oldProfileModelStopsReadingAfterNewProfileModelResumes() =
        runTest(dispatcher) {
            repository.statusRead = { asleep }
            val oldModel = resumedModel()
            runCurrent()
            oldModel.wake()
            runCurrent()
            oldModel.onScreenPaused()
            val newRepository = FakePcPowerRepository(awake.copy(target_name = "other-profile"))
            val newModel = PcPowerViewModel(newRepository)
            newModel.onScreenResumed()
            advanceUntilIdle()
            assertEquals(2, repository.statusCalls)
            assertEquals(1, newRepository.statusCalls)
            assertEquals(
                "other-profile",
                newModel.state.value.status
                    ?.target_name,
            )
            assertFalse(oldModel.state.value.canSleep)
        }

    @Test
    fun actionFinishingOffScreenDoesNotStartPollingAndResumeReadsOnce() =
        runTest(dispatcher) {
            val request = CompletableDeferred<Unit>()
            repository.onWake = { request.await() }
            val model = resumedModel()
            runCurrent()
            model.wake()
            runCurrent()
            model.onScreenPaused()
            request.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, repository.statusCalls)
            assertFalse(model.state.value.busy)
            model.onScreenResumed()
            runCurrent()
            assertEquals(2, repository.statusCalls)
            assertTrue(model.state.value.canSleep)
            assertEquals(1, repository.wakeCalls)
        }

    @Test
    fun resumingDuringPendingActionDoesNotSendAnotherActionOrConcurrentRead() =
        runTest(dispatcher) {
            val request = CompletableDeferred<Unit>()
            repository.onWake = { request.await() }
            val model = resumedModel()
            runCurrent()
            model.wake()
            runCurrent()
            model.onScreenPaused()
            model.onScreenResumed()
            model.wake()
            model.sleep()
            model.refresh()
            runCurrent()
            assertEquals(1, repository.statusCalls)
            assertEquals(1, repository.wakeCalls)
            request.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, repository.statusCalls)
            assertEquals(1, repository.wakeCalls)
            assertTrue(model.state.value.canSleep)
        }

    @Test
    fun sleepRequiresBothAwakeAndAvailableAndWakeNeedsCapability() =
        runTest(dispatcher) {
            repository.statusRead = { awake.copy(sleep_available = false, wake_available = false) }
            val model = resumedModel()
            runCurrent()
            model.sleep()
            model.wake()
            runCurrent()
            assertFalse(model.state.value.canSleep)
            assertFalse(model.state.value.canWake)
            assertTrue(repository.sleepOperationIds.isEmpty())
            assertEquals(0, repository.wakeCalls)
            repository.statusRead = { asleep }
            model.refresh()
            runCurrent()
            model.sleep()
            runCurrent()
            assertFalse(model.state.value.canSleep)
            assertTrue(repository.sleepOperationIds.isEmpty())
        }

    private fun resumedModel(): PcPowerViewModel = PcPowerViewModel(repository).also { it.onScreenResumed() }

    private class FakePcPowerRepository(
        initialStatus: PcPowerStatus,
    ) : PcPowerRepository {
        var statusCalls = 0
        var wakeCalls = 0
        val sleepOperationIds = mutableListOf<String>()
        var statusRead: suspend () -> PcPowerStatus = { initialStatus }
        var onWake: suspend () -> Unit = {}
        var onSleep: suspend () -> Unit = {}

        override suspend fun status(): PcPowerStatus {
            statusCalls++
            return statusRead()
        }

        override suspend fun wake(): PcPowerActionResponse {
            wakeCalls++
            onWake()
            return PcPowerActionResponse(1, "desktop-ryzen7", "wake_requested")
        }

        override suspend fun sleep(operationId: String): PcPowerActionResponse {
            sleepOperationIds.add(operationId)
            onSleep()
            return PcPowerActionResponse(1, "desktop-ryzen7", "sleep_accepted", operationId)
        }
    }
}
