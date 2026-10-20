package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.domain.hermes.CapabilityBlock
import com.m57.hermescontrol.domain.hermes.CapabilityEvidence
import com.m57.hermescontrol.domain.hermes.CapabilitySnapshot
import com.m57.hermescontrol.domain.hermes.CapabilitySource
import com.m57.hermescontrol.domain.hermes.CapabilityState
import com.m57.hermescontrol.domain.hermes.HermesCapability
import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.domain.hermes.ReadFailure
import com.m57.hermescontrol.domain.hermes.SessionRead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** One read probe per connection generation. No prompt, approval, session creation or fallback. */
class CapabilityRepository(
    private val transport: HermesTransport,
    coroutineScope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(CapabilitySnapshot())
    val state = _state.asStateFlow()

    init {
        coroutineScope.launch {
            transport.connection.distinctUntilChanged().collectLatest { connection ->
                val scope = connection.scope
                _state.value = CapabilitySnapshot(scope)
                if (scope == null || connection.state != LinkState.CONNECTED) return@collectLatest
                val result = transport.readSessions(scope)
                if (result is SessionRead.Available && result.scope != scope) return@collectLatest
                val evidence =
                    CapabilityEvidence(
                        state = dashboardReadCapability(result),
                        source = CapabilitySource.DASHBOARD_READ,
                        scope = scope,
                        observedAtMillis = now(),
                    )
                _state.value =
                    CapabilitySnapshot(scope, _state.value.features + (HermesCapability.SESSION_LIST to evidence))
            }
        }
    }
}

internal fun dashboardReadCapability(result: SessionRead): CapabilityState =
    when (result) {
        is SessionRead.Available -> {
            CapabilityState.Supported
        }

        is SessionRead.Unavailable -> {
            when (result.reason) {
                ReadFailure.UNSUPPORTED -> CapabilityState.Unsupported
                ReadFailure.AUTH_REQUIRED -> CapabilityState.Blocked(CapabilityBlock.AUTH_REQUIRED)
                ReadFailure.MALFORMED -> CapabilityState.Blocked(CapabilityBlock.MALFORMED)
                ReadFailure.TIMEOUT_OR_UNAVAILABLE -> CapabilityState.Blocked(CapabilityBlock.UNAVAILABLE)
                ReadFailure.OFFLINE, ReadFailure.SCOPE_CHANGED -> CapabilityState.Unknown
            }
        }
    }
