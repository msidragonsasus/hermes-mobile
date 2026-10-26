package com.m57.hermescontrol.career

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CareerBrowserNetworkCleanupTest {
    @Test
    fun cleanupRunsOffCallerAndCannotDelayUiTeardown() {
        val caller = Thread.currentThread()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val uiTornDown = AtomicBoolean(false)
        scheduleCareerBrowserNetworkCleanup(
            listOf(
                {
                    worker.set(Thread.currentThread())
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    check(uiTornDown.get())
                    finished.countDown()
                },
            ),
        )
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNotEquals(caller, worker.get())
            assertEquals("career-browser-cleanup", worker.get().name)
            assertEquals(1L, finished.count)
            // Mirrors disposal continuing with UI teardown while transport close is pending.
            uiTornDown.set(true)
        } finally {
            release.countDown()
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun throwingCleanupOperationDoesNotSkipLaterStepsOrPoisonWorker() {
        val finished = CountDownLatch(1)
        val steps = mutableListOf<String>()
        scheduleCareerBrowserNetworkCleanup(
            listOf(
                {
                    steps.add("cancel")
                    throw IllegalStateException("synthetic")
                },
                {
                    steps.add("close")
                    throw AssertionError("synthetic")
                },
                {
                    steps.add("evict")
                    throw RuntimeException("synthetic")
                },
                { steps.add("shutdown") },
            ),
        )
        scheduleCareerBrowserNetworkCleanup(
            listOf({
                steps.add("next-session")
                finished.countDown()
            }),
        )
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("cancel", "close", "evict", "shutdown", "next-session"), steps)
    }

    @Test
    fun cleanupWorkerHasBoundedConcurrencyAndRetainsPendingWork() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstDone = AtomicBoolean(false)
        val secondEntered = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        scheduleCareerBrowserNetworkCleanup(
            listOf({
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                firstDone.set(true)
            }),
        )
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            scheduleCareerBrowserNetworkCleanup(
                listOf({
                    check(firstDone.get())
                    secondEntered.set(true)
                    finished.countDown()
                }),
            )
            assertFalse(secondEntered.get())
        } finally {
            release.countDown()
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun completionRunsAfterEveryStepEvenIfCleanupThrows() {
        val finished = CountDownLatch(1)
        val lastStep = AtomicBoolean(false)
        val completeAfterSteps = AtomicBoolean(false)
        scheduleCareerBrowserNetworkCleanup(
            operations =
                listOf(
                    { throw RuntimeException("synthetic") },
                    { lastStep.set(true) },
                ),
            onComplete = {
                completeAfterSteps.set(lastStep.get())
                finished.countDown()
            },
        )
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(completeAfterSteps.get())
    }

    @Test
    fun completionFailureCannotPoisonNextSessionCleanup() {
        val finished = CountDownLatch(1)
        scheduleCareerBrowserNetworkCleanup(emptyList(), onComplete = { throw RuntimeException("synthetic") })
        scheduleCareerBrowserNetworkCleanup(emptyList(), onComplete = { finished.countDown() })
        assertTrue(finished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun leaseReleaseRequiresBothCompletionsInEitherOrderAndHappensOnce() {
        for (cookiesFirst in listOf(true, false)) {
            var releases = 0
            val completion = CareerBrowserLeaseCompletion { releases++ }
            if (cookiesFirst) completion.cookiesFinished() else completion.transportFinished()
            assertEquals(0, releases)
            if (cookiesFirst) completion.transportFinished() else completion.cookiesFinished()
            assertEquals(1, releases)
            completion.cookiesFinished()
            completion.transportFinished()
            assertEquals(1, releases)
        }
    }
}
