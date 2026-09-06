package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.ScopedWsEvent
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.ExecutionState
import com.m57.hermescontrol.domain.hermes.HermesObservation
import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.domain.hermes.ObservationKind
import com.m57.hermescontrol.domain.hermes.ReadFailure
import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.domain.hermes.SessionRead
import com.m57.hermescontrol.domain.hermes.SessionRef
import com.m57.hermescontrol.domain.hermes.SessionSummary
import com.m57.hermescontrol.domain.hermes.TransportConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class DashboardRpcTransport : HermesTransport {
    override val connection =
        combine(HermesWsClient.connectionScope, HermesWsClient.connectionStatus) { scope, status ->
            TransportConnection(
                scope.takeIf { status == ConnectionStatus.CONNECTED },
                when (status) {
                    ConnectionStatus.CONNECTED -> LinkState.CONNECTED
                    ConnectionStatus.CONNECTING -> LinkState.CONNECTING
                    ConnectionStatus.RECONNECTING -> LinkState.RECONNECTING
                    ConnectionStatus.AUTH_EXPIRED -> LinkState.AUTH_REQUIRED
                    else -> LinkState.OFFLINE
                },
            )
        }

    override val observations = normalizeDashboardEvents(HermesWsClient.scopedEvents)

    override suspend fun readSessions(scope: ConnectionScope): SessionRead {
        if (!HermesWsClient.matchesCurrentScope(scope)) return SessionRead.Unavailable(ReadFailure.SCOPE_CHANGED)
        if (HermesWsClient.connectionStatus.value !=
            ConnectionStatus.CONNECTED
        ) {
            return SessionRead.Unavailable(ReadFailure.OFFLINE)
        }
        return try {
            val result = HermesWsClient.readSessions(scope).await()
            if (!HermesWsClient.matchesCurrentScope(scope)) {
                SessionRead.Unavailable(ReadFailure.SCOPE_CHANGED)
            } else {
                decodeDashboardSessions(scope, result as? JsonElement)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HermesWsClient.HermesRpcException) {
            SessionRead.Unavailable(
                when (e.code) {
                    -32601 -> ReadFailure.UNSUPPORTED
                    401, 4401 -> ReadFailure.AUTH_REQUIRED
                    else -> ReadFailure.TIMEOUT_OR_UNAVAILABLE
                },
            )
        }
    }
}

internal fun decodeDashboardSessions(
    scope: ConnectionScope,
    result: JsonElement?,
): SessionRead {
    val rows =
        (result as? JsonObject)?.get("sessions") as? JsonArray ?: return SessionRead.Unavailable(ReadFailure.MALFORMED)
    val sessions = mutableListOf<SessionSummary>()
    val ids = mutableSetOf<String>()
    for (row in rows) {
        val item = row as? JsonObject ?: return SessionRead.Unavailable(ReadFailure.MALFORMED)
        val id = item["id"] as? JsonPrimitive
        if (id == null || !id.isString || id.content.isBlank() ||
            !ids.add(id.content)
        ) {
            return SessionRead.Unavailable(ReadFailure.MALFORMED)
        }
        val title = item["title"] as? JsonPrimitive
        sessions.add(SessionSummary(id.content, title?.takeIf { it.isString }?.content))
    }
    return SessionRead.Available(scope, sessions)
}

internal fun normalizeDashboardEvents(events: Flow<ScopedWsEvent>): Flow<HermesObservation> =
    flow {
        var previous: ScopedWsEvent? = null
        var replayEpoch: String? = null
        events.collect { envelope ->
            val prior = previous
            val epoch = (envelope.event as? WsEvent.GatewayReady)?.data?.get("replay_epoch") as? String
            val epochChanged = !epoch.isNullOrBlank() && replayEpoch != null && replayEpoch != epoch
            if (!epoch.isNullOrBlank()) replayEpoch = epoch
            if (epochChanged ||
                (prior != null && (prior.scope != envelope.scope || envelope.ordinal != prior.ordinal + 1))
            ) {
                emit(
                    HermesObservation(
                        envelope.scope,
                        envelope.ordinal,
                        null,
                        ObservationKind.GAP,
                        ExecutionState.UNKNOWN,
                    ),
                )
            }
            previous = envelope
            normalizeDashboardEvent(envelope)?.let { emit(it) }
        }
    }

internal fun normalizeDashboardEvent(envelope: ScopedWsEvent): HermesObservation? {
    var requestId: String? = null
    var questionId: String? = null
    var savedId: String? = null
    val (sessionId, kind, state) =
        when (val event = envelope.event) {
            is WsEvent.MessageStart -> {
                Triple(event.sessionId, ObservationKind.STARTED, ExecutionState.RUNNING)
            }

            is WsEvent.MessageComplete -> {
                savedId = event.storedSessionId
                Triple(event.sessionId, ObservationKind.COMPLETED, ExecutionState.COMPLETED)
            }

            is WsEvent.ToolStart -> {
                Triple(event.sessionId, ObservationKind.TOOL_STARTED, ExecutionState.RUNNING)
            }

            is WsEvent.ToolComplete -> {
                Triple(event.sessionId, ObservationKind.TOOL_COMPLETED, ExecutionState.RUNNING)
            }

            is WsEvent.ApprovalRequest -> {
                requestId = event.requestId
                Triple(event.sessionId, ObservationKind.APPROVAL, ExecutionState.WAITING_APPROVAL)
            }

            is WsEvent.ClarifyRequest -> {
                requestId = event.clarifyId
                questionId = event.questionId
                Triple(event.sessionId, ObservationKind.QUESTION, ExecutionState.NEEDS_INPUT)
            }

            is WsEvent.ClarifyExpire -> {
                requestId = event.clarifyId
                Triple(event.sessionId, ObservationKind.QUESTION_EXPIRED, ExecutionState.UNKNOWN)
            }

            else -> {
                if (envelope.gap) Triple(null, ObservationKind.GAP, ExecutionState.UNKNOWN) else return null
            }
        }
    val session = sessionId?.takeIf(String::isNotBlank)?.let { SessionRef(it, savedId) }
    val request =
        requestId?.takeIf(String::isNotBlank)?.let { id ->
            session?.let { RequestRef(envelope.scope, it, id, questionId) }
        }
    return HermesObservation(envelope.scope, envelope.ordinal, session, kind, state, request)
}
