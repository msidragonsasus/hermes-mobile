package com.m57.hermescontrol.career

import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * One process-owned worker, independent of the outgoing Compose/lifecycle scope.
 * Pending cleanup is retained rather than rejected or run on the caller/UI thread.
 * The worker exits after being idle; each transport operation is isolated from failure.
 */
private val careerBrowserCleanupExecutor: Executor =
    ThreadPoolExecutor(
        0,
        1,
        30,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        { task -> Thread(task, "career-browser-cleanup").apply { isDaemon = true } },
    )

internal fun scheduleCareerBrowserNetworkCleanup(
    operations: List<() -> Unit>,
    onComplete: () -> Unit = {},
    executor: Executor = careerBrowserCleanupExecutor,
) {
    executor.execute {
        try {
            operations.forEach { operation -> runCatching { operation() } }
        } finally {
            runCatching { onComplete() }
        }
    }
}

/** Both callbacks must run on the UI thread; cookie failure deliberately keeps the lease. */
internal class CareerBrowserLeaseCompletion(
    private val release: () -> Unit,
) {
    private var transportDone = false
    private var cookiesDone = false
    private var released = false

    fun transportFinished() {
        transportDone = true
        releaseIfReady()
    }

    fun cookiesFinished() {
        cookiesDone = true
        releaseIfReady()
    }

    private fun releaseIfReady() {
        if (!released && transportDone && cookiesDone) {
            released = true
            release()
        }
    }
}
