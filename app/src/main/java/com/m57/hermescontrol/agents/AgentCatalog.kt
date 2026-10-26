package com.m57.hermescontrol.agents

/** Agent identity is separate from Chat sessions, providers and model selection. */
enum class AgentId {
    BRO,
    HERMES,
}

enum class AgentAdapterKind {
    BRO_PRIVATE_RELAY,
    HERMES_DASHBOARD,
}

enum class AgentConnectionState {
    UNVERIFIED,
    VERIFIED,
    DISCONNECTED,
    AUTH_REQUIRED,
}

enum class AgentCapability {
    SUBMIT_REQUEST,
    READ_REQUESTS,
    READ_RESULTS,
    READ_PROGRESS,
    RESPOND_TO_APPROVAL,
    CANCEL_REQUEST,
}

enum class AgentCapabilityState {
    UNKNOWN,
    UNSUPPORTED,
    SUPPORTED,
    BLOCKED,
}

/** Opaque verified identities only. Never put credentials or message bodies here. */
data class AgentScope(
    val agentId: AgentId,
    val ownerId: String,
    val connectionId: String,
    val profileId: String,
) {
    init {
        require(ownerId.isNotBlank() && connectionId.isNotBlank() && profileId.isNotBlank())
    }
}

data class AgentConnection(
    val id: AgentId,
    val displayName: String,
    val adapterKind: AgentAdapterKind,
    val state: AgentConnectionState = AgentConnectionState.UNVERIFIED,
    val verifiedScope: AgentScope? = null,
    val capabilities: Map<AgentCapability, AgentCapabilityState> = emptyMap(),
) {
    init {
        require(displayName.isNotBlank())
        require(verifiedScope == null || verifiedScope.agentId == id)
        require(state != AgentConnectionState.VERIFIED || verifiedScope != null)
    }

    /** Missing evidence, stale profile/owner/connection and unknown features always fail closed. */
    fun canUse(
        capability: AgentCapability,
        activeScope: AgentScope?,
    ): Boolean =
        state == AgentConnectionState.VERIFIED &&
            activeScope != null && activeScope == verifiedScope &&
            capabilities[capability] == AgentCapabilityState.SUPPORTED

    fun capabilityState(capability: AgentCapability): AgentCapabilityState =
        capabilities[capability] ?: AgentCapabilityState.UNKNOWN
}

/**
 * Local candidate inventory, not a live connection registry. No transport, token or auto-connect.
 * Neither the existing bro-to-Hermes relay nor a Chat socket verifies these new hub adapters.
 * Future agents require an explicit integration; an unknown ID must never fall back to Hermes.
 */
object AgentCatalog {
    val connections: List<AgentConnection> =
        listOf(
            AgentConnection(AgentId.BRO, "bro", AgentAdapterKind.BRO_PRIVATE_RELAY),
            AgentConnection(AgentId.HERMES, "Hermes", AgentAdapterKind.HERMES_DASHBOARD),
        )

    fun find(id: AgentId): AgentConnection? = connections.firstOrNull { it.id == id }
}

internal fun agentIdFromRoute(value: String): AgentId? = AgentId.entries.firstOrNull { it.name == value }
