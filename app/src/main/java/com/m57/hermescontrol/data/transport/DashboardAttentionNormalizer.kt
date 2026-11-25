package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.data.ws.EventParser
import com.m57.hermescontrol.data.ws.ScopedWsEvent
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.domain.hermes.AttentionDetails
import com.m57.hermescontrol.domain.hermes.AttentionItem
import com.m57.hermescontrol.domain.hermes.AttentionKind
import com.m57.hermescontrol.domain.hermes.AttentionSignal
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.domain.hermes.SessionRef
import com.m57.hermescontrol.domain.hermes.allowedApprovalChoices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal fun normalizeDashboardAttention(events: Flow<ScopedWsEvent>): Flow<AttentionSignal> =
    flow {
        var previous: ScopedWsEvent? = null
        var replayEpoch: String? = null
        events.collect { envelope ->
            val prior = previous
            val epoch = (envelope.event as? WsEvent.GatewayReady)?.data?.get("replay_epoch") as? String
            val epochChanged = !epoch.isNullOrBlank() && replayEpoch != null && replayEpoch != epoch
            if (!epoch.isNullOrBlank()) replayEpoch = epoch
            val streamGap =
                epochChanged ||
                    (prior != null && (prior.scope != envelope.scope || envelope.ordinal != prior.ordinal + 1))
            if (streamGap) {
                emit(AttentionSignal.Gap(prior?.scope ?: envelope.scope))
            }
            if (envelope.gap && (!streamGap || prior?.scope != envelope.scope)) {
                emit(AttentionSignal.Gap(envelope.scope, envelope.gapSessionId))
            }
            previous = envelope
            dashboardAttentionSignal(envelope.scope, envelope.event, envelope.ordinal)?.let { emit(it) }
        }
    }

internal fun dashboardAttentionSignal(
    scope: ConnectionScope,
    event: WsEvent?,
    ordinal: Long = 0,
): AttentionSignal? {
    if (event is WsEvent.ServerRequestCancel) {
        val runtimeId = event.sessionId?.takeIf(String::isNotBlank) ?: return null
        val outer = event.requestId?.takeIf(String::isNotBlank) ?: return null
        return AttentionSignal.Cancelled(scope, runtimeId, outer, event.method, event.reason)
    }
    if (event is WsEvent.OpenRequestsSnapshot) {
        return AttentionSignal.Snapshot(
            scope,
            event.sessionId,
            event.startedAt,
            event.requests.flatMap { dashboardAttentionItems(scope, it) },
            event.requestIds,
        )
    }
    if (event is WsEvent.ClarifyExpire) {
        val runtimeId = event.sessionId?.takeIf(String::isNotBlank) ?: return null
        val requestId = event.clarifyId?.takeIf(String::isNotBlank) ?: return null
        return if (event.serverRequestId != null) {
            AttentionSignal.Cancelled(scope, runtimeId, event.serverRequestId, "clarify", event.reason)
        } else {
            AttentionSignal.Expired(scope, runtimeId, requestId)
        }
    }
    if (event is WsEvent.ClarifyResolved) {
        val runtimeId = event.sessionId?.takeIf(String::isNotBlank) ?: return null
        val requestId = event.clarifyId?.takeIf(String::isNotBlank) ?: return null
        // Withdrawal is not an ACK: only the exact already-sent attempt may confirm later.
        return AttentionSignal.Cancelled(scope, runtimeId, requestId, "clarify", "resolved")
    }
    val items = dashboardAttentionItems(scope, event)
    return items.takeIf { it.isNotEmpty() }?.let { AttentionSignal.Requested(it, ordinal) }
}

/** Also used by legacy Chat replay so it shares exact identity and description with the shell. */
internal fun dashboardAttentionItems(
    scope: ConnectionScope,
    event: WsEvent?,
): List<AttentionItem> =
    when (event) {
        is WsEvent.SessionInfo -> {
            val runtimeId = (event.data?.get("session_id") as? String)?.takeIf(String::isNotBlank)
            val pending = event.data?.get("pending_approval") as? Map<*, *>
            if (runtimeId == null || pending == null) {
                emptyList()
            } else {
                dashboardAttentionItems(scope, EventParser.parseApprovalRequest(pending, runtimeId))
            }
        }

        is WsEvent.ApprovalRequest -> {
            val runtimeId = event.sessionId?.takeIf(String::isNotBlank)
            val requestId = event.requestId?.takeIf(String::isNotBlank)
            if (runtimeId == null || requestId == null) {
                emptyList()
            } else {
                listOf(
                    AttentionItem(
                        RequestRef(
                            scope,
                            SessionRef(runtimeId),
                            requestId,
                            serverRequestId = event.serverRequestId,
                            executionGeneration = event.executionGeneration,
                        ),
                        AttentionKind.APPROVAL,
                        event.description ?: event.command ?: "実行内容は未提供です",
                        details =
                            AttentionDetails(
                                action = event.command,
                                patterns = event.patternKeys.orEmpty(),
                                approvalChoices =
                                    allowedApprovalChoices(
                                        event.choices,
                                        event.allowPermanent,
                                        event.smartDenied,
                                    ),
                            ),
                    ),
                )
            }
        }

        is WsEvent.ClarifyRequest -> {
            val runtimeId = event.sessionId?.takeIf(String::isNotBlank)
            val requestId = event.clarifyId?.takeIf(String::isNotBlank)
            val invalidQuestions =
                event.questionId?.isBlank() == true || event.questions.any { it.qid.isBlank() } ||
                    event.questions
                        .map { it.qid }
                        .distinct()
                        .size != event.questions.size
            if (runtimeId == null || requestId == null || invalidQuestions) {
                emptyList()
            } else {
                val questions =
                    event.questions.ifEmpty {
                        listOf(
                            WsEvent.ClarifyQuestion(
                                event.questionId ?: "q0",
                                event.text.orEmpty(),
                                event.options.orEmpty(),
                                event.multiSelect,
                            ),
                        )
                    }
                questions.map { question ->
                    AttentionItem(
                        RequestRef(
                            scope,
                            SessionRef(runtimeId),
                            requestId,
                            if (event.questions.isEmpty()) event.questionId else question.qid,
                            event.serverRequestId,
                            event.executionGeneration,
                        ),
                        AttentionKind.CLARIFICATION,
                        question.question,
                        status =
                            if (question.qid in event.answers || question.qid in event.lockedAnswers) {
                                AttentionStatus.CONFIRMED
                            } else {
                                AttentionStatus.PENDING
                            },
                        details =
                            AttentionDetails(
                                options = question.choices,
                                multiSelect = question.multiSelect,
                                lockedAnswer = (event.answers + event.lockedAnswers)[question.qid],
                            ),
                        clarificationLock =
                            if (question.qid in event.answers || question.qid in event.lockedAnswers) {
                                com.m57.hermescontrol.domain.hermes
                                    .ClarificationLock((event.answers + event.lockedAnswers)[question.qid])
                            } else {
                                null
                            },
                    )
                }
            }
        }

        else -> {
            emptyList()
        }
    }
