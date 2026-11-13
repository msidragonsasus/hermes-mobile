package com.m57.hermescontrol.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Transfers a newly claimed owner from IO to Main, or revokes it on either side of cancellation. */
internal class TranscriptOwnerLease {
    private var owner: TranscriptOwner? = null
    private var abandoned = false
    private var transferred = false

    @Synchronized fun capture(created: TranscriptOwner) {
        if (abandoned) created.valid = false else owner = created
    }

    @Synchronized fun transfer() {
        transferred = true
        owner = null
    }

    @Synchronized fun abandon() {
        if (transferred) return
        abandoned = true
        owner?.valid = false
        owner = null
    }
}

/**
 * Main-confined admission and one consumer. Only an adjacent pending checkpoint with the
 * identical owner and generation is replaceable. Strict backlog is deliberately NOT bounded.
 * The wakeup contains no snapshot; replacement retains neither producers nor previous waiters.
 */
internal class OrderedTranscriptOperations(
    scope: CoroutineScope,
) {
    private class Operation<T>(
        val owner: TranscriptOwner?,
        val generation: Long?,
        val replaceable: Boolean,
        val result: CompletableDeferred<T>,
        val action: suspend () -> T,
        val replaced: () -> Unit,
    ) {
        suspend fun run() {
            try {
                result.complete(action())
            } catch (e: Exception) {
                result.completeExceptionally(e)
                if (e is CancellationException) {
                    owner?.valid = false
                    throw e
                }
            }
        }

        fun cancel() {
            owner?.valid = false
            result.cancel()
        }
    }

    private val pending = ArrayDeque<Operation<*>>()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private var active: Operation<*>? = null
    private var closed = false
    internal val pendingCount: Int get() = pending.size
    private val worker =
        scope.launch {
            try {
                for (signal in wakeup) {
                    while (pending.isNotEmpty()) {
                        val operation = pending.removeFirst()
                        active = operation
                        try {
                            operation.run()
                        } finally {
                            active = null
                        }
                    }
                }
            } finally {
                close()
            }
        }

    init {
        // A canceled scope can prevent the coroutine body (and its finally) from ever starting.
        worker.invokeOnCompletion { close() }
    }

    fun checkpoint(
        owner: TranscriptOwner,
        generation: Long,
        action: suspend () -> Boolean,
    ): CompletableDeferred<Boolean> {
        val result = CompletableDeferred<Boolean>()
        val tail = pending.lastOrNull()
        if (tail != null && tail.replaceable && tail.owner === owner && tail.generation == generation) {
            pending.removeLast()
            tail.replaced()
        }
        admit(Operation(owner, generation, true, result, action) { result.complete(false) })
        return result
    }

    fun <T> strict(
        owner: TranscriptOwner? = null,
        action: suspend () -> T,
    ): CompletableDeferred<T> {
        val result = CompletableDeferred<T>()
        admit(Operation(owner, null, false, result, action) {})
        return result
    }

    private fun admit(operation: Operation<*>) {
        if (closed) {
            operation.cancel()
        } else {
            pending.addLast(operation)
            wakeup.trySend(Unit)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        active?.cancel()
        pending.forEach { it.cancel() }
        pending.clear()
        wakeup.close()
        worker.cancel()
    }
}
