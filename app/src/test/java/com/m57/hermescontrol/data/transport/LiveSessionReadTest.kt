package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.domain.hermes.BackgroundRead
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.LiveRead
import com.m57.hermescontrol.domain.hermes.LiveSession
import com.m57.hermescontrol.domain.hermes.LiveStatus
import com.m57.hermescontrol.domain.hermes.ReadFailure
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSessionReadTest {
    private val scope = ConnectionScope("server", "connection", "profile", "process", 1)

    private fun decode(json: String) = decodeLiveSessions(scope, Json.parseToJsonElement(json))

    @Test
    fun activeListRowsDecodeStatusesAndBothIdsWithoutTrustingUnknownValues() {
        val read =
            decode(
                """
                {"sessions":[
                  {"id":"rt-1","session_key":"saved-1","status":"working","title":"x","current":false},
                  {"id":"saved-2","session_key":"saved-2","status":"starting"},
                  {"id":"rt-3","session_key":"saved-3","status":"waiting"},
                  {"id":"rt-4","session_key":"","status":"idle"},
                  {"id":"rt-5","status":"paused"},
                  {"id":"rt-6","session_key":7,"status":1}
                ]}
                """,
            )

        assertEquals(
            LiveRead.Available(
                scope,
                listOf(
                    LiveSession("rt-1", "saved-1", LiveStatus.WORKING),
                    LiveSession("saved-2", "saved-2", LiveStatus.STARTING),
                    LiveSession("rt-3", "saved-3", LiveStatus.WAITING),
                    LiveSession("rt-4", null, LiveStatus.IDLE),
                    LiveSession("rt-5", null, LiveStatus.UNKNOWN),
                    LiveSession("rt-6", null, LiveStatus.UNKNOWN),
                ),
            ),
            read,
        )
    }

    @Test
    fun malformedRostersAreUnavailableRatherThanAnEmptyIdleList() {
        listOf(
            """{}""",
            """{"sessions":{}}""",
            """{"sessions":["rt-1"]}""",
            """{"sessions":[{"session_key":"saved","status":"working"}]}""",
            """{"sessions":[{"id":"","status":"working"}]}""",
            """{"sessions":[{"id":"a","status":"idle"},{"id":"a","status":"working"}]}""",
        ).forEach { json ->
            assertEquals(json, LiveRead.Unavailable(ReadFailure.MALFORMED), decode(json))
        }
        assertEquals(LiveRead.Unavailable(ReadFailure.MALFORMED), decodeLiveSessions(scope, null))
        assertEquals(LiveRead.Available(scope, emptyList()), decode("""{"sessions":[]}"""))
    }

    private fun subagents(json: String) = decodeSubagentWork(Json.parseToJsonElement(json))

    private fun processes(json: String) = decodeProcessWork(Json.parseToJsonElement(json))

    private val running = BackgroundRead.Available(true)
    private val none = BackgroundRead.Available(false)
    private val unknown = BackgroundRead.Unavailable(ReadFailure.MALFORMED)

    @Test
    fun ownerScopedChildRosterAnswersRunningOrNoneOnlyFromActualRecords() {
        assertEquals(running, subagents("""{"subagents":[{"subagent_id":"a","status":"running"}],"delegations":[]}"""))
        assertEquals(running, subagents("""{"subagents":[{"subagent_id":"a","status":"queued"}]}"""))
        assertEquals(running, subagents("""{"subagents":[{"subagent_id":"a","status":"steered"}]}"""))
        assertEquals(
            none,
            subagents(
                """{"subagents":[{"subagent_id":"a","status":"completed"},{"subagent_id":"b","status":"failed"}]}""",
            ),
        )
        // An empty live roster is "no running child"; `delegations` (failed history) never decides it.
        assertEquals(none, subagents("""{"subagents":[],"delegations":[]}"""))
        assertEquals(unknown, subagents("""{"delegations":[]}"""))
        assertEquals(unknown, subagents("""{"subagents":[{"subagent_id":"a","status":"paused"}]}"""))
        assertEquals(unknown, subagents("""{"subagents":[{"subagent_id":"a"}]}"""))
        // A running record decides even beside an unreadable one.
        assertEquals(
            running,
            subagents(
                """{"subagents":[{"subagent_id":"a","status":"paused"},{"subagent_id":"b","status":"running"}]}""",
            ),
        )
        assertEquals(unknown, decodeSubagentWork(null))

        assertEquals(running, processes("""{"processes":[{"session_id":"proc_1","status":"running"}]}"""))
        assertEquals(none, processes("""{"processes":[{"session_id":"proc_1","status":"exited","exit_code":0}]}"""))
        assertEquals(none, processes("""{"processes":[]}"""))
        assertEquals(unknown, processes("""{}"""))
        assertEquals(unknown, processes("""{"processes":[{"session_id":"proc_1","status":"zombie"}]}"""))
    }

    @Test
    fun combinedBackgroundAnswerNeverTurnsAFailedOrUnsupportedReadIntoNone() {
        val unsupported = BackgroundRead.Unavailable(ReadFailure.UNSUPPORTED)
        val timeout = BackgroundRead.Unavailable(ReadFailure.TIMEOUT_OR_UNAVAILABLE)
        assertEquals(running, combineBackgroundWork(running, timeout))
        assertEquals(running, combineBackgroundWork(unsupported, running))
        assertEquals(none, combineBackgroundWork(none, none))
        assertEquals(timeout, combineBackgroundWork(none, timeout))
        assertEquals(timeout, combineBackgroundWork(unsupported, none))
        assertEquals(unsupported, combineBackgroundWork(unsupported, unsupported))
    }
}
