package com.m57.hermescontrol.data.ws

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalDecoderPolicyTest {
    private val offered = listOf("once", "session", "always", "deny")

    @Test
    fun disallowedSessionAlsoRemovesPermanentChoiceFromExplicitAdvertisement() {
        for (allowPermanent in listOf(null, false, true)) {
            val event =
                EventParser.parseApprovalRequest(
                    mapOf("choices" to offered, "allow_session" to false, "allow_permanent" to allowPermanent),
                    "runtime",
                )
            assertEquals(listOf("once", "deny"), event.choices)
        }
        assertEquals(
            emptyList<String>(),
            EventParser
                .parseApprovalRequest(
                    mapOf("choices" to listOf("session", "always"), "allow_session" to false),
                    "runtime",
                ).choices,
        )
    }

    @Test
    fun rawAdvertisementIsExactKnownAndNeverBroadenedWhenMalformed() {
        for (choices in listOf(null, "once", 1, mapOf("once" to true), emptyList<String>(), listOf("ONCE", "all"))) {
            assertEquals(
                emptyList<String>(),
                EventParser.parseApprovalRequest(mapOf("choices" to choices), "runtime").choices,
            )
        }
        assertEquals(
            listOf("once", "deny"),
            EventParser
                .parseApprovalRequest(
                    mapOf("choices" to listOf("deny", 1, "once", "once", "other")),
                    "runtime",
                ).choices,
        )
    }

    @Test
    fun restrictiveFlagsAgreeForLiveAndPendingReplayDecoding() {
        val cases =
            listOf(
                mapOf("smart_denied" to true) to listOf("once", "deny"),
                mapOf("allow_permanent" to false) to listOf("once", "session", "deny"),
                mapOf("allow_session" to false) to listOf("once", "deny"),
            )
        for ((flags, expected) in cases) {
            val payload = flags + mapOf("request_id" to "request:exact", "choices" to offered)
            val live =
                EventParser.parseParams(
                    mapOf("type" to "approval.request", "session_id" to "runtime:exact", "payload" to payload),
                ) as WsEvent.ApprovalRequest
            val replay = EventParser.parseApprovalRequest(payload, "runtime:exact")
            assertEquals(expected, live.choices)
            assertEquals(live, replay)
            assertEquals("request:exact", live.requestId)
            assertEquals("runtime:exact", live.sessionId)
        }
    }
}
