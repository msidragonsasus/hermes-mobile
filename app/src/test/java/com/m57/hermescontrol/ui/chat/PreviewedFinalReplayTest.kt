package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.EventParser
import com.m57.hermescontrol.data.ws.JsonRpcResponse
import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewedFinalReplayTest {
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
    fun explicitlyPreviewedFinalDoesNotAppendItsInterimDeliveryAgain() {
        val sid = "previewed-final"
        val start =
            ChatWsEventReducer.reduce(
                ChatUiState(currentSessionId = sid),
                StreamingState(),
                WsEvent.MessageStart(sid),
                sid,
            )
        val interim =
            ChatWsEventReducer.reduce(
                start.state,
                start.streamingState,
                parse(
                    """{"type":"message.interim","session_id":"previewed-final","payload":{"text":"Synthetic final answer","already_streamed":false}}""",
                ),
                sid,
            )
        val deliveredId =
            interim.state.messages
                .single()
                .id
        val terminal =
            parse(
                """{"type":"message.complete","session_id":"previewed-final","payload":{"text":"Synthetic final answer","status":"complete","response_previewed":true}}""",
            )
        val completed = ChatWsEventReducer.reduce(interim.state, interim.streamingState, terminal, sid)
        assertEquals("The explicit previewed delivery must remain one answer", 1, completed.state.messages.size)
        assertEquals(
            deliveredId,
            completed.state.messages
                .single()
                .id,
        )
        assertEquals(
            "Synthetic final answer",
            completed.state.messages
                .single()
                .content,
        )
        assertTrue(completed.streamingState.turnEnded)
        assertFalse(completed.state.isAgentTyping)
    }

    private fun withInterim(text: String = "Synthetic final answer"): ReducerResult {
        val sid = "previewed-final"
        val start =
            ChatWsEventReducer.reduce(
                ChatUiState(currentSessionId = sid),
                StreamingState(),
                WsEvent.MessageStart(sid),
                sid,
            )
        return ChatWsEventReducer.reduce(
            start.state,
            start.streamingState,
            WsEvent.MessageInterim(text, false, sid),
            sid,
        )
    }

    private fun completion(
        text: String = "Synthetic final answer",
        flag: String? = "true",
    ): WsEvent {
        val extra = flag?.let { ",\"response_previewed\":$it" }.orEmpty()
        val encoded =
            kotlinx.serialization.json
                .JsonPrimitive(text)
                .toString()
        return parse(
            """{"type":"message.complete","session_id":"previewed-final","payload":{"text":$encoded,"status":"complete"$extra}}""",
        )
    }

    @Test
    fun missingFalseAndMalformedPreviewFlagsPreserveIndependentEqualFinals() {
        for (flag in listOf(null, "false", "\"true\"")) {
            val interim = withInterim()
            val result =
                ChatWsEventReducer.reduce(
                    interim.state,
                    interim.streamingState,
                    completion(flag = flag),
                    "previewed-final",
                )
            assertEquals("Flag $flag must not authorize delivery reuse", 2, result.state.messages.size)
            assertEquals(
                2,
                result.state.messages
                    .map { it.id }
                    .distinct()
                    .size,
            )
        }
    }

    @Test
    fun previewFlagWithoutAnObservedInterimCannotDiscardTheFinal() {
        val result =
            ChatWsEventReducer.reduce(
                ChatUiState(currentSessionId = "previewed-final"),
                StreamingState(),
                completion(),
                "previewed-final",
            )
        assertEquals(
            "Synthetic final answer",
            result.state.messages
                .single()
                .content,
        )
        assertTrue(result.streamingState.turnEnded)
    }

    @Test
    fun aNewStartCannotReuseTheEarlierTurnsInterimCandidate() {
        val interim = withInterim()
        val oldId =
            interim.state.messages
                .single()
                .id
        val start =
            ChatWsEventReducer.reduce(
                interim.state,
                interim.streamingState,
                WsEvent.MessageStart("previewed-final"),
                "previewed-final",
            )
        val result = ChatWsEventReducer.reduce(start.state, start.streamingState, completion(), "previewed-final")
        assertEquals(2, result.state.messages.size)
        assertTrue(result.state.messages.any { it.id == oldId })
        assertEquals(
            2,
            result.state.messages
                .map { it.id }
                .distinct()
                .size,
        )
    }

    @Test
    fun differentFinalTextCannotSelectOlderProseByThePreviewFlagAlone() {
        val interim = withInterim()
        val result =
            ChatWsEventReducer.reduce(
                interim.state,
                interim.streamingState,
                completion("A different final"),
                "previewed-final",
            )
        assertEquals(listOf("Synthetic final answer", "A different final"), result.state.messages.map { it.content })
    }

    @Test
    fun independentEqualInterimsSurviveWhenOnlyTheLatestDeliveryIsPreviewed() {
        val first = withInterim()
        val second =
            ChatWsEventReducer.reduce(
                first.state,
                first.streamingState,
                WsEvent.MessageInterim("Synthetic final answer", false, "previewed-final"),
                "previewed-final",
            )
        val beforeIds = second.state.messages.map { it.id }
        val result = ChatWsEventReducer.reduce(second.state, second.streamingState, completion(), "previewed-final")
        assertEquals(beforeIds, result.state.messages.map { it.id })
        assertEquals(2, result.state.messages.size)
    }

    @Test
    fun selectedInterimIdentitySurvivesMediaDisplayStripping() {
        val raw = "Synthetic final answer\nMEDIA:/synthetic/private/report.pdf"
        val interim = withInterim(raw)
        val body = interim.state.messages.single()
        val stripped = body.copy(content = HostMediaExtractor.strip(raw), sourceBody = raw)
        val state = interim.state.copy(messages = listOf(stripped))
        val result = ChatWsEventReducer.reduce(state, interim.streamingState, completion(raw), "previewed-final")
        assertEquals(
            body.id,
            result.state.messages
                .single()
                .id,
        )
        assertEquals(
            raw,
            result.state.messages
                .single()
                .bodyForStorage(),
        )
        assertTrue(result.effects.any { it is ReducerEffect.AttachHostMedia && it.messageId == body.id })
    }

    @Test
    fun synthesizedStorageBodyDoesNotProveThatAFinalWasAlreadyDelivered() {
        val observed = "Original wording\nMEDIA:/synthetic/private/old.pdf"
        val interim = withInterim(observed)
        val selected = interim.state.messages.single()
        val edited = selected.copy(content = "Changed wording", sourceBody = observed)
        val state = interim.state.copy(messages = listOf(edited))
        val finalText = "Changed wording\nMEDIA:/synthetic/private/old.pdf"
        // Storage compatibility can synthesize this combination; it was never an observed source body.
        assertEquals(finalText, edited.bodyForStorage())
        val result = ChatWsEventReducer.reduce(state, interim.streamingState, completion(finalText), "previewed-final")
        assertEquals(2, result.state.messages.size)
        assertTrue(result.state.messages.any { it.id == selected.id && it.content == "Changed wording" })
        assertTrue(result.state.messages.any { it.id != selected.id && it.content == finalText })
    }

    @Test
    fun firstPreviewedTerminalStillAttachesRawMediaAndOnlyReplaySkipsEffects() {
        val raw = "Report\nMEDIA:/synthetic/private/report.pdf"
        val interim = withInterim(raw)
        val memberId =
            interim.state.messages
                .single()
                .id
        val first = ChatWsEventReducer.reduce(interim.state, interim.streamingState, completion(raw), "previewed-final")
        assertEquals(
            memberId,
            first.state.messages
                .single()
                .id,
        )
        assertEquals(
            1,
            first.effects.filterIsInstance<ReducerEffect.AttachHostMedia>().count { it.messageId == memberId },
        )
        assertEquals(1, first.effects.count { it is ReducerEffect.RefreshSessions })
        assertEquals(1, first.effects.count { it is ReducerEffect.RefreshContextUsage })
        assertEquals(
            1,
            first.effects.filterIsInstance<ReducerEffect.PersistMessage>().count { it.message.id == memberId },
        )
        assertTrue(first.streamingState.turnEnded)
        assertFalse(first.state.isAgentTyping)
        val replay = ChatWsEventReducer.reduce(first.state, first.streamingState, completion(raw), "previewed-final")
        assertEquals(first.state.messages, replay.state.messages)
        assertTrue("Only a genuine terminal replay skips completion effects", replay.effects.isEmpty())
    }
}
