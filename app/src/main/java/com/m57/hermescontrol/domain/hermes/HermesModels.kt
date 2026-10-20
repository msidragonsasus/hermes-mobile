package com.m57.hermescontrol.domain.hermes

import kotlinx.serialization.Serializable

/** Identifiers only: never credentials, URLs with tickets, or message bodies. */
@Serializable
data class ConnectionScope(
    val serverIdentity: String,
    val connectionProfileId: String?,
    val serverProfileId: String?,
    val processId: String,
    val generation: Int,
)

data class SessionRef(
    val runtimeId: String,
    val savedId: String? = null,
)

data class RequestRef(
    val scope: ConnectionScope,
    val session: SessionRef,
    val requestId: String,
    val questionId: String? = null,
)

enum class ExecutionState {
    UNKNOWN,
    RUNNING,
    NEEDS_INPUT,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELED,
}

enum class ObservationKind {
    STARTED,
    COMPLETED,
    TOOL_STARTED,
    TOOL_COMPLETED,
    APPROVAL,
    QUESTION,
    QUESTION_EXPIRED,
    GAP,
}

/** Local observation, not a durable server Run or an exhaustive history entry. */
data class HermesObservation(
    val scope: ConnectionScope,
    val ordinal: Long,
    val session: SessionRef?,
    val kind: ObservationKind,
    val state: ExecutionState,
    val request: RequestRef? = null,
)

enum class LinkState { OFFLINE, CONNECTING, CONNECTED, RECONNECTING, AUTH_REQUIRED }

data class TransportConnection(
    val scope: ConnectionScope?,
    val state: LinkState,
)

data class SessionSummary(
    val savedId: String,
    val title: String?,
)

enum class ReadFailure { OFFLINE, AUTH_REQUIRED, UNSUPPORTED, MALFORMED, TIMEOUT_OR_UNAVAILABLE, SCOPE_CHANGED }

sealed interface SessionRead {
    data class Available(
        val scope: ConnectionScope,
        val sessions: List<SessionSummary>,
    ) : SessionRead

    data class Unavailable(
        val reason: ReadFailure,
    ) : SessionRead
}
