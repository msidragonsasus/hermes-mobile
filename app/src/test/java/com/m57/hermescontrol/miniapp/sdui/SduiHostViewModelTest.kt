package com.m57.hermescontrol.miniapp.sdui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SduiHostViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeRepository()
    private var nextId = 0
    private val disk = FakePendingDisk()
    private val model = SduiHostViewModel(repository, COMPANY, disk.store("scope", COMPANY)) { "op-${++nextId}" }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private class FakeRepository : SduiRepository {
        var current = true
        var manifestResult: SduiFetch<SduiManifest> = SduiFetch.Ok(manifestOf())
        var screenResult: suspend () -> SduiFetch<SduiScreen> = { SduiFetch.Ok(screenOf()) }
        var checkResult: suspend (String) -> SduiCheckResult = { SduiCheckResult.Ambiguous }
        var manifestCalls = 0
        var screenCalls = 0
        val checkIds = mutableListOf<String>()
        val checkReleases = mutableListOf<String>()

        override fun isCurrent() = current

        override suspend fun manifest(): SduiFetch<SduiManifest> {
            manifestCalls++
            return manifestResult
        }

        override suspend fun screen(
            manifest: SduiManifest,
            companyId: String,
        ): SduiFetch<SduiScreen> {
            screenCalls++
            return screenResult()
        }

        override suspend fun check(
            screen: SduiScreen,
            operationId: String,
            releaseId: String,
        ): SduiCheckResult {
            checkIds += operationId
            checkReleases += releaseId
            return checkResult(operationId)
        }
    }

    private fun accepted(
        operationId: String,
        state: SduiReceiptState = SduiReceiptState.QUEUED,
    ) = SduiCheckResult.Accepted(SduiReceipt("bundled-1", COMPANY, "default", operationId, state, "run-1"))

    private fun TestScope.loaded(): SduiUiState {
        model.load()
        advanceUntilIdle()
        return model.state.value
    }

    // ── reads never write ───────────────────────────────────────────────

    @Test
    fun loadingReadsOnlyAndNeverSubmitsAnything() =
        runTest(dispatcher) {
            val state = loaded()
            assertTrue(state.phase is SduiPhase.Ready)
            assertEquals(SduiCheckState.Idle, state.check)
            assertEquals(1, repository.manifestCalls)
            assertEquals(1, repository.screenCalls)
            assertEquals(emptyList<String>(), repository.checkIds)

            model.refresh()
            model.release()
            model.load()
            advanceUntilIdle()
            assertEquals(emptyList<String>(), repository.checkIds)
        }

    @Test
    fun loadIsNotRepeatedWhileLoading() =
        runTest(dispatcher) {
            model.load()
            model.load()
            model.refresh()
            advanceUntilIdle()
            assertEquals(1, repository.manifestCalls)
        }

    // ── unavailable states ──────────────────────────────────────────────

    @Test
    fun notFoundMeansHostUnsupported() =
        runTest(dispatcher) {
            repository.manifestResult = SduiFetch.NotFound
            assertEquals(SduiPhase.Unavailable(SduiUnavailable.HOST_UNSUPPORTED), loaded().phase)
            assertEquals(0, repository.screenCalls)
        }

    @Test
    fun unauthorizedLoadIsAuthRequired() =
        runTest(dispatcher) {
            repository.screenResult = { SduiFetch.Unauthorized }
            assertEquals(SduiPhase.Unavailable(SduiUnavailable.AUTH_REQUIRED), loaded().phase)
        }

    @Test
    fun unknownContractIsUnsupportedNotPartial() =
        runTest(dispatcher) {
            val unsupported =
                listOf(
                    SduiRejection.UNSUPPORTED_VERSION,
                    SduiRejection.UNKNOWN_COMPONENT,
                    SduiRejection.UNKNOWN_ACTION,
                    SduiRejection.LIMIT_EXCEEDED,
                    SduiRejection.TOO_LARGE,
                    SduiRejection.IDENTITY_MISMATCH,
                )
            for (reason in unsupported) {
                repository.screenResult = { SduiFetch.Rejected(reason) }
                val state = loaded()
                assertEquals(reason.name, SduiPhase.Unavailable(SduiUnavailable.CONTRACT_UNSUPPORTED), state.phase)
                model.release()
            }
        }

    @Test
    fun releaseDisagreementAsksForReloadAndNeverWrites() =
        runTest(dispatcher) {
            repository.screenResult = { SduiFetch.Rejected(SduiRejection.RELEASE_MISMATCH) }
            assertEquals(SduiPhase.Unavailable(SduiUnavailable.RELEASE_CHANGED), loaded().phase)
            assertEquals(emptyList<String>(), repository.checkIds)
        }

    @Test
    fun networkFailureIsRetryableAndReloadingWorks() =
        runTest(dispatcher) {
            repository.screenResult = { SduiFetch.Unavailable }
            assertEquals(SduiPhase.Unavailable(SduiUnavailable.LOAD_FAILED), loaded().phase)
            repository.screenResult = { SduiFetch.Ok(screenOf()) }
            model.load()
            advanceUntilIdle()
            assertTrue(model.state.value.phase is SduiPhase.Ready)
        }

    // ── single operation, duplicate taps ────────────────────────────────

    @Test
    fun duplicateTapWhilePendingSendsOnce() =
        runTest(dispatcher) {
            loaded()
            val gate = CompletableDeferred<SduiCheckResult>()
            repository.checkResult = { gate.await() }
            model.sendCheck()
            model.sendCheck()
            model.sendCheck()
            advanceUntilIdle()
            assertEquals(listOf("op-1"), repository.checkIds)
            assertEquals(SduiCheckState.Sending("op-1", retry = false), model.state.value.check)

            gate.complete(accepted("op-1"))
            advanceUntilIdle()
            assertEquals(listOf("op-1"), repository.checkIds)
            assertTrue(model.state.value.check is SduiCheckState.Accepted)
        }

    @Test
    fun readOnlyManifestNeverSends() =
        runTest(dispatcher) {
            repository.manifestResult = SduiFetch.Ok(manifestOf(permissions = setOf("mypage.read")))
            loaded()
            model.sendCheck()
            advanceUntilIdle()
            assertEquals(emptyList<String>(), repository.checkIds)
        }

    @Test
    fun sendBeforeTheScreenIsReadyDoesNothing() =
        runTest(dispatcher) {
            model.sendCheck()
            model.retryCheck()
            advanceUntilIdle()
            assertEquals(emptyList<String>(), repository.checkIds)
        }

    @Test
    fun acceptedReceiptBlocksANewOperationUntilAFreshDisplay() =
        runTest(dispatcher) {
            loaded()
            repository.checkResult = { accepted(it, SduiReceiptState.RUNNING) }
            model.sendCheck()
            advanceUntilIdle()
            assertEquals(SduiReceiptState.RUNNING, (model.state.value.check as SduiCheckState.Accepted).receipt.state)

            // A second tap and an entry reload do not start anything.
            model.sendCheck()
            model.release()
            model.load()
            advanceUntilIdle()
            model.sendCheck()
            advanceUntilIdle()
            assertEquals(listOf("op-1"), repository.checkIds)
            assertTrue(model.state.value.check is SduiCheckState.Accepted)

            model.refresh()
            advanceUntilIdle()
            assertEquals(SduiCheckState.Idle, model.state.value.check)
            model.sendCheck()
            advanceUntilIdle()
            assertEquals(listOf("op-1", "op-2"), repository.checkIds)
        }

    @Test
    fun definitiveRefusalAllowsANewOperation() =
        runTest(dispatcher) {
            loaded()
            repository.checkResult = { SduiCheckResult.Refused }
            model.sendCheck()
            advanceUntilIdle()
            assertEquals(SduiCheckState.Refused, model.state.value.check)
            model.sendCheck()
            advanceUntilIdle()
            assertEquals(listOf("op-1", "op-2"), repository.checkIds)
        }

    // ── ambiguity ───────────────────────────────────────────────────────

    @Test
    fun ambiguousResultRetainsTheIdAcrossRefreshAndNeverSilentlyStartsANewOne() =
        runTest(dispatcher) {
            loaded()
            model.sendCheck()
            advanceUntilIdle()
            assertEquals(SduiCheckState.Ambiguous("op-1"), model.state.value.check)

            model.sendCheck()
            model.refresh()
            advanceUntilIdle()
            model.sendCheck()
            advanceUntilIdle()
            assertEquals(SduiCheckState.Ambiguous("op-1"), model.state.value.check)
            assertEquals(listOf("op-1"), repository.checkIds)
        }

    @Test
    fun explicitRetryReusesTheSameOperationId() =
        runTest(dispatcher) {
            loaded()
            model.sendCheck()
            advanceUntilIdle()
            repository.checkResult = { accepted(it) }
            model.retryCheck()
            advanceUntilIdle()
            assertEquals(listOf("op-1", "op-1"), repository.checkIds)
            assertEquals("op-1", (model.state.value.check as SduiCheckState.Accepted).receipt.operationId)
        }

    @Test
    fun repeatedAmbiguousRetriesKeepTheSameId() =
        runTest(dispatcher) {
            loaded()
            model.sendCheck()
            advanceUntilIdle()
            model.retryCheck()
            advanceUntilIdle()
            model.retryCheck()
            advanceUntilIdle()
            assertEquals(listOf("op-1", "op-1", "op-1"), repository.checkIds)
            assertEquals(SduiCheckState.Ambiguous("op-1"), model.state.value.check)
        }

    @Test
    fun retryWithoutAnAmbiguousOperationDoesNothing() =
        runTest(dispatcher) {
            loaded()
            model.retryCheck()
            advanceUntilIdle()
            assertEquals(emptyList<String>(), repository.checkIds)
        }

    @Test
    fun leavingWhileSendingKeepsTheIdAndReEntryDoesNotResend() =
        runTest(dispatcher) {
            loaded()
            val gate = CompletableDeferred<SduiCheckResult>()
            repository.checkResult = { gate.await() }
            model.sendCheck()
            advanceUntilIdle()

            model.release()
            advanceUntilIdle()
            gate.complete(accepted("op-1"))
            advanceUntilIdle()
            // The late result of the abandoned work is discarded, not applied to the new visit.
            assertEquals(SduiPhase.Idle, model.state.value.phase)
            assertEquals(SduiCheckState.Ambiguous("op-1"), model.state.value.check)

            repository.checkResult = { accepted(it) }
            model.load()
            advanceUntilIdle()
            assertEquals(listOf("op-1"), repository.checkIds)
            assertEquals(SduiCheckState.Ambiguous("op-1"), model.state.value.check)

            model.retryCheck()
            advanceUntilIdle()
            assertEquals(listOf("op-1", "op-1"), repository.checkIds)
            assertTrue(model.state.value.check is SduiCheckState.Accepted)
        }

    @Test
    fun releasingMidLoadDiscardsTheLateScreen() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<SduiFetch<SduiScreen>>()
            repository.screenResult = { gate.await() }
            model.load()
            advanceUntilIdle()
            model.release()
            gate.complete(SduiFetch.Ok(screenOf()))
            advanceUntilIdle()
            assertEquals(SduiPhase.Idle, model.state.value.phase)
        }

    // ── release mismatch ────────────────────────────────────────────────

    @Test
    fun releaseMismatchOnFirstSendRequiresReloadAndNeverAutoRetries() =
        runTest(dispatcher) {
            loaded()
            repository.checkResult = { SduiCheckResult.ReleaseMismatch }
            model.sendCheck()
            advanceUntilIdle()
            assertEquals(SduiCheckState.NeedsReload(null), model.state.value.check)

            model.sendCheck()
            model.retryCheck()
            advanceUntilIdle()
            assertEquals(listOf("op-1"), repository.checkIds)

            model.refresh()
            advanceUntilIdle()
            assertEquals(SduiCheckState.Idle, model.state.value.check)
        }

    @Test
    fun releaseMismatchOnRetryKeepsTheUnresolvedId() =
        runTest(dispatcher) {
            loaded()
            model.sendCheck()
            advanceUntilIdle()
            repository.checkResult = { SduiCheckResult.ReleaseMismatch }
            model.retryCheck()
            advanceUntilIdle()
            assertEquals(SduiCheckState.NeedsReload("op-1"), model.state.value.check)

            model.refresh()
            advanceUntilIdle()
            assertEquals(SduiCheckState.Ambiguous("op-1"), model.state.value.check)
            assertEquals(listOf("op-1", "op-1"), repository.checkIds)
        }

    // ── connection / profile changes ────────────────────────────────────

    @Test
    fun switchedConnectionBlocksEveryWrite() =
        runTest(dispatcher) {
            loaded()
            model.sendCheck()
            advanceUntilIdle()
            assertEquals(SduiCheckState.Ambiguous("op-1"), model.state.value.check)

            repository.current = false
            model.retryCheck()
            model.sendCheck()
            advanceUntilIdle()
            assertEquals(listOf("op-1"), repository.checkIds)
            assertEquals(SduiPhase.Unavailable(SduiUnavailable.CONNECTION_CHANGED), model.state.value.phase)
            // Terminal for this host: loading again does nothing either.
            model.load()
            model.refresh()
            advanceUntilIdle()
            assertEquals(1, repository.manifestCalls)
        }

    @Test
    fun switchedConnectionBeforeFirstSendDoesNotSend() =
        runTest(dispatcher) {
            loaded()
            repository.current = false
            model.sendCheck()
            advanceUntilIdle()
            assertEquals(emptyList<String>(), repository.checkIds)
            assertEquals(SduiCheckState.Idle, model.state.value.check)
        }

    @Test
    fun switchDuringSendDiscardsTheReceiptAndKeepsTheOldIdUnresolved() =
        runTest(dispatcher) {
            loaded()
            val gate = CompletableDeferred<SduiCheckResult>()
            repository.checkResult = { gate.await() }
            model.sendCheck()
            advanceUntilIdle()

            repository.current = false
            gate.complete(accepted("op-1"))
            advanceUntilIdle()
            assertEquals(SduiPhase.Unavailable(SduiUnavailable.CONNECTION_CHANGED), model.state.value.phase)
            assertEquals(SduiCheckState.Ambiguous("op-1"), model.state.value.check)

            model.retryCheck()
            advanceUntilIdle()
            assertEquals(listOf("op-1"), repository.checkIds)
        }

    @Test
    fun onConnectionChangedCancelsInFlightWorkAndDiscardsItsResult() =
        runTest(dispatcher) {
            loaded()
            val gate = CompletableDeferred<SduiCheckResult>()
            repository.checkResult = { gate.await() }
            model.sendCheck()
            advanceUntilIdle()

            model.onConnectionChanged()
            gate.complete(accepted("op-1"))
            advanceUntilIdle()
            assertEquals(SduiPhase.Unavailable(SduiUnavailable.CONNECTION_CHANGED), model.state.value.phase)
            assertEquals(SduiCheckState.Ambiguous("op-1"), model.state.value.check)
        }

    @Test
    fun switchDuringLoadDropsTheScreen() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<SduiFetch<SduiScreen>>()
            repository.screenResult = { gate.await() }
            model.load()
            advanceUntilIdle()
            repository.current = false
            gate.complete(SduiFetch.Ok(screenOf()))
            advanceUntilIdle()
            assertEquals(SduiPhase.Unavailable(SduiUnavailable.CONNECTION_CHANGED), model.state.value.phase)
        }

    @Test
    fun reopeningAfterSwitchingBackStartsFresh() =
        runTest(dispatcher) {
            loaded()
            repository.current = false
            model.sendCheck()
            advanceUntilIdle()
            model.release()
            repository.current = true
            model.load()
            advanceUntilIdle()
            assertTrue(model.state.value.phase is SduiPhase.Ready)
        }

    // ── auth / 404 from the write ───────────────────────────────────────

    @Test
    fun authAndMissingEndpointFromTheWriteShowUnavailable() =
        runTest(dispatcher) {
            loaded()
            repository.checkResult = { SduiCheckResult.Unauthorized }
            model.sendCheck()
            advanceUntilIdle()
            assertEquals(SduiPhase.Unavailable(SduiUnavailable.AUTH_REQUIRED), model.state.value.phase)
            assertEquals(SduiCheckState.Idle, model.state.value.check)

            model.release()
            loaded()
            repository.checkResult = { SduiCheckResult.NotFound }
            model.sendCheck()
            advanceUntilIdle()
            assertEquals(SduiPhase.Unavailable(SduiUnavailable.HOST_UNSUPPORTED), model.state.value.phase)
        }

    private companion object {
        const val COMPANY = "company-1"

        fun manifestOf(permissions: Set<String> = setOf("mypage.read", "mypage.check")) =
            SduiManifest("career.mypage", 1, 1, "bundled-1", permissions, "company")

        fun screenOf() =
            SduiScreen(
                appId = "career.mypage",
                protocolVersion = 1,
                componentVersion = 1,
                releaseId = "bundled-1",
                companyId = COMPANY,
                profile = "default",
                title = "マイページ",
                state = "ready",
                components = listOf(SduiText("本文")),
            )
    }
}
