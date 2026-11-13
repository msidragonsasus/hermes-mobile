package com.m57.hermescontrol.domain.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unknown row state must activate the existing explanatory notice, not another lone glyph. */
class SessionActivityResilienceTest {
    private val scope = ConnectionScope("server", "connection", "profile", "process", 1)
    private val connection = TransportConnection(scope, LinkState.CONNECTED)
    private val now = 20_000L
    private val session = LiveSession("runtime", "saved", LiveStatus.IDLE)

    @Test
    fun expiredBackgroundEvidenceExplainsItsUnknownStateEvenWhenTheForegroundFeedIsFresh() {
        val roster =
            LiveRoster(
                scope,
                listOf(session),
                now,
                background = mapOf("runtime" to BackgroundWork(true, 0L)),
            )
        val result = projectHistoryActivity(connection, roster, emptyList(), now)

        assertEquals(SessionActivity.UNCERTAIN, result.activity["saved"])
        assertTrue("An unknown ring needs the existing human-readable explanation", result.unverified)
    }

    @Test
    fun unknownServerStatusExplainsItsUnknownStateEvenWhenTheRosterReadSucceeded() {
        val roster = LiveRoster(scope, listOf(session.copy(status = LiveStatus.UNKNOWN)), now)
        val result = projectHistoryActivity(connection, roster, emptyList(), now)

        assertEquals(SessionActivity.UNCERTAIN, result.activity["saved"])
        assertTrue("A successful read does not explain an unknown server status", result.unverified)
    }

    @Test
    fun nonTransientBackgroundFailuresNeverRetainAnActiveClaimFromAnEarlierRead() {
        listOf(
            ReadFailure.AUTH_REQUIRED,
            ReadFailure.MALFORMED,
            ReadFailure.SCOPE_CHANGED,
            ReadFailure.UNSUPPORTED,
            ReadFailure.OFFLINE,
        ).forEach { failure ->
            val roster =
                LiveRoster(
                    scope,
                    listOf(session),
                    now,
                    background = mapOf("runtime" to BackgroundWork(true, now, failure)),
                )
            val result = projectHistoryActivity(connection, roster, emptyList(), now)

            assertEquals(
                "Failure $failure must invalidate the active claim",
                SessionActivity.UNCERTAIN,
                result.activity["saved"],
            )
            assertTrue("Failure $failure needs the existing explanation", result.unverified)
        }
    }
}
