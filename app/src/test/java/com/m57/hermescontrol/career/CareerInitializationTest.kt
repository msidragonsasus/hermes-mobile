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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CareerInitializationTest {
    private val dispatcher = StandardTestDispatcher()
    private val local = mockk<CareerLocalStore>()
    private val repository = mockk<CareerRepository>()
    private val saved = CareerSnapshot(1, listOf(CareerEntity("facts", "saved", 1, JsonObject(emptyMap()))))
    private val pending = CareerPendingOperation("save", buildJsonObject { put("operation_id", "saved-operation") })

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { local.enabled() } returns true
        coEvery { local.cached() } returns saved
        coEvery { local.pending() } returns null
        coEvery { local.cache(any()) } returns Unit
        coEvery { repository.snapshot() } returns saved
    }

    @After
    fun cleanup() = Dispatchers.resetMain()

    @Test
    fun genuineDisabledScopeShowsOnboardingWithoutReadingOrChangingItsData() =
        runTest(dispatcher) {
            coEvery { local.enabled() } returns false
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()
            assertTrue(model.state.value.initialized)
            assertFalse(model.state.value.enabled)
            assertNull(model.state.value.initializationError)
            model.refresh()
            model.retryInitialization()
            advanceUntilIdle()
            coVerify(exactly = 0) { local.cached() }
            coVerify(exactly = 0) { local.pending() }
            assertNoWrites()
            coVerify(exactly = 0) { repository.snapshot() }
        }

    @Test
    fun cacheReadFailurePreservesKnownEnableAndPendingWithoutSubmitting() =
        runTest(dispatcher) {
            coEvery { local.cached() } throws IOException("synthetic unreadable cache")
            coEvery { local.pending() } returns pending
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()
            assertTrue(model.state.value.enabled)
            assertFalse(model.state.value.initialized)
            assertNotNull(model.state.value.initializationError)
            assertEquals(pending, model.state.value.pending)
            model.retry()
            assertNoWrites()
            coVerify(exactly = 0) { repository.snapshot() }
        }

    @Test
    fun pendingReadFailurePreservesKnownEnableAndReadableCacheButBlocksWrites() =
        runTest(dispatcher) {
            coEvery { local.pending() } throws IOException("synthetic unreadable journal")
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()
            assertTrue(model.state.value.enabled)
            assertFalse(model.state.value.initialized)
            assertTrue(model.state.value.stale)
            assertEquals(saved, model.state.value.snapshot)
            assertNotNull(model.state.value.initializationError)
            var discarded = false
            model.send("save", JsonObject(emptyMap()))
            model.importShukatsu(ShukatsuImportResult(JsonObject(emptyMap())))
            model.retry()
            model.discardPendingAndThen { discarded = true }
            model.enable(true)
            model.enable(false)
            model.refresh()
            advanceUntilIdle()
            assertFalse(discarded)
            assertNoWrites()
            coVerify(exactly = 0) { repository.snapshot() }
        }

    @Test
    fun enabledFlagReadFailureIsUnknownRatherThanCompletedFirstUse() =
        runTest(dispatcher) {
            coEvery { local.enabled() } throws IOException("synthetic unreadable enabled flag")
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()
            assertFalse(model.state.value.initialized)
            assertNotNull(model.state.value.initializationError)
            model.enable(true)
            advanceUntilIdle()
            assertNoWrites()
            coVerify(exactly = 0) { local.cached() }
            coVerify(exactly = 0) { local.pending() }
            coVerify(exactly = 0) { repository.snapshot() }
        }

    @Test
    fun localRetryRecoversCacheFailureWithoutChangingEnableOrSendingPending() =
        runTest(dispatcher) {
            coEvery { local.cached() } throws IOException("transient cache read failure")
            coEvery { local.pending() } returns pending
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()
            coEvery { local.cached() } returns saved
            model.retryInitialization()
            model.retryInitialization()
            advanceUntilIdle()
            assertTrue(model.state.value.initialized)
            assertTrue(model.state.value.enabled)
            assertEquals(saved, model.state.value.snapshot)
            assertEquals(pending, model.state.value.pending)
            assertNull(model.state.value.initializationError)
            assertNull(model.state.value.error)
            coVerify(exactly = 2) { local.enabled() }
            coVerify(exactly = 1) { repository.snapshot() }
            assertNoWrites()
        }

    @Test
    fun localRetryRecoversPendingFailureAndDoesNotForgetExistingOperation() =
        runTest(dispatcher) {
            coEvery { local.pending() } throws IOException("transient pending read failure")
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()
            coEvery { local.pending() } returns pending
            model.retryInitialization()
            advanceUntilIdle()
            assertTrue(model.state.value.initialized)
            assertEquals(pending, model.state.value.pending)
            assertNull(model.state.value.initializationError)
            model.send("save", JsonObject(emptyMap()))
            advanceUntilIdle()
            assertNoWrites()
        }

    @Test
    fun failedRetryKeepsPreviouslyReadableCacheAndNeverUsesAnEnableFallback() =
        runTest(dispatcher) {
            coEvery { local.pending() } throws IOException("journal unavailable")
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()
            coEvery { local.cached() } throws IOException("cache also unavailable")
            model.retryInitialization()
            advanceUntilIdle()
            assertTrue(model.state.value.enabled)
            assertFalse(model.state.value.initialized)
            assertEquals(saved, model.state.value.snapshot)
            assertNotNull(model.state.value.initializationError)
            assertNoWrites()
        }

    @Test
    fun failedEnabledReadCanRecoverToStoredFalseWithoutAutoEnabling() =
        runTest(dispatcher) {
            coEvery { local.enabled() } throws IOException("settings unavailable")
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()
            coEvery { local.enabled() } returns false
            model.retryInitialization()
            advanceUntilIdle()
            assertTrue(model.state.value.initialized)
            assertFalse(model.state.value.enabled)
            assertNull(model.state.value.initializationError)
            assertNoWrites()
            coVerify(exactly = 0) { repository.snapshot() }
        }

    @Test
    fun ordinaryOfflineUsesCacheAndDoesNotBecomeInitializationFailure() =
        runTest(dispatcher) {
            coEvery { repository.snapshot() } throws IOException("offline")
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()
            assertTrue(model.state.value.initialized)
            assertTrue(model.state.value.enabled)
            assertTrue(model.state.value.stale)
            assertEquals(saved, model.state.value.snapshot)
            assertNull(model.state.value.initializationError)
            assertNotNull(model.state.value.error)
            assertNoWrites()
        }

    @Test
    fun enableAndSendCannotRaceUnfinishedLocalInitialization() =
        runTest(dispatcher) {
            val read = CompletableDeferred<Boolean>()
            coEvery { local.enabled() } coAnswers { read.await() }
            val model = CareerViewModel(repository, local)
            runCurrent()
            model.enable(true)
            model.send("save", JsonObject(emptyMap()))
            model.discardPending()
            model.refresh()
            runCurrent()
            assertNoWrites()
            read.complete(false)
            advanceUntilIdle()
            assertTrue(model.state.value.initialized)
            assertFalse(model.state.value.enabled)
        }

    @Test
    fun explicitEnableRestoresCachedDataWhenServerIsOffline() =
        runTest(dispatcher) {
            var enabled = false
            coEvery { local.enabled() } answers { enabled }
            coEvery { local.setEnabled(any()) } coAnswers { enabled = firstArg() }
            coEvery { repository.snapshot() } throws IOException("offline")
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()
            model.enable(true)
            model.enable(true)
            advanceUntilIdle()
            assertTrue(model.state.value.enabled)
            assertTrue(model.state.value.initialized)
            assertEquals(saved, model.state.value.snapshot)
            coVerify(exactly = 1) { local.setEnabled(true) }
            coVerify(exactly = 0) { local.savePending(any()) }
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @Test
    fun explicitEnableCannotBypassUnreadablePendingJournal() =
        runTest(dispatcher) {
            var enabled = false
            coEvery { local.enabled() } answers { enabled }
            coEvery { local.setEnabled(any()) } coAnswers { enabled = firstArg() }
            coEvery { local.pending() } throws IOException("unreadable journal")
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()
            model.enable(true)
            advanceUntilIdle()
            assertTrue(model.state.value.enabled)
            assertFalse(model.state.value.initialized)
            assertEquals(saved, model.state.value.snapshot)
            assertNotNull(model.state.value.initializationError)
            model.send("save", JsonObject(emptyMap()))
            advanceUntilIdle()
            coVerify(exactly = 0) { local.savePending(any()) }
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
            coVerify(exactly = 0) { repository.snapshot() }
        }

    @Test
    fun repeatedRefreshCannotRaceDisableOrDuplicateSnapshotReads() =
        runTest(dispatcher) {
            val snapshot = CompletableDeferred<CareerSnapshot>()
            coEvery { repository.snapshot() } coAnswers { snapshot.await() }
            val model = CareerViewModel(repository, local)
            runCurrent()
            model.refresh()
            model.refresh()
            model.enable(false)
            runCurrent()
            coVerify(exactly = 1) { repository.snapshot() }
            coVerify(exactly = 0) { local.setEnabled(any()) }
            snapshot.complete(saved)
            advanceUntilIdle()
            assertTrue(model.state.value.enabled)
            assertEquals(saved, model.state.value.snapshot)
        }

    private fun assertNoWrites() {
        coVerify(exactly = 0) { local.setEnabled(any()) }
        coVerify(exactly = 0) { local.savePending(any()) }
        coVerify(exactly = 0) { repository.mutate(any(), any()) }
    }
}
