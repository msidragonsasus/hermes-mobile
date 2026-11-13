package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.EventParser
import com.m57.hermescontrol.data.ws.JsonRpcResponse
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TruncatedInterimDeliveryTest {
    @Test
    fun authoritativeInterimRepairsTheMissingTailOfItsAlreadyStreamedMember() {
        val session = "truncated-interim"
        val full = "そこで、ダウンロード待ちなのかインストール側の待ちなのかを切り分けられます。"
        val prefix = "そこで、ダウンロード待ちなの"
        val live = ChatMessage(id = "observed-live", role = MessageRole.ASSISTANT, content = prefix, isStreaming = true)
        val frame = """{"type":"message.interim","session_id":"$session","payload":{"text":${JsonPrimitive(
            full,
        )},"already_streamed":true}}"""
        val event =
            EventParser.parse(
                JsonRpcResponse(
                    jsonrpc = "2.0",
                    id = null,
                    result = null,
                    error = null,
                    method = "event",
                    params = Json.parseToJsonElement(frame).jsonObject,
                ),
            )
        val result =
            ChatWsEventReducer.reduce(
                ChatUiState(currentSessionId = session),
                StreamingState(streamingMessage = live),
                event,
                session,
            )
        assertEquals(
            "The full authoritative interim must repair this same streamed delivery, not seal its clipped prefix",
            full,
            result.state.messages
                .single()
                .content,
        )
        assertEquals(
            live.id,
            result.state.messages
                .single()
                .id,
        )
        assertEquals(
            full,
            result.state.messages
                .single()
                .bodyForStorage(),
        )
    }

    private fun interim(
        prefix: String,
        full: String,
        flag: String? = "true",
        sourceBody: String? = null,
        ended: Boolean = false,
    ): ReducerResult {
        val session = "truncated-interim"
        val live =
            ChatMessage(
                id = "observed-live",
                role = MessageRole.ASSISTANT,
                content = prefix,
                isStreaming = true,
                timestamp = 123L,
                sourceBody = sourceBody,
            )
        val extra = flag?.let { ",\"already_streamed\":$it" }.orEmpty()
        val frame = """{"type":"message.interim","session_id":"$session","payload":{"text":${JsonPrimitive(
            full,
        )}$extra}}"""
        val event =
            EventParser.parse(
                JsonRpcResponse(
                    jsonrpc = "2.0",
                    id = null,
                    result = null,
                    error = null,
                    method = "event",
                    params = Json.parseToJsonElement(frame).jsonObject,
                ),
            )
        return ChatWsEventReducer.reduce(
            ChatUiState(currentSessionId = session),
            StreamingState(streamingMessage = live, turnEnded = ended),
            event,
            session,
        )
    }

    @Test
    fun repairedPreviewIsNotDuplicatedByItsTerminalAndMediaEffectsAreNotLost() {
        val full = "Full answer.\nMEDIA:/synthetic/answer.apk"
        val sealed = interim("Full answer.", full)
        val terminal = WsEvent.MessageComplete(full, "truncated-interim", responsePreviewed = true)
        val done = ChatWsEventReducer.reduce(sealed.state, sealed.streamingState, terminal, "truncated-interim")
        assertEquals("The repaired preview and its complete event are one delivery", 1, done.state.messages.size)
        assertEquals(
            "observed-live",
            done.state.messages
                .single()
                .id,
        )
        assertEquals(
            full,
            done.state.messages
                .single()
                .bodyForStorage(),
        )
        assertEquals(1, done.effects.filterIsInstance<ReducerEffect.AttachHostMedia>().size)
        val replay = ChatWsEventReducer.reduce(done.state, done.streamingState, terminal, "truncated-interim")
        assertEquals(1, replay.state.messages.size)
        assertEquals(0, replay.effects.filterIsInstance<ReducerEffect.AttachHostMedia>().size)
    }

    @Test
    fun falseMissingAndMalformedFlagsKeepThePrefixAndFullTextAsIndependentDeliveries() {
        for (flag in listOf("false", null, "\"true\"")) {
            val result = interim("Prefix", "Prefix and independent body.", flag)
            assertEquals(listOf("Prefix", "Prefix and independent body."), result.state.messages.map { it.content })
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
    fun anEmptyFullSnapshotCannotEraseTheStreamedBody() {
        val result = interim("Preserved body", "")
        assertEquals(
            "Preserved body",
            result.state.messages
                .single()
                .content,
        )
        assertEquals(
            "observed-live",
            result.state.messages
                .single()
                .id,
        )
    }

    @Test
    fun incompatibleTextCannotOverwriteAnObservedLiveBody() {
        val result = interim("Independent observed text", "Different text")
        assertEquals(
            "Independent observed text",
            result.state.messages
                .single { it.id == "observed-live" }
                .content,
        )
    }

    @Test
    fun repairingOneMissingPunctuationPreservesTheStreamedIdAndCreationTime() {
        val result = interim("Complete sentence", "Complete sentence.")
        val message = result.state.messages.single()
        assertEquals("Complete sentence.", message.content)
        assertEquals("observed-live", message.id)
        assertEquals(123L, message.timestamp)
    }

    @Test
    fun observedRawMediaPrefixIsFilledAndRemainsTheSourceAfterDisplayStripping() {
        val rawPrefix = "Body\nMEDIA:/synthetic/first.apk"
        val full = "$rawPrefix\nAdditional text."
        val result = interim(HostMediaExtractor.strip(rawPrefix), full, sourceBody = rawPrefix)
        val message = result.state.messages.single()
        assertEquals(full, message.content)
        assertEquals(full, message.sourceBody)
        assertEquals(full, message.bodyForStorage())
        val stripped = message.copy(content = HostMediaExtractor.strip(full))
        assertEquals(full, stripped.bodyForStorage())
        assertEquals(0, result.effects.filterIsInstance<ReducerEffect.AttachHostMedia>().size)
    }

    @Test
    fun strippedProseAloneCannotConcealAConflictingMediaIdentity() {
        val first = "Body\nMEDIA:/synthetic/first.apk"
        val different = "Body\nMEDIA:/synthetic/second.apk\nAdditional text."
        val result = interim(HostMediaExtractor.strip(first), different, sourceBody = first)
        val message = result.state.messages.single { it.id == "observed-live" }
        assertEquals(HostMediaExtractor.strip(first), message.content)
        assertEquals(first, message.sourceBody)
        assertEquals(first, message.bodyForStorage())
    }

    @Test
    fun staleSourceHintCannotDonateItsOldMediaToTheRepairedDelivery() {
        val old = "Unrelated previous body\nMEDIA:/synthetic/old.apk"
        val full = "Current continuation\nMEDIA:/synthetic/new.apk"
        val result = interim("Current", full, sourceBody = old)
        val message = result.state.messages.single()
        assertEquals(full, message.content)
        assertEquals(full, message.sourceBody)
        assertEquals(full, message.bodyForStorage())
    }

    @Test
    fun equalAndShorterInterimsDoNotRewriteTheObservedBodyOrSource() {
        for (incoming in listOf("Complete body.", "Complete")) {
            val result = interim("Complete body.", incoming, sourceBody = "Unrelated source")
            assertEquals(
                "Complete body.",
                result.state.messages
                    .single()
                    .content,
            )
            assertEquals(
                "Unrelated source",
                result.state.messages
                    .single()
                    .sourceBody,
            )
        }
    }

    @Test
    fun anEndedTurnCannotAuthorizeNewTailRepair() {
        val result = interim("Existing", "Existing new tail", ended = true)
        assertEquals(
            "Existing",
            result.state.messages
                .single { it.id == "observed-live" }
                .content,
        )
        assertEquals(true, result.streamingState.turnEnded)
    }

    @Test
    fun repairedRawMediaSurvivesIndexedPersistStripCheckpointAndFreshRepositoryReopen() =
        runTest {
            val session = "truncated-interim"
            val target = "synthetic-target"
            val rawPrefix = "Body\nMEDIA:/synthetic/first.apk"
            val full = "$rawPrefix\nAdditional text."
            val sealed =
                interim(
                    HostMediaExtractor.strip(rawPrefix),
                    full,
                    sourceBody = rawPrefix,
                ).state.messages.single()
            val dao = FakeChatMessageDao()
            val repository = ChatPersistenceRepository(dao)
            val (owner, _) = repository.openTranscript(target, session)
            assertEquals(true, repository.save(repository.prepare(owner, listOf(sealed), ResponseLedger())))
            val stripped = sealed.copy(content = HostMediaExtractor.strip(full))
            assertEquals(true, repository.save(repository.prepare(owner, listOf(stripped), ResponseLedger())))
            val fresh = ChatPersistenceRepository(dao)
            val (_, reopened) = fresh.openTranscript(target, session)
            assertEquals(1, reopened.messages.size)
            assertEquals("observed-live", reopened.messages.single().id)
            assertEquals(full, reopened.messages.single().bodyForStorage())
            assertEquals(123L, reopened.messages.single().timestamp)
        }

    @Test
    fun alreadyStreamedWithoutALiveMemberPreservesTheExistingNewDeliveryPath() {
        val result =
            ChatWsEventReducer.reduce(
                ChatUiState(currentSessionId = "truncated-interim"),
                StreamingState(),
                WsEvent.MessageInterim("Whole body", true, "truncated-interim"),
                "truncated-interim",
            )
        assertEquals(
            "Whole body",
            result.state.messages
                .single()
                .content,
        )
    }
}
