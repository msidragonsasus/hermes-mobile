package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.data.ws.ScopedWsEvent
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.domain.hermes.AttentionDetails
import com.m57.hermescontrol.domain.hermes.AttentionItem
import com.m57.hermescontrol.domain.hermes.AttentionKind
import com.m57.hermescontrol.domain.hermes.AttentionSignal
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.domain.hermes.SessionRef
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
            if (envelope.gap || epochChanged ||
                (prior != null && (prior.scope != envelope.scope || envelope.ordinal != prior.ordinal + 1))
            ) {
                emit(
                    AttentionSignal.Gap(
                        if (prior != null &&
                            prior.scope != envelope.scope
                        ) {
                            prior.scope
                        } else {
                            envelope.scope
                        },
                    ),
                )
            }
            previous = envelope
            dashboardAttentionSignal(envelope.scope, envelope.event)?.let { emit(it) }
        }
    }

internal fun dashboardAttentionSignal(
    scope: ConnectionScope,
    event: WsEvent?,
): AttentionSignal? {
    if (event is WsEvent.ClarifyExpire) {
        val runtimeId = event.sessionId?.takeIf(String::isNotBlank) ?: return null
        val requestId = event.clarifyId?.takeIf(String::isNotBlank) ?: return null
        return AttentionSignal.Expired(scope, runtimeId, requestId)
    }
    val items = dashboardAttentionItems(scope, event)
    return items.takeIf { it.isNotEmpty() }?.let(AttentionSignal::Requested)
}

/** Also used by legacy Chat replay so it shares exact identity and description with the shell. */
internal fun dashboardAttentionItems(
    scope: ConnectionScope,
    event: WsEvent?,
): List<AttentionItem> =
    when (event) {
        is WsEvent.ApprovalRequest -> {
            val runtimeId = event.sessionId?.takeIf(String::isNotBlank)
            val requestId = event.requestId?.takeIf(String::isNotBlank)
            if (runtimeId == null || requestId == null) {
                emptyList()
            } else {
                listOf(
                    AttentionItem(
                        RequestRef(scope, SessionRef(runtimeId), requestId),
                        AttentionKind.APPROVAL,
                        event.description ?: event.command ?: "実行内容は未提供です",
                        details =
                            AttentionDetails(
                                action = event.command,
                                patterns = event.patternKeys.orEmpty(),
                                approvalChoices = event.choices,
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
                        ),
                        AttentionKind.CLARIFICATION,
                        question.question,
                        details = AttentionDetails(options = question.choices, multiSelect = question.multiSelect),
                    )
                }
            }
        }

        else -> {
            emptyList()
        }
    }
