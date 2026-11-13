package com.m57.hermescontrol.domain.hermes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttentionRepositoryTest {
    private val scope = ConnectionScope("server", "connection", "profile", "process", 1)
    private val ref = RequestRef(scope, SessionRef("runtime", "saved"), "request")
    private val repository = AttentionRepository()

    private fun observe(
        target: RequestRef = ref,
        expiry: Long? = null,
    ) {
        repository.observe(AttentionItem(target, AttentionKind.APPROVAL, "Synthetic operation", expiry))
    }

    private fun status(target: RequestRef = ref) =
        repository.items.value
            .single { it.ref == target }
            .status

    @Test
    fun round1AuthoritativeStringLockSurvivesThreeGenerations() =
        runTest {
            assertThreeGenerationLock("server answer")
        }

    @Test
    fun round1AuthoritativeNullLockSurvivesThreeGenerations() =
        runTest {
            assertThreeGenerationLock(null)
        }

    private suspend fun assertThreeGenerationLock(answer: String?) {
        val first = ref.copy(questionId = "qid", serverRequestId = "outer")
        val item = AttentionItem(first, AttentionKind.CLARIFICATION, "Synthetic question")
        repository.observe(item)
        var sends = 0
        repository.respond(first, first.scope, true, 0) {
            sends++
            SubmissionResult.Unknown
        }
        val second = first.copy(scope = scope.copy(generation = 2))
        repository.observe(item.copy(ref = second, clarificationLock = ClarificationLock(answer)))
        val third = first.copy(scope = scope.copy(generation = 3))
        val replay = repository.observe(item.copy(ref = third))
        assertEquals(ClarificationLock(answer), replay.clarificationLock)
        assertEquals(AttentionStatus.CONFIRMED, replay.status)
        assertEquals(false, repository.actionable(third, third.scope))
        for (target in listOf(first, second, third)) {
            repository.respond(target, target.scope, true, 0) {
                sends++
                SubmissionResult.Unknown
            }
        }
        assertEquals(1, sends)
        assertEquals(AttentionStatus.UNKNOWN, status(first))
        val separateOuter = third.copy(serverRequestId = "different-outer")
        val separateQid = third.copy(questionId = "different-qid")
        assertEquals(null, repository.observe(item.copy(ref = separateOuter)).clarificationLock)
        assertEquals(null, repository.observe(item.copy(ref = separateQid)).clarificationLock)
    }

    @Test
    fun exactCardWaitsForAckAndRejectsDuplicateTap() =
        runTest {
            val other = ref.copy(requestId = "other")
            observe()
            observe(other)
            val ack = CompletableDeferred<SubmissionResult<Boolean>>()
            var calls = 0
            val job =
                launch {
                    repository.respond(ref, scope, true, 0) {
                        calls++
                        ack.await()
                    }
                }
            runCurrent()
            assertEquals(AttentionStatus.SUBMITTING, status())
            assertEquals(AttentionStatus.PENDING, status(other))
            repository.respond(ref, scope, true, 0) {
                calls++
                SubmissionResult.Unknown
            }
            assertEquals(1, calls)
            ack.complete(SubmissionResult.Confirmed("rpc", true))
            job.join()
            assertEquals(AttentionStatus.CONFIRMED, status())
            observe()
            assertEquals(2, repository.items.value.size)
            assertEquals(AttentionStatus.CONFIRMED, status())
        }

    @Test
    fun differentProfilesGenerationProcessSessionAndQuestionCannotTargetOriginal() =
        runTest {
            observe()
            val scopes =
                listOf(
                    scope.copy(serverIdentity = "other"),
                    scope.copy(connectionProfileId = "other"),
                    scope.copy(serverProfileId = "other"),
                    scope.copy(generation = 2),
                    scope.copy(processId = "other"),
                )
            for (other in scopes) {
                repository.respond(ref, other, true, 0) {
                    org.junit.Assert.fail("Must not send")
                    SubmissionResult.Unknown
                }
            }
            for (other in listOf(ref.copy(session = SessionRef("other")), ref.copy(questionId = "q2"))) {
                repository.respond(other, scope, true, 0) {
                    org.junit.Assert.fail("Must not send")
                    SubmissionResult.Unknown
                }
            }
            repository.respond(ref, scope, false, 0) {
                org.junit.Assert.fail("Must not send")
                SubmissionResult.Unknown
            }
            assertEquals(AttentionStatus.PENDING, status())
        }

    @Test
    fun expiredRequestCannotSendAndLateAckCannotReviveIt() =
        runTest {
            observe(expiry = 10)
            repository.respond(ref, scope, true, 10) {
                org.junit.Assert.fail("Must not send")
                SubmissionResult.Unknown
            }
            assertEquals(AttentionStatus.EXPIRED, status())
            val other = ref.copy(requestId = "second")
            observe(other)
            val ack = CompletableDeferred<SubmissionResult<Boolean>>()
            val job = launch { repository.respond(other, scope, true, 0) { ack.await() } }
            runCurrent()
            repository.expire(other)
            ack.complete(SubmissionResult.Confirmed("rpc", true))
            job.join()
            assertEquals(AttentionStatus.EXPIRED, status(other))
        }

    @Test
    fun resolvedBeforeAckConfirmsAllQuestionsWithoutChangingOtherRequests() =
        runTest {
            val first = ref.copy(questionId = "q0")
            val second = ref.copy(questionId = "q1")
            val other = first.copy(session = SessionRef("other"))
            listOf(first, second, other).forEach {
                repository.observe(AttentionItem(it, AttentionKind.CLARIFICATION, "Question"))
            }
            val ack = CompletableDeferred<SubmissionResult<Boolean>>()
            val job = launch { repository.respond(first, scope, true, 0) { ack.await() } }
            runCurrent()
            assertEquals(AttentionStatus.SUBMITTING, status(first))
            repository.settleClarification(scope, "runtime", "request", resolved = true)
            ack.complete(SubmissionResult.Expired)
            job.join()
            assertEquals(AttentionStatus.CONFIRMED, status(first))
            assertEquals(AttentionStatus.CONFIRMED, status(second))
            assertEquals(AttentionStatus.PENDING, status(other))
        }

    @Test
    fun expiryAckExpiresUnansweredQuestionsAndPreservesLockedAnswers() =
        runTest {
            val locked = ref.copy(questionId = "q0")
            val sent = ref.copy(questionId = "q1")
            val unanswered = ref.copy(questionId = "q2")
            listOf(locked, sent, unanswered).forEach {
                repository.observe(AttentionItem(it, AttentionKind.CLARIFICATION, "Question"))
            }
            repository.respond(locked, scope, true, 0) { SubmissionResult.Confirmed("rpc", true) }
            repository.respond(sent, scope, true, 0) { SubmissionResult.Expired }
            assertEquals(AttentionStatus.CONFIRMED, status(locked))
            assertEquals(AttentionStatus.EXPIRED, status(sent))
            assertEquals(AttentionStatus.EXPIRED, status(unanswered))
        }

    @Test
    fun replayedLocksConfirmFreshAndPreviouslyObservedQuestions() =
        runTest {
            val first = ref.copy(questionId = "q0")
            val second = ref.copy(questionId = "q1")
            val item = AttentionItem(first, AttentionKind.CLARIFICATION, "Question", details = AttentionDetails())
            repository.observe(item)
            repository.respond(first, scope, true, 0) { SubmissionResult.Unknown }
            val replay =
                item.copy(
                    status = AttentionStatus.CONFIRMED,
                    details = AttentionDetails(lockedAnswer = "Blue"),
                )
            repository.observe(replay)
            repository.observe(replay.copy(ref = second))
            repository.observe(item)
            for (question in listOf(first, second)) {
                assertEquals(AttentionStatus.CONFIRMED, status(question))
                assertEquals(
                    "Blue",
                    repository.items.value
                        .single { it.ref == question }
                        .details
                        ?.lockedAnswer,
                )
                repository.respond(question, scope, true, 0) { error("Locked answer must never be sent again") }
            }
        }

    @Test
    fun zeroResolvedAndTimeoutStayUnknownEvenAfterReplay() =
        runTest {
            for ((index, result) in listOf(
                SubmissionResult.Confirmed("rpc", false),
                SubmissionResult.Unknown,
            ).withIndex()) {
                val target = ref.copy(requestId = "request-$index")
                observe(target)
                repository.respond(target, scope, true, 0) { result }
                observe(target)
                var repeated = false
                repository.respond(target, scope, true, 0) {
                    repeated = true
                    result
                }
                assertEquals(false, repeated)
                assertEquals(AttentionStatus.UNKNOWN, status(target))
            }
        }

    @Test
    fun rejectedBeforeSendRequiresAnotherExplicitTap() =
        runTest {
            observe()
            repository.respond(ref, scope, true, 0) { SubmissionResult.NotSent }
            assertEquals(AttentionStatus.PENDING, status())
            repository.respond(ref, scope, true, 0) { SubmissionResult.Confirmed("rpc", true) }
            assertEquals(AttentionStatus.CONFIRMED, status())
        }

    @Test
    fun cancellationAfterBeginningIsUnknownAndPropagates() =
        runTest {
            observe()
            val job =
                launch {
                    repository.respond(ref, scope, true, 0) { CompletableDeferred<SubmissionResult<Boolean>>().await() }
                }
            runCurrent()
            job.cancel()
            job.join()
            assertEquals(true, job.isCancelled)
            assertEquals(AttentionStatus.UNKNOWN, status())
        }

    @Test
    fun unansweredRequestNeedsAuthoritativeReplayAfterGapBeforeItCanBeAnswered() =
        runTest {
            observe()
            repository.markScopeUnknown(scope)
            var calls = 0
            repository.respond(ref, scope, true, 0) {
                calls++
                SubmissionResult.Confirmed("rpc", true)
            }
            assertEquals(0, calls)
            observe()
            assertEquals(AttentionStatus.PENDING, status())
            repository.respond(ref, scope, true, 0) {
                calls++
                SubmissionResult.Confirmed("rpc", true)
            }
            assertEquals(1, calls)
        }

    @Test
    fun unansweredReconnectCanBeAnsweredButAttemptOnAnyGenerationCannotBeReplayed() =
        runTest {
            observe()
            repository.markScopeUnknown(scope)
            val replay = ref.copy(scope = scope.copy(generation = 2))
            observe(replay)
            assertEquals(AttentionStatus.PENDING, status(replay))
            repository.respond(replay, replay.scope, true, 0) { SubmissionResult.Unknown }
            // Even a later replay of the original generation must find the attempted response.
            observe()
            assertEquals(AttentionStatus.UNKNOWN, status())
            val third = ref.copy(scope = scope.copy(generation = 3))
            observe(third)
            assertEquals(AttentionStatus.UNKNOWN, status(third))
        }

    @Test
    fun definitelyNotSentBeforeGapCanRecoverButExpiredRequestCannot() =
        runTest {
            observe()
            repository.respond(ref, scope, true, 0) {
                repository.markScopeUnknown(scope)
                SubmissionResult.NotSent
            }
            observe()
            assertEquals(AttentionStatus.PENDING, status())
            repository.expire(ref)
            observe()
            val replay = ref.copy(scope = scope.copy(generation = 2))
            observe(replay)
            assertEquals(AttentionStatus.EXPIRED, status())
            assertEquals(AttentionStatus.EXPIRED, status(replay))
        }

    @Test
    fun unansweredCopyCannotHideAnAttemptOnAnotherGeneration() =
        runTest {
            observe()
            val second = ref.copy(scope = scope.copy(generation = 2))
            observe(second)
            repository.respond(second, second.scope, true, 0) { SubmissionResult.Unknown }
            val third = ref.copy(scope = scope.copy(generation = 3))
            observe(third)
            assertEquals(AttentionStatus.UNKNOWN, status(third))
            repository.respond(third, third.scope, true, 0) {
                org.junit.Assert.fail("An attempted request cannot be sent again")
                SubmissionResult.Unknown
            }
        }
}
