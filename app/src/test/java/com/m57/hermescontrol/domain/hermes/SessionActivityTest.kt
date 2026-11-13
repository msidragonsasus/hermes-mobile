package com.m57.hermescontrol.domain.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionActivityTest {
    private val scope = ConnectionScope("server", "connection", "profile", "process", 1)
    private val connected = TransportConnection(scope, LinkState.CONNECTED)
    private val now = 100_000L

    private fun roster(
        vararg sessions: LiveSession,
        rosterScope: ConnectionScope = scope,
        readAt: Long = now - 1_000,
        failure: ReadFailure? = null,
    ) = LiveRoster(rosterScope, sessions.toList(), readAt, failure)

    private fun attention(
        runtimeId: String,
        kind: AttentionKind,
        status: AttentionStatus = AttentionStatus.PENDING,
        requestScope: ConnectionScope = scope,
    ) = AttentionItem(
        RequestRef(requestScope, SessionRef(runtimeId), "request-$runtimeId-$kind"),
        kind,
        "t",
        status = status,
    )

    @Test
    fun rosterIsLivenessAuthorityForColdUnseenBackgroundSessions() {
        val statuses =
            projectSessionActivity(
                connected,
                roster(
                    // Legacy gateway: runtime and stored IDs differ; both must reach the history row.
                    LiveSession("runtime-a", "saved-a", LiveStatus.WORKING),
                    // Canonical authority: id == session_key.
                    LiveSession("saved-b", "saved-b", LiveStatus.STARTING),
                    LiveSession("runtime-c", "saved-c", LiveStatus.WAITING),
                    LiveSession("runtime-d", "saved-d", LiveStatus.IDLE),
                    LiveSession("runtime-e", null, LiveStatus.UNKNOWN),
                ),
                emptyList(),
                now,
            )

        // `starting` is admitted/queued before the agent runs (session_active_list.py), not running yet.
        assertEquals("STARTING", statuses["saved-b"]?.name)
        assertEquals(
            mapOf(
                "runtime-a" to SessionActivity.RUNNING,
                "saved-a" to SessionActivity.RUNNING,
                // `waiting` also covers non-human renderer reads, so it is only a generic wait.
                "runtime-c" to SessionActivity.WAITING,
                "saved-c" to SessionActivity.WAITING,
                "runtime-e" to SessionActivity.UNCERTAIN,
            ),
            statuses - "saved-b",
        )
    }

    @Test
    fun exactPendingRequestsRefineWaitingButNeverOverrideAuthoritativeIdleWorkingOrAbsent() {
        val statuses =
            projectSessionActivity(
                connected,
                roster(
                    LiveSession("approve", "saved-approve", LiveStatus.WAITING),
                    LiveSession("question", "saved-question", LiveStatus.WAITING),
                    LiveSession("answered", "saved-answered", LiveStatus.WAITING),
                    LiveSession("idle", "saved-idle", LiveStatus.IDLE),
                    LiveSession("working", "saved-working", LiveStatus.WORKING),
                ),
                listOf(
                    attention("approve", AttentionKind.APPROVAL),
                    attention("question", AttentionKind.CLARIFICATION),
                    attention("answered", AttentionKind.APPROVAL, AttentionStatus.SUBMITTING),
                    // Missed cancel/settle events: the roster says these are no longer waiting.
                    attention("idle", AttentionKind.APPROVAL),
                    attention("working", AttentionKind.CLARIFICATION),
                    attention("gone", AttentionKind.APPROVAL),
                    // Another socket generation is never this request.
                    attention("answered", AttentionKind.CLARIFICATION, requestScope = scope.copy(generation = 0)),
                ),
                now,
            )

        assertEquals(SessionActivity.WAITING_APPROVAL, statuses["saved-approve"])
        assertEquals(SessionActivity.NEEDS_INPUT, statuses["saved-question"])
        assertEquals(SessionActivity.WAITING, statuses["saved-answered"])
        assertEquals(null, statuses["saved-idle"])
        assertEquals(SessionActivity.RUNNING, statuses["saved-working"])
        assertEquals(null, statuses["gone"])
    }

    @Test
    fun staleFailedDisconnectedOrOtherGenerationRostersNeverSpinOrClaimIdle() {
        val sessions =
            arrayOf(
                LiveSession("runtime-a", "saved-a", LiveStatus.WORKING),
                LiveSession("runtime-b", "saved-b", LiveStatus.IDLE),
            )
        val uncertain = mapOf("runtime-a" to SessionActivity.UNCERTAIN, "saved-a" to SessionActivity.UNCERTAIN)
        val stale = now - LIVE_ROSTER_FRESH_MS - 1
        val cases =
            listOf(
                TransportConnection(null, LinkState.OFFLINE) to roster(*sessions),
                TransportConnection(scope, LinkState.RECONNECTING) to roster(*sessions),
                TransportConnection(scope, LinkState.AUTH_REQUIRED) to roster(*sessions),
                TransportConnection(scope.copy(generation = 2), LinkState.CONNECTED) to roster(*sessions),
                // Stopped observation (drawer closed / app stopped) and non-transient failures.
                connected to roster(*sessions, failure = ReadFailure.OFFLINE),
                connected to roster(*sessions, failure = ReadFailure.AUTH_REQUIRED),
                connected to roster(*sessions, failure = ReadFailure.MALFORMED),
                connected to roster(*sessions, failure = ReadFailure.TIMEOUT_OR_UNAVAILABLE, readAt = stale),
                connected to roster(*sessions, readAt = stale),
            )
        cases.forEach { (connection, roster) ->
            val projected = projectHistoryActivity(connection, roster, emptyList(), now)
            assertEquals(uncertain, projected.activity)
            assertEquals(emptySet<String>(), projected.rechecking)
            assertTrue("$connection $roster", projected.unverified)
        }
        // A different server profile's roster says nothing about this destination.
        val foreign =
            projectHistoryActivity(
                TransportConnection(scope.copy(serverProfileId = "other"), LinkState.CONNECTED),
                roster(*sessions),
                emptyList(),
                now,
            )
        assertEquals(HistoryActivity(), foreign)
    }

    @Test
    fun retainedAliasesNeverOutrankDirectRowsAndAFreshFailedRefreshIsRecheckingNotConfirmed() {
        val sessions =
            arrayOf(
                LiveSession(
                    "runtime-stable",
                    "saved-tip",
                    LiveStatus.WORKING,
                    aliases = setOf("saved-root", "saved-old"),
                ),
                // A newer row that owns an earlier key directly (and is idle) wins over the alias.
                LiveSession("saved-root", "saved-root", LiveStatus.IDLE),
            )
        val running =
            mapOf(
                "runtime-stable" to SessionActivity.RUNNING,
                "saved-tip" to SessionActivity.RUNNING,
                "saved-old" to SessionActivity.RUNNING,
            )
        val confirmed = projectHistoryActivity(connected, roster(*sessions), emptyList(), now)
        assertEquals(HistoryActivity(running, validUntil = now - 1_000 + LIVE_ROSTER_FRESH_MS + 1), confirmed)

        val retrying =
            projectHistoryActivity(
                connected,
                roster(*sessions, failure = ReadFailure.TIMEOUT_OR_UNAVAILABLE),
                emptyList(),
                now,
            )
        assertEquals(running, retrying.activity)
        assertEquals(running.keys, retrying.rechecking)
        assertFalse(retrying.unverified)
        assertEquals(confirmed.validUntil, retrying.validUntil)
    }

    @Test
    fun withoutAnyRosterOnlyExactLivePendingRequestsAreShown() {
        val requests =
            listOf(
                attention("approve", AttentionKind.APPROVAL),
                attention("question", AttentionKind.CLARIFICATION),
                attention("unknown", AttentionKind.APPROVAL, AttentionStatus.UNKNOWN),
                attention("old", AttentionKind.APPROVAL, requestScope = scope.copy(generation = 0)),
            )
        val unsupported = LiveRoster(scope, null, 0, ReadFailure.UNSUPPORTED)

        val expected = mapOf("approve" to SessionActivity.WAITING_APPROVAL, "question" to SessionActivity.NEEDS_INPUT)
        assertEquals(expected, projectSessionActivity(connected, null, requests, now))
        assertEquals(expected, projectSessionActivity(connected, unsupported, requests, now))
        assertEquals(
            emptyMap<String, SessionActivity>(),
            projectSessionActivity(TransportConnection(null, LinkState.OFFLINE), null, requests, now),
        )
    }
}
