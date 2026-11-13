package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.toEntity
import com.m57.hermescontrol.data.local.toUiModel
import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompactionDisplayProjectionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun compositeCarrierUsesProjectedAskThroughHistoryAndCache() {
        val raw = "[CONTEXT COMPACTION — REFERENCE ONLY] internal summary\nREAL ASK"
        val row =
            json.decodeFromJsonElement<SessionMessage>(
                buildJsonObject {
                    put("id", 23)
                    put("role", "user")
                    put("content", raw)
                    put("display_content", "REAL ASK")
                },
            )
        val mapped = mapServerMessages("session", listOf(row), 0, true, emptyList()).single()

        assertEquals("REAL ASK", mapped.content)
        assertEquals("rest-session-23", mapped.id)
        assertEquals(raw, row.contentText)
        assertEquals("REAL ASK", mapped.toEntity("session").toUiModel().content)
    }

    @Test
    fun refreshedProjectionWinsOverStaleSameIdCacheBody() {
        val row = projectedRow("REAL ASK")
        val projected = mapServerMessages("session", listOf(row), 0, true, emptyList()).single()
        val stale = projected.copy(content = "[CONTEXT COMPACTION — REFERENCE ONLY] old cached summary")
        val merged = mergeTranscriptWithLive(listOf(projected), listOf(stale)).single()
        assertEquals("REAL ASK", merged.content)
        assertEquals("REAL ASK", merged.toEntity("session").toUiModel().content)
    }

    @Test
    fun projectedCarrierCannotLeakPhysicalReasoningOrCachedReasoning() {
        val row = projectedRow("REAL ASK", reasoning = "internal physical reasoning")
        val cached = ChatMessage(role = MessageRole.USER, content = "REAL ASK", reasoningText = "stale reasoning")
        val mapped = mapServerMessages("session", listOf(row), 0, true, listOf(cached)).single()
        assertEquals("", mapped.reasoningText)
    }

    @Test
    fun blankProjectionNeverFallsBackToPhysicalContent() {
        for (projection in listOf(JsonPrimitive(""), JsonArray(emptyList()))) {
            val row = projectedRow(projection)
            val mapped = mapServerMessages("session", listOf(row), 0, true, emptyList()).single()
            assertEquals("", mapped.content)
            assertEquals("", mapped.toEntity("session").toUiModel().content)
        }
    }

    @Test
    fun nullAndAbsentProjectionKeepLegacyContent() {
        for (projection in listOf<JsonElement?>(null, JsonNull)) {
            val mapped = mapServerMessages("session", listOf(projectedRow(projection)), 0, true, emptyList()).single()
            assertEquals("physical body", mapped.content)
        }
    }

    @Test
    fun hiddenAndModelOnlyOverwriteSameIdCacheWithoutReasoningDonation() {
        for (fields in listOf(
            buildJsonObject { put("display_kind", "hidden") },
            buildJsonObject { put("display_metadata", buildJsonObject { put("model_only", true) }) },
            buildJsonObject { put("display_metadata", "{\"model_only\":true}") },
        )) {
            val row =
                json.decodeFromJsonElement<SessionMessage>(
                    buildJsonObject {
                        put("id", 23)
                        put("role", "assistant")
                        put("content", "internal model copy")
                        put("reasoning", "secret internal reasoning")
                        put("display_content", "must still stay hidden")
                        fields.forEach { (key, value) -> put(key, value) }
                    },
                )
            val visible = SessionMessage(id = 24, role = "assistant", content = JsonPrimitive("answer"))
            val mapped = mapServerMessages("session", listOf(row, visible), 0, true, emptyList())
            assertEquals(2, mapped.size)
            assertEquals("rest-session-23", mapped[0].id)
            assertEquals("hidden", mapped[0].displayKind)
            assertEquals("", mapped[0].content)
            assertEquals("", mapped[0].reasoningText)
            assertEquals("", mapped[1].reasoningText)
            assertEquals("hidden", mapped[0].toEntity("session").toUiModel().displayKind)
        }
    }

    @Test
    fun modelOnlyRequiresRealBooleanAndMalformedMetadataDoesNotHide() {
        for (metadata in listOf(
            buildJsonObject { put("model_only", "true") },
            JsonPrimitive("broken json"),
            JsonPrimitive(1),
        )) {
            val row =
                json.decodeFromJsonElement<SessionMessage>(
                    buildJsonObject {
                        put("id", 23)
                        put("role", "user")
                        put("content", "real user text")
                        put("display_metadata", metadata)
                    },
                )
            val mapped = mapServerMessages("session", listOf(row), 0, true, emptyList()).single()
            assertEquals("real user text", mapped.content)
            assertNull(mapped.displayKind)
        }
    }

    @Test
    fun projectedToolContentIsNotReplacedByLiveRawToolCopy() {
        val row =
            json.decodeFromJsonElement<SessionMessage>(
                buildJsonObject {
                    put("id", 23)
                    put("role", "tool")
                    put("tool_call_id", "call1")
                    put("content", "physical tool output")
                    put("display_content", "safe projected tool output")
                },
            )
        val live = ChatMessage(role = MessageRole.TOOL, content = "physical tool output", toolCallId = "call1")
        val mapped = mapServerMessages("session", listOf(row), 0, true, listOf(live)).single()
        assertEquals("safe projected tool output", mapped.content)
    }

    @Test
    fun unknownSummaryPrefixInsideOrdinaryUserQuestionIsNotHidden() {
        val text = "What does [CONTEXT COMPACTION — REFERENCE ONLY] mean?"
        val row = SessionMessage(id = 23, role = "user", content = JsonPrimitive(text))
        val mapped = mapServerMessages("session", listOf(row), 0, true, emptyList()).single()
        assertEquals(text, mapped.content)
        assertNull(mapped.displayKind)
    }

    @Test
    fun projectedAssistantCannotReceiveOrphanPhysicalReasoning() {
        val reasonOnly =
            SessionMessage(
                id = 21,
                role = "assistant",
                content = JsonPrimitive(""),
                reasoning = JsonPrimitive("orphan reasoning"),
            )
        val projected =
            json.decodeFromJsonElement<SessionMessage>(
                buildJsonObject {
                    put("id", 23)
                    put("role", "assistant")
                    put("content", "physical summary")
                    put("display_content", "visible text")
                },
            )
        val mapped = mapServerMessages("session", listOf(reasonOnly, projected), 0, true, emptyList()).single()
        assertEquals("visible text", mapped.content)
        assertEquals("", mapped.reasoningText)
    }

    @Test
    fun hiddenCachedRowCannotDonateReasoningToVisibleAnswer() {
        val cached =
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "answer",
                reasoningText = "hidden reasoning",
                displayKind = "hidden",
            )
        val visible = SessionMessage(id = 23, role = "assistant", content = JsonPrimitive("answer"))
        val mapped = mapServerMessages("session", listOf(visible), 0, true, listOf(cached)).single()
        assertEquals("", mapped.reasoningText)
    }

    @Test
    fun hiddenToolIdentitySuppressesOlderLiveRawCopyDuringMerge() {
        val row =
            json.decodeFromJsonElement<SessionMessage>(
                buildJsonObject {
                    put("id", 23)
                    put("role", "tool")
                    put("tool_call_id", "hidden-call")
                    put("content", "raw internal result")
                    put("display_kind", "hidden")
                },
            )
        val live =
            ChatMessage(
                id = "live-tool",
                role = MessageRole.TOOL,
                content = "raw internal result",
                toolCallId = "hidden-call",
            )
        val mapped = mapServerMessages("session", listOf(row), 0, true, listOf(live))
        val merged = mergeTranscriptWithLive(mapped, listOf(live))
        assertEquals(1, merged.size)
        assertEquals("hidden", merged.single().displayKind)
        assertEquals("", merged.single().content)
    }

    @Test
    fun displayAuthorityAndHiddenStateAreMeaningfulMessageChanges() {
        val old = ChatMessage(id = "same", role = MessageRole.USER, content = "")
        assertEquals(false, sameMessages(listOf(old), listOf(old.copy(displayKind = "hidden"))))
        assertEquals(false, sameMessages(listOf(old), listOf(old.copy(hasServerDisplayProjection = true))))
    }

    private fun projectedRow(
        projection: String,
        reasoning: String = "",
    ): SessionMessage = projectedRow(JsonPrimitive(projection), reasoning)

    private fun projectedRow(
        projection: JsonElement?,
        reasoning: String = "",
    ): SessionMessage =
        json.decodeFromJsonElement(
            buildJsonObject {
                put("id", 23)
                put("role", "user")
                put("content", "physical body")
                put("reasoning", reasoning)
                if (projection != null) put("display_content", projection)
            },
        )
}
