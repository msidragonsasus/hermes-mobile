package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.HermesObservation
import com.m57.hermescontrol.domain.hermes.SessionRead
import com.m57.hermescontrol.domain.hermes.TransportConnection
import kotlinx.coroutines.flow.Flow

/** Phase 1 observations delegate to the existing socket. No parallel connection lifecycle. */
interface HermesTransport {
    val connection: Flow<TransportConnection>
    val observations: Flow<HermesObservation>

    suspend fun readSessions(scope: ConnectionScope): SessionRead
}
