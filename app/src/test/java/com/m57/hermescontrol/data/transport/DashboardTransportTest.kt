package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.data.ws.ScopedWsEvent
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.ExecutionState
import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.domain.hermes.ObservationKind
import com.m57.hermescontrol.domain.hermes.ReadFailure
import com.m57.hermescontrol.domain.hermes.SessionRead
import com.m57.hermescontrol.domain.hermes.TransportConnection
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake uses the same normalized event contract; it is never installed in production. */
private class FakeHermesTransport(
    vararg events: ScopedWsEvent,
) : HermesTransport {
    override val connection = flowOf(TransportConnection(events.firstOrNull()?.scope, LinkState.CONNECTED))
    override val observations = normalizeDashboardEvents(flowOf(*events))

    override suspend fun readSessions(scope: ConnectionScope): SessionRead =
        SessionRead.Unavailable(ReadFailure.UNSUPPORTED)
}

class DashboardTransportTest {
    private val scope = ConnectionScope("synthetic-server", "connection-a", "profile-a", "process-a", 1)

    @Test
    fun `gateway epoch change invalidates observations without asserting failure`() =
        runTest {
            val events =
                flowOf(
                    ScopedWsEvent(scope, 1, WsEvent.GatewayReady(mapOf("replay_epoch" to "a"))),
                    ScopedWsEvent(scope, 2, WsEvent.GatewayReady(mapOf("replay_epoch" to "b"))),
                )
            val observation = normalizeDashboardEvents(events).toList().single()
            assertEquals(ObservationKind.GAP, observation.kind)
            assertEquals(ExecutionState.UNKNOWN, observation.state)
        }

    @Test
    fun `read contract accepts empty and partial lists and rejects malformed identity`() {
        fun decode(text: String) =
            decodeDashboardSessions(
                scope,
                kotlinx.serialization.json.Json
                    .parseToJsonElement(text),
            )
        assertEquals(SessionRead.Available(scope, emptyList()), decode("""{"sessions":[]}"""))
        val partial = decode("""{"sessions":[{"id":"saved-a"}]}""") as SessionRead.Available
        assertEquals("saved-a", partial.sessions.single().savedId)
        assertNull(partial.sessions.single().title)
        for (text in listOf(
            "{}",
            "[]",
            """{"sessions":[{}]}""",
            """{"sessions":[{"id":3}]}""",
            """{"sessions":[{"id":"a"},{"id":"a"}]}""",
        )) {
            assertEquals(SessionRead.Unavailable(ReadFailure.MALFORMED), decode(text))
        }
    }

    @Test
    fun `fake and dashboard normalizer preserve exact identities without inventing saved ids`() =
        runTest {
            val event = WsEvent.ApprovalRequest("synthetic command", null, null, "runtime-a", "request-a")
            val envelope = ScopedWsEvent(scope, 1, event)
            val actual = FakeHermesTransport(envelope).observations.toList().single()
            assertEquals(normalizeDashboardEvent(envelope), actual)
            assertEquals(scope, actual.request?.scope)
            assertEquals("request-a", actual.request?.requestId)
            assertEquals("runtime-a", actual.session?.runtimeId)
            assertNull(actual.session?.savedId)
            assertEquals(ExecutionState.WAITING_APPROVAL, actual.state)
            assertTrue(!actual.toString().contains("synthetic command"))
        }

    @Test
    fun `missing request or session cannot create an actionable ref`() {
        for (event in listOf(
            WsEvent.ApprovalRequest(null, null, null, "session", null),
            WsEvent.ApprovalRequest(null, null, null, null, "request"),
            WsEvent.ClarifyRequest("body", null, "", "session"),
        )) {
            assertNull(normalizeDashboardEvent(ScopedWsEvent(scope, 1, event))?.request)
        }
    }

    @Test
    fun `token omission is not a gap but dropped envelope and changed generation are`() =
        runTest {
            val events =
                listOf(
                    ScopedWsEvent(scope, 1, WsEvent.MessageStart("session")),
                    ScopedWsEvent(scope, 2, WsEvent.MessageToken("private text", "session")),
                    ScopedWsEvent(scope, 3, WsEvent.ToolStart("tool", null, "session")),
                    ScopedWsEvent(scope, 5, WsEvent.MessageComplete("private body", "session")),
                    ScopedWsEvent(scope.copy(generation = 2), 6, WsEvent.MessageStart("session")),
                )
            val observations = FakeHermesTransport(*events.toTypedArray()).observations.toList()
            assertEquals(
                listOf(
                    ObservationKind.STARTED,
                    ObservationKind.TOOL_STARTED,
                    ObservationKind.GAP,
                    ObservationKind.COMPLETED,
                    ObservationKind.GAP,
                    ObservationKind.STARTED,
                ),
                observations.map { it.kind },
            )
            assertTrue(observations.none { it.toString().contains("private") })
        }

    @Test
    fun `replay truncation stays unknown and completion preserves supplied saved mapping`() {
        val gap = normalizeDashboardEvent(ScopedWsEvent(scope, 7, null, gap = true))!!
        assertEquals(ExecutionState.UNKNOWN, gap.state)
        val complete =
            normalizeDashboardEvent(
                ScopedWsEvent(
                    scope,
                    8,
                    WsEvent.MessageComplete("body", "runtime", storedSessionId = "saved"),
                ),
            )!!
        assertEquals("runtime", complete.session?.runtimeId)
        assertEquals("saved", complete.session?.savedId)
    }
}
