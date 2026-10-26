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

/**
 * The unresolved write across refresh, a different release, and process death. "Process death" is a
 * new [Host]: only [FakePendingDisk] is carried over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SduiPendingCheckTest {
    private val dispatcher = StandardTestDispatcher()
    private val disk = FakePendingDisk()
    private var nextId = 0

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private class FakeRepository(
        private val events: MutableList<String>,
    ) : SduiRepository {
        var current = true
        var release = "bundled-1"
        var checkResult: suspend (String, String) -> SduiCheckResult = { _, _ -> SduiCheckResult.Ambiguous }

        /** (operation id, release named by the request) of every request that left the host. */
        val sent = mutableListOf<Pair<String, String>>()

        override fun isCurrent() = current

        override suspend fun manifest(): SduiFetch<SduiManifest> = SduiFetch.Ok(manifestOf(release))

        override suspend fun screen(
            manifest: SduiManifest,
            companyId: String,
        ): SduiFetch<SduiScreen> = SduiFetch.Ok(screenOf(release, companyId))

        override suspend fun check(
            screen: SduiScreen,
            operationId: String,
            releaseId: String,
        ): SduiCheckResult {
            events += "check:$operationId"
            sent += operationId to releaseId
            return checkResult(operationId, releaseId)
        }
    }

    private inner class Host(
        scope: String = SCOPE,
        companyId: String = COMPANY,
    ) {
        val repository = FakeRepository(disk.events)
        val model = SduiHostViewModel(repository, companyId, disk.store(scope, companyId)) { "op-${++nextId}" }
        val check get() = model.state.value.check
        val phase get() = model.state.value.phase
        val screenRelease get() = (phase as SduiPhase.Ready).screen.releaseId
    }

    private fun TestScope.host(
        scope: String = SCOPE,
        companyId: String = COMPANY,
    ): Host =
        Host(scope, companyId).also {
            it.model.load()
            advanceUntilIdle()
        }

    private fun accepted(
        operationId: String,
        releaseId: String,
    ) = SduiCheckResult.Accepted(
        SduiReceipt(releaseId, COMPANY, "default", operationId, SduiReceiptState.QUEUED, "run-1"),
    )

    private fun TestScope.sendOnce(host: Host) {
        host.model.sendCheck()
        advanceUntilIdle()
    }

    private val stored get() = disk.records[disk.key(SCOPE, COMPANY)]

    // ── committed before the request ────────────────────────────────────

    @Test
    fun theRecordIsDurableBeforeTheFirstRequestIsSent() =
        runTest(dispatcher) {
            val host = host()
            sendOnce(host)
            assertEquals(listOf("commit:op-1", "check:op-1"), disk.events)
            assertEquals(SduiPendingRead.Found(SduiPendingCheck("op-1", "bundled-1")), stored)
        }

    @Test
    fun aRecordThatCannotBeWrittenMeansNothingIsSent() =
        runTest(dispatcher) {
            val host = host()
            disk.failCommit = true
            sendOnce(host)
            assertEquals(emptyList<Pair<String, String>>(), host.repository.sent)
            assertEquals(SduiCheckState.Refused, host.check)
            assertEquals(null, stored)

            // Nothing is pending, so a new operation is allowed once the disk works again.
            disk.failCommit = false
            sendOnce(host)
            assertEquals(listOf("op-2" to "bundled-1"), host.repository.sent)
        }

    @Test
    fun aRetryWhoseRecordCannotBeWrittenIsNotSentAndStaysAmbiguous() =
        runTest(dispatcher) {
            val host = host()
            sendOnce(host)
            disk.failCommit = true
            host.model.retryCheck()
            advanceUntilIdle()
            assertEquals(listOf("op-1" to "bundled-1"), host.repository.sent)
            assertEquals(SduiCheckState.Ambiguous("op-1"), host.check)
        }

    // ── original release ────────────────────────────────────────────────

    @Test
    fun retryAfterRefreshingOnANewReleaseKeepsTheOriginalReleaseAndId() =
        runTest(dispatcher) {
            val host = host()
            sendOnce(host)
            assertEquals(SduiCheckState.Ambiguous("op-1"), host.check)

            host.repository.release = "bundled-2"
            host.model.refresh()
            advanceUntilIdle()
            assertEquals("bundled-2", host.screenRelease)
            assertEquals(SduiCheckState.Ambiguous("op-1"), host.check)

            // A new operation on the new release is still not possible.
            host.model.sendCheck()
            advanceUntilIdle()
            assertEquals(1, host.repository.sent.size)

            host.repository.checkResult = { op, release -> accepted(op, release) }
            host.model.retryCheck()
            advanceUntilIdle()
            assertEquals(listOf("op-1" to "bundled-1", "op-1" to "bundled-1"), host.repository.sent)
            assertEquals("bundled-1", (host.check as SduiCheckState.Accepted).receipt.releaseId)
        }

    @Test
    fun retryAfterProcessDeathOnANewReleaseSendsTheOriginalRelease() =
        runTest(dispatcher) {
            sendOnce(host())

            val revived = Host()
            revived.repository.release = "bundled-2"
            revived.model.load()
            advanceUntilIdle()
            assertEquals("bundled-2", revived.screenRelease)
            assertEquals(SduiCheckState.Ambiguous("op-1"), revived.check)

            revived.model.retryCheck()
            advanceUntilIdle()
            assertEquals(listOf("op-1" to "bundled-1"), revived.repository.sent)
        }

    // ── a retry never settles anything by being refused ─────────────────

    @Test
    fun aRefusedRetryKeepsTheAmbiguity() =
        runTest(dispatcher) {
            val answers =
                listOf(
                    SduiCheckResult.Refused,
                    SduiCheckResult.OperationConflict,
                    SduiCheckResult.Unauthorized,
                    SduiCheckResult.NotFound,
                    SduiCheckResult.NotSent,
                )
            for (answer in answers) {
                disk.records.clear()
                val host = host()
                sendOnce(host)
                host.repository.checkResult = { _, _ -> answer }
                host.model.retryCheck()
                advanceUntilIdle()
                val id = host.sentId()
                assertEquals(answer.toString(), SduiCheckState.Ambiguous(id), host.check)
                assertEquals(answer.toString(), SduiPendingRead.Found(SduiPendingCheck(id, "bundled-1")), stored)
            }
        }

    @Test
    fun aReleaseMismatchOnRetryKeepsTheAmbiguityThroughRefresh() =
        runTest(dispatcher) {
            val host = host()
            sendOnce(host)
            host.repository.checkResult = { _, _ -> SduiCheckResult.ReleaseMismatch }
            host.model.retryCheck()
            advanceUntilIdle()
            assertEquals(SduiCheckState.NeedsReload("op-1"), host.check)
            assertTrue(stored is SduiPendingRead.Found)

            host.repository.release = "bundled-2"
            host.model.refresh()
            advanceUntilIdle()
            assertEquals(SduiCheckState.Ambiguous("op-1"), host.check)

            host.model.retryCheck()
            advanceUntilIdle()
            assertEquals(
                "bundled-1",
                host.repository.sent
                    .last()
                    .second,
            )
        }

    // ── what does settle it ─────────────────────────────────────────────

    @Test
    fun anAcceptedReceiptClearsTheRecord() =
        runTest(dispatcher) {
            val host = host()
            sendOnce(host)
            host.repository.checkResult = { op, release -> accepted(op, release) }
            host.model.retryCheck()
            advanceUntilIdle()
            assertTrue(host.check is SduiCheckState.Accepted)
            assertEquals(null, stored)

            // And a later process starts clean.
            val revived = host()
            assertEquals(SduiCheckState.Idle, revived.check)
        }

    @Test
    fun aKnownFirstRequestRefusalClearsTheRecordAndAllowsANewOperation() =
        runTest(dispatcher) {
            val refusals =
                listOf(
                    SduiCheckResult.Refused,
                    SduiCheckResult.ReleaseMismatch,
                    SduiCheckResult.OperationConflict,
                    SduiCheckResult.Unauthorized,
                    SduiCheckResult.NotFound,
                    SduiCheckResult.NotSent,
                )
            for (refusal in refusals) {
                disk.records.clear()
                val host = host()
                host.repository.checkResult = { _, _ -> refusal }
                sendOnce(host)
                assertEquals(refusal.toString(), null, stored)
                val settled = host.check
                assertTrue(refusal.toString() + " -> " + settled, settled != SduiCheckState.Ambiguous("op-$nextId"))

                val revived = host()
                assertEquals(refusal.toString(), SduiCheckState.Idle, revived.check)
            }
        }

    @Test
    fun aFirstRefusalThatCannotBeErasedStaysAmbiguous() =
        runTest(dispatcher) {
            val host = host()
            host.repository.checkResult = { _, _ -> SduiCheckResult.Refused }
            disk.failClear = true
            sendOnce(host)
            assertEquals(SduiCheckState.Ambiguous("op-1"), host.check)
            assertTrue(stored is SduiPendingRead.Found)
        }

    @Test
    fun anAmbiguousFirstResultKeepsTheRecord() =
        runTest(dispatcher) {
            val host = host()
            sendOnce(host)
            assertEquals(SduiPendingRead.Found(SduiPendingCheck("op-1", "bundled-1")), stored)
        }

    // ── kept when the work is dropped ───────────────────────────────────

    @Test
    fun aCancelledSendScreenDisposeOrScopeSwitchKeepsTheRecord() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<SduiCheckResult>()
            val dropped: List<(Host) -> Unit> =
                listOf(
                    { it.model.release() },
                    { it.model.onConnectionChanged() },
                    {
                        // The receipt of a request sent on the old connection arrives after the switch.
                        it.repository.current = false
                        gate.complete(accepted(it.sentId(), "bundled-1"))
                    },
                )
            for (drop in dropped) {
                disk.records.clear()
                val host = host()
                host.repository.checkResult = { _, _ -> gate.await() }
                sendOnce(host)
                drop(host)
                advanceUntilIdle()
                assertTrue(stored is SduiPendingRead.Found)

                val revived = host()
                assertEquals(SduiCheckState.Ambiguous(host.sentId()), revived.check)
            }
            gate.cancel()
        }

    // ── restoring ───────────────────────────────────────────────────────

    @Test
    fun aRestoredRequestIsAmbiguousAndNeverSentByItself() =
        runTest(dispatcher) {
            sendOnce(host())
            val revived = host()
            assertTrue(revived.phase is SduiPhase.Ready)
            assertEquals(SduiCheckState.Ambiguous("op-1"), revived.check)

            revived.model.refresh()
            revived.model.release()
            revived.model.load()
            revived.model.sendCheck()
            advanceUntilIdle()
            assertEquals(emptyList<Pair<String, String>>(), revived.repository.sent)
            assertEquals(SduiCheckState.Ambiguous("op-1"), revived.check)

            revived.repository.checkResult = { op, release -> accepted(op, release) }
            revived.model.retryCheck()
            advanceUntilIdle()
            assertEquals(listOf("op-1" to "bundled-1"), revived.repository.sent)
            assertTrue(revived.check is SduiCheckState.Accepted)
            assertEquals(null, stored)
        }

    @Test
    fun aDifferentHostProfileOrCompanyDoesNotRecoverIt() =
        runTest(dispatcher) {
            sendOnce(host())
            val others =
                listOf(
                    host(companyId = "company-2"),
                    host(scope = "https://hermes.test\nselected\nprofile-b"),
                    host(scope = "https://other.test\nselected\nprofile-a"),
                    host(scope = "https://hermes.test\nother-selected\nprofile-a"),
                )
            others.forEach { other ->
                assertEquals(SduiCheckState.Idle, other.check)
                other.model.retryCheck()
                advanceUntilIdle()
                assertEquals(emptyList<Pair<String, String>>(), other.repository.sent)
            }
            assertEquals(SduiCheckState.Ambiguous("op-1"), host().check)
        }

    @Test
    fun aRecordWithoutItsReleaseBlocksInsteadOfBecomingANewWrite() =
        runTest(dispatcher) {
            disk.records[disk.key(SCOPE, COMPANY)] = SduiPendingRead.Invalid
            val host = host()
            assertTrue(host.phase is SduiPhase.Ready)
            assertEquals(SduiCheckState.Blocked, host.check)

            host.model.sendCheck()
            host.model.retryCheck()
            advanceUntilIdle()
            assertEquals(emptyList<Pair<String, String>>(), host.repository.sent)
            assertEquals(emptyList<String>(), disk.events)
            assertEquals(SduiCheckState.Blocked, host.check)

            // Still blocked after a refresh, because the record is still unusable.
            host.model.refresh()
            advanceUntilIdle()
            assertEquals(SduiCheckState.Blocked, host.check)
        }

    @Test
    fun anUnreadableRecordBlocksUntilItCanBeReadAgain() =
        runTest(dispatcher) {
            sendOnce(host())
            disk.failRead = true
            val host = host()
            assertEquals(SduiCheckState.Blocked, host.check)
            host.model.sendCheck()
            advanceUntilIdle()
            assertEquals(emptyList<Pair<String, String>>(), host.repository.sent)

            disk.failRead = false
            host.model.refresh()
            advanceUntilIdle()
            assertEquals(SduiCheckState.Ambiguous("op-1"), host.check)
        }

    @Test
    fun aRecoveredCleanDiskUnblocks() =
        runTest(dispatcher) {
            disk.failRead = true
            val host = host()
            assertEquals(SduiCheckState.Blocked, host.check)
            disk.failRead = false
            host.model.refresh()
            advanceUntilIdle()
            assertEquals(SduiCheckState.Idle, host.check)
        }

    @Test
    fun anAcceptedReceiptThatCouldNotBeErasedIsRecoveredAsTheSameRequest() =
        runTest(dispatcher) {
            val host = host()
            host.repository.checkResult = { op, release -> accepted(op, release) }
            disk.failClear = true
            sendOnce(host)
            assertTrue(host.check is SduiCheckState.Accepted)

            disk.failClear = false
            val revived = host()
            assertEquals(SduiCheckState.Ambiguous("op-1"), revived.check)
            revived.repository.checkResult = { op, release -> accepted(op, release) }
            revived.model.retryCheck()
            advanceUntilIdle()
            assertTrue(revived.check is SduiCheckState.Accepted)
            assertEquals(null, stored)
        }

    private fun Host.sentId(): String = repository.sent.first().first

    private companion object {
        const val SCOPE = "https://hermes.test\nselected\nprofile-a"
        const val COMPANY = "company-1"

        fun manifestOf(release: String) =
            SduiManifest("career.mypage", 1, 1, release, setOf("mypage.read", "mypage.check"), "company")

        fun screenOf(
            release: String,
            companyId: String,
        ) = SduiScreen(
            appId = "career.mypage",
            protocolVersion = 1,
            componentVersion = 1,
            releaseId = release,
            companyId = companyId,
            profile = "default",
            title = "マイページ",
            state = "ready",
            components = listOf(SduiText("本文")),
        )
    }
}
