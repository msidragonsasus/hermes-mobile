package com.m57.hermescontrol.data.ws

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Wire frames that keep a turn running between reply segments (tui_gateway contracts/events.py). */
class EventParserTurnSegmentTest {
    private fun parse(params: String): WsEvent =
        EventParser.parse(
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params = Json.parseToJsonElement(params).jsonObject,
            ),
        )

    @Test
    fun persistedTurnEvidenceIsNotDroppedOrConfusedWithTheRpcId() {
        val withReceipt =
            parse(
                """{"type":"message.complete","session_id":"s1","payload":{"text":"done","persisted_turn":{"row_ids":[101,102],"complete":false,"user_row_id":101,"submission_id":"server-submission"}}}""",
            )
        val without = parse("""{"type":"message.complete","session_id":"s1","payload":{"text":"done"}}""")
        org.junit.Assert.assertNotEquals(without, withReceipt)
        val malformed =
            parse(
                """{"type":"message.complete","session_id":"s1","payload":{"text":"done","persisted_turn":{"row_ids":[101,101],"complete":true}}}""",
            )
        assertEquals(without, malformed)
    }

    @Test
    fun nullDeltaTextIsASegmentBoundaryNotAnEmptyToken() {
        assertEquals(
            WsEvent.MessageSegmentEnd("s1"),
            parse("""{"type":"message.delta","session_id":"s1","payload":{"text":null}}"""),
        )
    }

    @Test
    fun deltaTextStaysAToken() {
        assertEquals(
            WsEvent.MessageToken("回答", "s1"),
            parse("""{"type":"message.delta","session_id":"s1","payload":{"text":"回答"}}"""),
        )
        assertEquals(
            WsEvent.MessageToken("", "s1"),
            parse("""{"type":"message.delta","session_id":"s1","payload":{"text":""}}"""),
        )
    }

    @Test
    fun interimCarriesTextAndWhetherItAlreadyStreamed() {
        assertEquals(
            WsEvent.MessageInterim("確認します。", alreadyStreamed = true, sessionId = "s1"),
            parse(
                """{"type":"message.interim","session_id":"s1",
                   "payload":{"text":"確認します。","already_streamed":true}}""",
            ),
        )
        assertEquals(
            WsEvent.MessageInterim("検索します。", alreadyStreamed = false, sessionId = "s1"),
            parse(
                """{"type":"message.interim","session_id":"s1",
                   "payload":{"text":"検索します。","already_streamed":false}}""",
            ),
        )
    }
}
