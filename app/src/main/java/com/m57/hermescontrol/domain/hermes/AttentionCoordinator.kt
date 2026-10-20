package com.m57.hermescontrol.domain.hermes

import com.m57.hermescontrol.data.transport.HermesAttentionSource
import com.m57.hermescontrol.data.transport.HermesAttentionTransport
import com.m57.hermescontrol.data.transport.HermesSubmissionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Shared shell/Chat lifecycle. Every send is an explicit action on the original immutable ref. */
class AttentionCoordinator(
    private val repository: AttentionRepository,
    private val source: HermesAttentionSource,
    private val transport: HermesAttentionTransport,
    private val connection: HermesSubmissionTransport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun start(scope: CoroutineScope) =
        scope.launch {
            source.attentionSignals.collect { signal ->
                when (signal) {
                    is AttentionSignal.Gap -> {
                        repository.markScopeUnknown(signal.scope)
                    }

                    is AttentionSignal.Requested -> {
                        signal.items
                            .filter {
                                it.ref.scope == connection.configuredScope() && connection.isReady(it.ref.scope)
                            }.forEach(repository::observe)
                    }

                    is AttentionSignal.Expired -> {
                        repository.items.value
                            .filter {
                                it.ref.scope == signal.scope && it.ref.session.runtimeId == signal.runtimeId &&
                                    it.ref.requestId == signal.requestId && it.kind == AttentionKind.CLARIFICATION
                            }.forEach { repository.expire(it.ref) }
                    }
                }
            }
        }

    fun isReady(ref: RequestRef): Boolean = ref.scope == connection.configuredScope() && connection.isReady(ref.scope)

    suspend fun respondApproval(
        ref: RequestRef,
        choice: String,
    ) {
        if (choice !in setOf("once", "deny")) return
        val item = repository.items.value.firstOrNull { it.ref == ref && it.kind == AttentionKind.APPROVAL } ?: return
        if (item.details?.approvalChoices?.let { choice !in it } == true) return
        repository.respond(ref, connection.configuredScope(), isReady(ref), now()) {
            transport.respondApproval(ref, choice)
        }
    }

    suspend fun respondClarification(
        ref: RequestRef,
        answer: String,
    ) {
        if (answer.isBlank()) return
        if (repository.items.value.none { it.ref == ref && it.kind == AttentionKind.CLARIFICATION }) return
        repository.respond(ref, connection.configuredScope(), isReady(ref), now()) {
            transport.respondClarification(ref, answer.trim())
        }
    }
}
