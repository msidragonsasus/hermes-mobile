package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.domain.hermes.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CapabilityRepositoryTest {
    private val scope = ConnectionScope("server", "a", "a", "process", 1)

    @Test
    fun `read outcome matrix never interprets unknown as supported`() {
        assertEquals(CapabilityState.Supported, dashboardReadCapability(SessionRead.Available(scope, emptyList())))
        val expected =
            mapOf(
                ReadFailure.UNSUPPORTED to CapabilityState.Unsupported,
                ReadFailure.AUTH_REQUIRED to CapabilityState.Blocked(CapabilityBlock.AUTH_REQUIRED),
                ReadFailure.MALFORMED to CapabilityState.Blocked(CapabilityBlock.MALFORMED),
                ReadFailure.TIMEOUT_OR_UNAVAILABLE to CapabilityState.Blocked(CapabilityBlock.UNAVAILABLE),
                ReadFailure.OFFLINE to CapabilityState.Unknown,
                ReadFailure.SCOPE_CHANGED to CapabilityState.Unknown,
            )
        expected.forEach { (failure, state) ->
            assertEquals(state, dashboardReadCapability(SessionRead.Unavailable(failure)))
        }
    }

    @Test
    fun `reconnect and profile switch reset old evidence and cancel old read`() =
        runTest {
            val connection = MutableStateFlow(TransportConnection(scope, LinkState.CONNECTED))
            val pending = CompletableDeferred<SessionRead>()
            val reads = mutableListOf<ConnectionScope>()
            val transport =
                object : HermesTransport {
                    override val connection = connection
                    override val observations = emptyFlow<HermesObservation>()

                    override suspend fun readSessions(scope: ConnectionScope): SessionRead {
                        reads.add(scope)
                        return if (scope.generation == 1) pending.await() else SessionRead.Available(scope, emptyList())
                    }
                }
            val repository = CapabilityRepository(transport, backgroundScope) { 123L }
            runCurrent()
            assertEquals(CapabilityState.Unknown, repository.state.value[HermesCapability.SESSION_LIST].state)
            val other = scope.copy(connectionProfileId = "b", serverProfileId = "b", generation = 2)
            connection.value = TransportConnection(other, LinkState.CONNECTED)
            runCurrent()
            assertEquals(other, repository.state.value.scope)
            assertEquals(CapabilityState.Supported, repository.state.value[HermesCapability.SESSION_LIST].state)
            assertEquals(CapabilityState.Unknown, repository.state.value[HermesCapability.EXACT_APPROVAL].state)
            pending.complete(SessionRead.Available(scope, emptyList()))
            runCurrent()
            assertEquals(other, repository.state.value.scope)
            connection.value = TransportConnection(null, LinkState.RECONNECTING)
            runCurrent()
            assertNull(repository.state.value.scope)
            assertEquals(CapabilityState.Unknown, repository.state.value[HermesCapability.SESSION_LIST].state)
            assertEquals(listOf(scope, other), reads)
        }

    @Test
    fun `api discovery status matrix and malformed bodies remain disabled`() {
        val matrix =
            mapOf(
                401 to ApiDiscoveryFailure.AUTH_REQUIRED,
                403 to ApiDiscoveryFailure.AUTH_REQUIRED,
                404 to ApiDiscoveryFailure.NOT_FOUND,
                500 to ApiDiscoveryFailure.UNAVAILABLE,
                null to ApiDiscoveryFailure.UNAVAILABLE,
            )
        matrix.forEach { (status, reason) ->
            assertEquals(ApiDiscoveryResult.Unavailable(reason), ApiServerCapabilityDecoder.decode(status, null))
        }
        for (body in listOf(
            "{",
            "[]",
            "{}",
            """{"object":"dashboard","features":{}}""",
            """{"object":"hermes.api_server.capabilities","features":{"run_submission":"true"}}""",
            "x".repeat(65537),
            "[".repeat(10000) + "]".repeat(10000),
        )) {
            assertEquals(
                ApiDiscoveryResult.Unavailable(ApiDiscoveryFailure.MALFORMED),
                ApiServerCapabilityDecoder.decode(200, body),
            )
        }
    }

    @Test
    fun `partial and full api advertisements do not enable live writes`() {
        val partial =
            ApiServerCapabilityDecoder.decode(
                200,
                """{"object":"hermes.api_server.capabilities","features":{}}""",
            ) as ApiDiscoveryResult.Advertisement
        assertTrue(partial.value.features.isEmpty())
        assertNull(partial.value.idempotency)
        val full =
            ApiServerCapabilityDecoder.decode(
                200,
                """{"object":"hermes.api_server.capabilities","features":{"run_submission":true,"approval_events":true,"run_approval_response":false,"runs_idempotency":{"supported":true,"durable":true,"retention_seconds":86400}},"endpoints":{"untrusted":{"path":"https://example.invalid"}}}""",
            ) as ApiDiscoveryResult.Advertisement
        assertEquals(true, full.value.features[ApiFeature.RUN_SUBMISSION])
        assertEquals(86400L, full.value.idempotency?.retentionSeconds)
        assertFalse(full.value.liveWritesEnabled)
        assertFalse(partial.value.liveWritesEnabled)
    }
}
