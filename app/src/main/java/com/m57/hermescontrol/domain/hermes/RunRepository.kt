package com.m57.hermescontrol.domain.hermes

import com.m57.hermescontrol.data.transport.HermesTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** A bounded, process-local observation of a session; never a durable server Run. */
data class ObservedRun(
    val scope: ConnectionScope,
    val session: SessionRef,
    val lastConfirmedState: ExecutionState,
    val lastKind: ObservationKind,
    val observedAt: Long,
    val uncertain: Boolean = false,
)

data class ObservedEvent(
    val observation: HermesObservation,
    val observedAt: Long,
)

data class RunSnapshot(
    val connection: TransportConnection = TransportConnection(null, LinkState.OFFLINE),
    val runs: List<ObservedRun> = emptyList(),
    val events: List<ObservedEvent> = emptyList(),
    val sessions: List<SessionSummary> = emptyList(),
    val loading: Boolean = false,
    val readFailure: ReadFailure? = null,
)

class RunRepository(
    private val transport: HermesTransport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableSnapshot = MutableStateFlow(RunSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()
    private var started = false
    private var activeScope: ConnectionScope? = null
    private var lastOrdinal = Long.MIN_VALUE
    private val ordinalKinds = mutableSetOf<ObservationKind>()

    /** Caller owns lifetime. Does not create sessions, connect sockets, or submit work. */
    fun start(coroutineScope: CoroutineScope): List<Job> {
        check(!started)
        started = true
        return listOf(
            coroutineScope.launch { transport.observations.collect(::observe) },
            coroutineScope.launch {
                transport.connection.distinctUntilChanged().collectLatest { connection ->
                    updateConnection(connection)
                    val scope = connection.scope
                    if (connection.state == LinkState.CONNECTED && scope != null) {
                        val result = transport.readSessions(scope)
                        acceptSessions(scope, result)
                    }
                }
            },
        )
    }

    @Synchronized
    private fun updateConnection(connection: TransportConnection) {
        val previous = mutableSnapshot.value
        val nextScope = connection.scope
        val changed = nextScope != null && activeScope != nextScope
        val sameDestination =
            activeScope?.let { old ->
                nextScope != null && old.copy(generation = nextScope.generation) == nextScope
            } == true
        if (changed) {
            activeScope = nextScope
            lastOrdinal = Long.MIN_VALUE
            ordinalKinds.clear()
        }
        val retained = !changed || sameDestination
        mutableSnapshot.value =
            previous.copy(
                connection = connection,
                runs = if (retained) previous.runs.map { it.copy(uncertain = true) } else emptyList(),
                events = if (retained) previous.events else emptyList(),
                sessions = if (changed || connection.state != LinkState.CONNECTED) emptyList() else previous.sessions,
                loading = connection.state == LinkState.CONNECTED && nextScope != null,
                readFailure = null,
            )
    }

    @Synchronized
    private fun acceptSessions(
        scope: ConnectionScope,
        result: SessionRead,
    ) {
        val previous = mutableSnapshot.value
        if (previous.connection.scope != scope ||
            previous.connection.state != LinkState.CONNECTED
        ) {
            return
        }
        mutableSnapshot.value =
            when (result) {
                is SessionRead.Available -> {
                    if (result.scope == scope) {
                        previous.copy(
                            loading = false,
                            sessions = result.sessions.take(MAX_SESSIONS),
                            readFailure = null,
                        )
                    } else {
                        previous.copy(loading = false, readFailure = ReadFailure.SCOPE_CHANGED)
                    }
                }

                is SessionRead.Unavailable -> {
                    previous.copy(loading = false, readFailure = result.reason)
                }
            }
    }

    @Synchronized
    private fun observe(observation: HermesObservation) {
        val previous = mutableSnapshot.value
        if (previous.connection.state != LinkState.CONNECTED || previous.connection.scope != observation.scope) return
        if (observation.ordinal < lastOrdinal) return
        if (observation.ordinal > lastOrdinal) ordinalKinds.clear()
        if (!ordinalKinds.add(observation.kind)) return
        lastOrdinal = observation.ordinal
        val timestamp = now()
        val session =
            observation.session?.let { incoming ->
                incoming.copy(
                    savedId =
                        incoming.savedId ?: previous.runs
                            .firstOrNull {
                                it.session.runtimeId == incoming.runtimeId && it.scope == observation.scope
                            }?.session
                            ?.savedId,
                )
            }
        val runs =
            when {
                observation.kind == ObservationKind.GAP -> {
                    previous.runs.map { it.copy(uncertain = true) }
                }

                session == null -> {
                    previous.runs
                }

                else -> {
                    listOf(
                        ObservedRun(observation.scope, session, observation.state, observation.kind, timestamp),
                    ) + previous.runs.filterNot { it.session.runtimeId == session.runtimeId }
                }
            }
        mutableSnapshot.value =
            previous.copy(
                runs = runs.take(MAX_SESSIONS),
                events = (listOf(ObservedEvent(observation, timestamp)) + previous.events).take(MAX_EVENTS),
            )
    }

    companion object {
        const val MAX_SESSIONS = 100
        const val MAX_EVENTS = 200
    }
}
