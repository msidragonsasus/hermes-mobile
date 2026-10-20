package com.m57.hermescontrol.domain.hermes

import com.m57.hermescontrol.data.transport.HermesTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunRepositoryTest {
    private val scope = ConnectionScope("server", "connection", "profile", "process", 1)
    private val session = SessionRef("runtime", "saved")

    private inner class Transport : HermesTransport {
        override val connection = MutableStateFlow(TransportConnection(scope, LinkState.CONNECTED))
        override val observations = MutableSharedFlow<HermesObservation>()
        var result: SessionRead = SessionRead.Available(scope, listOf(SessionSummary("saved", "Title")))
        var reads = 0

        override suspend fun readSessions(scope: ConnectionScope): SessionRead {
            reads++
            return result
        }
    }

    private fun event(
        ordinal: Long,
        target: ConnectionScope = scope,
    ) = HermesObservation(target, ordinal, session, ObservationKind.STARTED, ExecutionState.RUNNING)

    @Test
    fun gapAndEventSharingOrdinalBothApplyAndKnownSavedIdSurvivesToolEvents() =
        runTest {
            val transport = Transport()
            val repository = RunRepository(transport)
            repository.start(backgroundScope)
            runCurrent()
            transport.observations.emit(event(1))
            transport.observations.emit(event(3).copy(kind = ObservationKind.GAP, session = null))
            transport.observations.emit(
                event(3).copy(kind = ObservationKind.TOOL_STARTED, session = SessionRef("runtime")),
            )
            runCurrent()
            assertEquals(3, repository.snapshot.value.events.size)
            assertEquals(
                "saved",
                repository.snapshot.value.runs
                    .single()
                    .session.savedId,
            )
            assertEquals(
                ObservationKind.TOOL_STARTED,
                repository.snapshot.value.runs
                    .single()
                    .lastKind,
            )
            assertFalse(
                repository.snapshot.value.runs
                    .single()
                    .uncertain,
            )
        }

    @Test
    fun disconnectAndGapPreserveLastConfirmedStateWithoutInventingFailure() =
        runTest {
            val transport = Transport()
            val repository = RunRepository(transport) { 123L }
            repository.start(backgroundScope)
            runCurrent()
            transport.observations.emit(event(1))
            runCurrent()
            assertFalse(
                repository.snapshot.value.runs
                    .single()
                    .uncertain,
            )
            assertEquals(
                123L,
                repository.snapshot.value.events
                    .single()
                    .observedAt,
            )
            transport.observations.emit(event(2).copy(kind = ObservationKind.GAP, session = null))
            runCurrent()
            assertTrue(
                repository.snapshot.value.runs
                    .single()
                    .uncertain,
            )
            transport.connection.value = TransportConnection(null, LinkState.OFFLINE)
            runCurrent()
            assertEquals(
                ExecutionState.RUNNING,
                repository.snapshot.value.runs
                    .single()
                    .lastConfirmedState,
            )
            assertTrue(
                repository.snapshot.value.runs
                    .single()
                    .uncertain,
            )
            assertTrue(
                repository.snapshot.value.sessions
                    .isEmpty(),
            )
        }

    @Test
    fun replayAndCrossScopeEventsAreIgnoredAndNewDestinationClearsPrivateHistory() =
        runTest {
            val transport = Transport()
            val repository = RunRepository(transport)
            repository.start(backgroundScope)
            runCurrent()
            transport.observations.emit(event(1))
            transport.observations.emit(event(1))
            transport.observations.emit(event(2, scope.copy(serverProfileId = "other")))
            runCurrent()
            assertEquals(1, repository.snapshot.value.events.size)
            val changed = scope.copy(serverIdentity = "other")
            transport.connection.value = TransportConnection(changed, LinkState.CONNECTED)
            runCurrent()
            assertTrue(
                repository.snapshot.value.runs
                    .isEmpty(),
            )
            assertTrue(
                repository.snapshot.value.events
                    .isEmpty(),
            )
            assertTrue(
                repository.snapshot.value.sessions
                    .isEmpty(),
            )
            assertEquals(ReadFailure.SCOPE_CHANGED, repository.snapshot.value.readFailure)
        }

    @Test
    fun reconnectRetainsUncertaintyUntilNewObservationAndResetsOrdinal() =
        runTest {
            val transport = Transport()
            val repository = RunRepository(transport)
            repository.start(backgroundScope)
            runCurrent()
            transport.observations.emit(event(20))
            val reconnected = scope.copy(generation = 2)
            transport.connection.value = TransportConnection(reconnected, LinkState.CONNECTED)
            runCurrent()
            assertTrue(
                repository.snapshot.value.runs
                    .single()
                    .uncertain,
            )
            transport.observations.emit(event(21))
            transport.observations.emit(event(1, reconnected))
            runCurrent()
            assertFalse(
                repository.snapshot.value.runs
                    .single()
                    .uncertain,
            )
            assertEquals(
                reconnected,
                repository.snapshot.value.runs
                    .single()
                    .scope,
            )
            assertEquals(2, repository.snapshot.value.events.size)
        }

    @Test
    fun unavailableReadRemainsExplicitAndObservationStorageIsBounded() =
        runTest {
            val transport = Transport()
            transport.result = SessionRead.Unavailable(ReadFailure.UNSUPPORTED)
            val repository = RunRepository(transport)
            repository.start(backgroundScope)
            runCurrent()
            assertFalse(repository.snapshot.value.loading)
            assertEquals(ReadFailure.UNSUPPORTED, repository.snapshot.value.readFailure)
            repeat(250) { index ->
                transport.observations.emit(event(index.toLong()).copy(session = SessionRef("session-$index")))
            }
            runCurrent()
            assertEquals(RunRepository.MAX_SESSIONS, repository.snapshot.value.runs.size)
            assertEquals(RunRepository.MAX_EVENTS, repository.snapshot.value.events.size)
            assertEquals(1, transport.reads)
        }
}
