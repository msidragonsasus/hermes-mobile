package com.m57.hermescontrol.domain.hermes

enum class HermesCapability { SESSION_LIST, OBSERVED_ACTIVITY, EXACT_APPROVAL, RUN_SUBMISSION, MINI_APP_EXECUTION }

enum class CapabilitySource { NONE, DASHBOARD_READ, DASHBOARD_EVENT, API_ADVERTISEMENT }

enum class CapabilityBlock { AUTH_REQUIRED, MALFORMED, UNAVAILABLE, UNVERIFIED_CONTRACT }

sealed interface CapabilityState {
    data object Unknown : CapabilityState

    data object Unsupported : CapabilityState

    data object Supported : CapabilityState

    data class Blocked(
        val reason: CapabilityBlock,
    ) : CapabilityState
}

data class CapabilityEvidence(
    val state: CapabilityState = CapabilityState.Unknown,
    val source: CapabilitySource = CapabilitySource.NONE,
    val scope: ConnectionScope? = null,
    val observedAtMillis: Long? = null,
)

data class CapabilitySnapshot(
    val scope: ConnectionScope? = null,
    val features: Map<HermesCapability, CapabilityEvidence> =
        HermesCapability.entries.associateWith {
            CapabilityEvidence()
        },
) {
    operator fun get(feature: HermesCapability): CapabilityEvidence = features[feature] ?: CapabilityEvidence()
}
