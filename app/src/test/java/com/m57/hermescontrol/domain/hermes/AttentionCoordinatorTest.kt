package com.m57.hermescontrol.domain.hermes

import com.m57.hermescontrol.data.transport.HermesAttentionSource
import com.m57.hermescontrol.data.transport.HermesAttentionTransport
import com.m57.hermescontrol.data.transport.HermesSubmissionTransport
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttentionCoordinatorTest {
    private val scope = ConnectionScope("server", "connection", "profile", "process", 1)
    private val ref = RequestRef(scope, SessionRef("runtime"), "request")
    private val repository = AttentionRepository()
    private val signals = MutableSharedFlow<AttentionSignal>()
    private val source =
        object : HermesAttentionSource {
            override val attentionSignals = signals
        }
    private val transport = mockk<HermesAttentionTransport>()
    private val connection = mockk<HermesSubmissionTransport>()
    private var current = scope
    private var ready = true
    private val coordinator = AttentionCoordinator(repository, source, transport, connection) { 100 }

    init {
        every { connection.configuredScope() } answers { current }
        every { connection.isReady(any()) } answers { ready && firstArg<ConnectionScope>() == current }
        coEvery { transport.respondApproval(any(), any()) } returns SubmissionResult.Confirmed("rpc", true)
        coEvery { transport.respondClarification(any(), any()) } returns SubmissionResult.Confirmed("rpc", true)
    }

    private fun approval(target: RequestRef = ref) = AttentionItem(target, AttentionKind.APPROVAL, "Synthetic request")

    @Test
    fun broaderChoicesWaitForAckAndCannotBeRetriedThroughReconnect() =
        runTest {
            val details = AttentionDetails(approvalChoices = listOf("session", "always"))
            val ack = CompletableDeferred<SubmissionResult<Boolean>>()
            coEvery { transport.respondApproval(ref, "session") } coAnswers { ack.await() }
            repository.observe(approval().copy(details = details))
            val response = launch { coordinator.respondApproval(ref, "session") }
            runCurrent()
            assertEquals(
                AttentionStatus.SUBMITTING,
                repository.items.value
                    .single()
                    .status,
            )
            coordinator.respondApproval(ref, "always")
            current = scope.copy(generation = 2)
            val replay = ref.copy(scope = current)
            repository.observe(approval(replay).copy(details = details))
            ack.complete(SubmissionResult.Confirmed("rpc", true))
            response.join()
            coordinator.respondApproval(replay, "always")
            assertEquals(
                AttentionStatus.UNKNOWN,
                repository.items.value
                    .first { it.ref == replay }
                    .status,
            )
            coVerify(exactly = 1) { transport.respondApproval(ref, "session") }
            coVerify(exactly = 1) { transport.respondApproval(any(), any()) }
        }

    @Test
    fun broaderChoicesStillRespectExpiryReadinessAndExactConfiguredScope() =
        runTest {
            val details = AttentionDetails(approvalChoices = listOf("session", "always"))
            repository.observe(approval().copy(details = details, expiresAt = 100))
            coordinator.respondApproval(ref, "always")
            assertEquals(
                AttentionStatus.EXPIRED,
                repository.items.value
                    .single()
                    .status,
            )
            val fresh = ref.copy(requestId = "fresh")
            repository.observe(approval(fresh).copy(details = details))
            ready = false
            coordinator.respondApproval(fresh, "session")
            ready = true
            current = scope.copy(generation = 2)
            coordinator.respondApproval(fresh, "always")
            coVerify(exactly = 0) { transport.respondApproval(any(), any()) }
            current = scope
            coordinator.respondApproval(fresh, "session")
            coVerify(exactly = 1) { transport.respondApproval(fresh, "session") }
        }

    @Test
    fun broaderChoicesRequireExactKnownPerItemAdvertisement() =
        runTest {
            val choices = listOf(null, emptyList(), listOf("deny"), listOf("unknown", "SESSION", " session"))
            for ((index, offered) in choices.withIndex()) {
                val target = ref.copy(requestId = "restricted:$index")
                repository.observe(approval(target).copy(details = AttentionDetails(approvalChoices = offered)))
                for (choice in listOf("session", "always", "unknown", "SESSION", " session")) {
                    coordinator.respondApproval(target, choice)
                }
                if (offered != null) coordinator.respondApproval(target, "once")
            }
            coVerify(exactly = 0) { transport.respondApproval(any(), any()) }
        }

    @Test
    fun reconnectReplayCannotResetAnUnknownResponseToPending() =
        runTest {
            coEvery { transport.respondApproval(ref, "once") } returns SubmissionResult.Unknown
            repository.observe(approval())
            coordinator.respondApproval(ref, "once")
            current = scope.copy(generation = 2)
            val replay = ref.copy(scope = current)
            repository.observe(approval(replay))
            coordinator.respondApproval(replay, "once")
            assertEquals(
                AttentionStatus.UNKNOWN,
                repository.items.value
                    .first { it.ref == replay }
                    .status,
            )
            coVerify(exactly = 1) { transport.respondApproval(any(), any()) }
        }

    @Test
    fun observingOutsideChatDoesNotSendAndExplicitResponseWaitsForAck() =
        runTest {
            val ack = CompletableDeferred<SubmissionResult<Boolean>>()
            coEvery { transport.respondApproval(ref, "once") } coAnswers { ack.await() }
            coordinator.start(backgroundScope)
            runCurrent()
            signals.emit(AttentionSignal.Requested(listOf(approval())))
            runCurrent()
            assertEquals(
                AttentionStatus.PENDING,
                repository.items.value
                    .single()
                    .status,
            )
            coVerify(exactly = 0) { transport.respondApproval(any(), any()) }
            val response = launch { coordinator.respondApproval(ref, "once") }
            runCurrent()
            assertEquals(
                AttentionStatus.SUBMITTING,
                repository.items.value
                    .single()
                    .status,
            )
            signals.emit(AttentionSignal.Requested(listOf(approval())))
            coordinator.respondApproval(ref, "once")
            ack.complete(SubmissionResult.Confirmed("rpc", true))
            response.join()
            assertEquals(
                AttentionStatus.CONFIRMED,
                repository.items.value
                    .single()
                    .status,
            )
            coVerify(exactly = 1) { transport.respondApproval(ref, "once") }
        }

    @Test
    fun disconnectDifferentScopeAndUnadvertisedChoicesDoNotSend() =
        runTest {
            repository.observe(approval().copy(details = AttentionDetails(approvalChoices = listOf("deny"))))
            coordinator.respondApproval(ref, "once")
            coordinator.respondApproval(ref, "always")
            ready = false
            coordinator.respondApproval(ref, "deny")
            ready = true
            current = scope.copy(generation = 2)
            coordinator.respondApproval(ref, "deny")
            current = scope
            coordinator.respondClarification(ref, "wrong kind")
            coVerify(exactly = 0) { transport.respondApproval(any(), any()) }
            coVerify(exactly = 0) { transport.respondClarification(any(), any()) }
            coordinator.respondApproval(ref, "deny")
            coVerify(exactly = 1) { transport.respondApproval(ref, "deny") }
        }

    @Test
    fun gapNeverTurnsReplayOrLateAckIntoPermissionToRespondAgain() =
        runTest {
            val ack = CompletableDeferred<SubmissionResult<Boolean>>()
            coEvery { transport.respondApproval(ref, "once") } coAnswers { ack.await() }
            coordinator.start(backgroundScope)
            runCurrent()
            repository.observe(approval())
            val response = launch { coordinator.respondApproval(ref, "once") }
            runCurrent()
            signals.emit(AttentionSignal.Gap(scope))
            signals.emit(AttentionSignal.Requested(listOf(approval())))
            ack.complete(SubmissionResult.Confirmed("rpc", true))
            response.join()
            assertEquals(
                AttentionStatus.UNKNOWN,
                repository.items.value
                    .single()
                    .status,
            )
            coordinator.respondApproval(ref, "once")
            coVerify(exactly = 1) { transport.respondApproval(ref, "once") }
        }

    @Test
    fun batchQuestionAnswerAndExpiryKeepExactRequestAndQuestionIdentity() =
        runTest {
            val first = ref.copy(questionId = "q1")
            val second = ref.copy(questionId = "q2")
            val other = ref.copy(requestId = "other", questionId = "q1")
            listOf(first, second, other).forEach {
                repository.observe(AttentionItem(it, AttentionKind.CLARIFICATION, "Question"))
            }
            coordinator.start(backgroundScope)
            runCurrent()
            coordinator.respondClarification(second, "  answer  ")
            signals.emit(AttentionSignal.Expired(scope, "runtime", "request"))
            runCurrent()
            assertEquals(
                AttentionStatus.EXPIRED,
                repository.items.value
                    .first { it.ref == first }
                    .status,
            )
            assertEquals(
                AttentionStatus.CONFIRMED,
                repository.items.value
                    .first { it.ref == second }
                    .status,
            )
            assertEquals(
                AttentionStatus.PENDING,
                repository.items.value
                    .first { it.ref == other }
                    .status,
            )
            coordinator.respondClarification(first, "late")
            coVerify(exactly = 1) { transport.respondClarification(second, "answer") }
            coVerify(exactly = 1) { transport.respondClarification(any(), any()) }
        }

    @Test
    fun crossScopeSourceIsIgnoredAndMetadataEnrichmentPreservesLifecycleAndFirstExplanation() =
        runTest {
            coordinator.start(backgroundScope)
            runCurrent()
            signals.emit(
                AttentionSignal.Requested(listOf(approval(ref.copy(scope = scope.copy(serverIdentity = "other"))))),
            )
            runCurrent()
            assertTrue(repository.items.value.isEmpty())
            repository.observe(approval())
            coordinator.respondApproval(ref, "deny")
            repository.observe(approval().copy(details = AttentionDetails(action = "first")))
            repository.observe(approval().copy(title = "changed", details = AttentionDetails(action = "changed")))
            assertEquals(
                AttentionStatus.CONFIRMED,
                repository.items.value
                    .single()
                    .status,
            )
            assertEquals(
                "Synthetic request",
                repository.items.value
                    .single()
                    .title,
            )
            assertEquals(
                "first",
                repository.items.value
                    .single()
                    .details
                    ?.action,
            )
        }
}
