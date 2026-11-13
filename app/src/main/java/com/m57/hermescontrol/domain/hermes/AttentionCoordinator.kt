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
                if (signal is AttentionSignal.Requested) {
                    repository.apply(
                        signal.copy(
                            items =
                                signal.items.filter {
                                    it.ref.scope == connection.configuredScope() && connection.isReady(it.ref.scope)
                                },
                        ),
                    )
                } else if (signal is AttentionSignal.Gap || signalScope(signal) == connection.configuredScope()) {
                    repository.apply(signal)
                }
            }
        }

    private fun signalScope(signal: AttentionSignal): ConnectionScope? =
        when (signal) {
            is AttentionSignal.Requested -> {
                signal.items
                    .firstOrNull()
                    ?.ref
                    ?.scope
            }

            is AttentionSignal.Expired -> {
                signal.scope
            }

            is AttentionSignal.Resolved -> {
                signal.scope
            }

            is AttentionSignal.Cancelled -> {
                signal.scope
            }

            is AttentionSignal.Snapshot -> {
                signal.scope
            }

            is AttentionSignal.Gap -> {
                signal.scope
            }
        }

    fun isReady(ref: RequestRef): Boolean = ref.scope == connection.configuredScope() && connection.isReady(ref.scope)

    suspend fun respondApproval(
        ref: RequestRef,
        choice: String,
    ) {
        val item = repository.items.value.firstOrNull { it.ref == ref && it.kind == AttentionKind.APPROVAL } ?: return
        if (choice !in allowedApprovalChoices(item.details?.approvalChoices)) return
        repository.respond(ref, connection.configuredScope(), isReady(ref), now()) {
            transport.respondApproval(ref, choice)
        }
    }

    suspend fun respondClarification(
        ref: RequestRef,
        answer: String?,
    ) {
        if (answer != null && answer.isBlank()) return
        if (repository.items.value.none { it.ref == ref && it.kind == AttentionKind.CLARIFICATION }) return
        repository.respondClarification(
            ref,
            answer?.trim(),
            connection.configuredScope(),
            isReady(ref),
            now(),
            transport,
        )
    }
}
