package com.m57.hermescontrol.openclaw

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal fun clawRetryable(error: Exception): Boolean =
    error is TimeoutCancellationException ||
        (error as? ClawFailure)?.code in setOf("DISCONNECTED", "NOT_SENT", "EVENT_OVERFLOW") ||
        error is java.io.IOException

/** Retry connections and read-side reconciliation only. Never replay chat.send or config writes. */
internal suspend fun maintainClawConnection(
    connect: suspend (onReady: () -> Unit) -> Unit,
    onFailure: (Exception, Boolean) -> Unit,
    wait: suspend (Long) -> Unit = { delay(it) },
) {
    var attempt = 0
    while (currentCoroutineContext().isActive) {
        try {
            connect { attempt = 0 }
            return
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            val retry = clawRetryable(error)
            onFailure(error, retry)
            if (!retry) return
            wait((1_000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L))
            attempt++
        }
    }
}
