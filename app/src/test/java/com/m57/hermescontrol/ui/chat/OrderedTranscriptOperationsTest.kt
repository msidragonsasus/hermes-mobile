package com.m57.hermescontrol.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedTranscriptOperationsTest {
    private fun owner(epoch: Long = 1) = TranscriptOwner("target", "session", epoch)

    @Test fun tailReplacementDoesNotCrossBarriersOwnerIdentityOrGeneration() =
        runTest {
            val queue = OrderedTranscriptOperations(backgroundScope)
            val a = owner()
            val sameStringsDifferentOwner = owner()
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<String>()
            val active =
                queue.checkpoint(a, 1) {
                    release.await()
                    writes.add("active")
                    true
                }
            runCurrent()
            val replaced =
                queue.checkpoint(a, 1) {
                    writes.add("replaced")
                    true
                }
            val latest =
                queue.checkpoint(a, 1) {
                    writes.add("latest")
                    true
                }
            val barrier =
                queue.strict {
                    writes.add("barrier")
                    true
                }
            val nextSegment =
                queue.checkpoint(a, 1) {
                    writes.add("nextSegment")
                    true
                }
            val nextGeneration =
                queue.checkpoint(a, 2) {
                    writes.add("nextGeneration")
                    true
                }
            val nextOwner =
                queue.checkpoint(sameStringsDifferentOwner, 2) {
                    writes.add("nextOwner")
                    true
                }
            val claimB = queue.strict { writes.add("claimB") }
            val claimA = queue.strict { writes.add("claimA") }
            assertFalse(replaced.await())
            assertEquals(7, queue.pendingCount)
            assertFalse(active.isCompleted)
            release.complete(Unit)
            runCurrent()
            assertEquals(
                listOf("active", "latest", "barrier", "nextSegment", "nextGeneration", "nextOwner", "claimB", "claimA"),
                writes,
            )
            listOf(active, latest, barrier, nextSegment, nextGeneration, nextOwner).forEach { assertTrue(it.await()) }
            assertTrue(claimB.isCompleted && claimA.isCompleted)
            assertEquals(0, queue.pendingCount)
            queue.close()
        }

    @Test fun policyTwoRetainsStrictBacklogAndOneCheckpointPerAdjacentSegmentNotGlobalBound() =
        runTest {
            val queue = OrderedTranscriptOperations(backgroundScope)
            val owner = owner()
            val gate = CompletableDeferred<Unit>()
            queue.strict { gate.await() }
            runCurrent()
            val producerJobs = backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.children.count()
            val barriers =
                (1..40).map { index ->
                    repeat(100) { queue.checkpoint(owner, 1) { true } }
                    queue.strict { index }
                }
            // Policy 2: 40 strict barriers plus 40 sealed checkpoint segments, not O(1).
            assertEquals(80, queue.pendingCount)
            assertEquals(
                "Checkpoint admission must not spawn suspended producer jobs",
                producerJobs,
                backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.children.count(),
            )
            assertTrue(barriers.none { it.isCompleted })
            gate.complete(Unit)
            runCurrent()
            assertEquals((1..40).toList(), barriers.map { it.await() })
            assertEquals(0, queue.pendingCount)
            queue.close()
        }

    @Test fun failureSettlesExactlyOneOperationAndDoesNotPoisonLaterRetry() =
        runTest {
            val queue = OrderedTranscriptOperations(backgroundScope)
            val failed = queue.strict<Boolean> { error("storage failure") }
            val rejected = queue.strict { false }
            val retry = queue.checkpoint(owner(), 1) { true }
            runCurrent()
            assertTrue(failed.isCompleted)
            assertEquals("storage failure", failed.getCompletionExceptionOrNull()?.message)
            assertFalse(rejected.await())
            assertTrue(retry.await())
            queue.close()
        }

    @Test fun canceledScopeBeforeWorkerStartsSettlesAdmissionAndRevokesOwner() =
        runTest {
            val job = kotlinx.coroutines.Job()
            val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + job)
            val queue = OrderedTranscriptOperations(scope)
            val owner = owner()
            val pending = queue.checkpoint(owner, 1) { true }
            job.cancel()
            runCurrent()
            assertTrue("Even an unstarted consumer must settle its mailbox", pending.isCompleted)
            assertFalse(owner.valid)
            assertEquals(0, queue.pendingCount)
            assertTrue(queue.strict { true }.isCompleted)
        }

    @Test fun closeSettlesActivePendingAndClaimAndRevokesDetachedWriteOwners() =
        runTest {
            val queue = OrderedTranscriptOperations(backgroundScope)
            val detached = owner()
            val other = owner(2)
            val gate = CompletableDeferred<Unit>()
            val active =
                queue.checkpoint(detached, 1) {
                    gate.await()
                    true
                }
            runCurrent()
            val pending = queue.checkpoint(other, 2) { true }
            val claim = queue.strict { error("must not run") }
            queue.close()
            assertTrue(active.isCompleted && pending.isCompleted && claim.isCompleted)
            assertFalse("Detached active owner must be revoked on shutdown", detached.valid)
            assertFalse("Pending owner must be revoked on shutdown", other.valid)
            assertEquals(0, queue.pendingCount)
            assertTrue(queue.strict { true }.isCompleted)
            runCurrent()
        }
}
