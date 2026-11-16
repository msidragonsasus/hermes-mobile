package com.m57.hermescontrol.ui.chat.brainstorm

import com.m57.hermescontrol.data.model.BrainstormSnapshot
import com.m57.hermescontrol.data.model.BrainstormSpeakerSnapshot
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.ToolStatus
import com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry
import com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Pure parser, merger, and deduplicator for Hermes parallel brainstorm payloads (`hermes.brainstorm.v1`,
 * `message_agent` fan-out acknowledgements, and `/api/specialists/brainstorms` REST hydration).
 */
internal object BrainstormParser {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun isBrainstormToolMessage(message: ChatMessage): Boolean {
        if (message.role != MessageRole.TOOL) return false
        return parseFromToolMessage(message) != null
    }

    fun extractBrainstormId(message: ChatMessage): String? {
        if (message.role != MessageRole.TOOL) return null
        val obj = parseJsonObject(message.content) ?: return null
        return (obj["brainstorm_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    fun brainstormDedupeKey(message: ChatMessage): String? {
        val parsed = parseFromToolMessage(message) ?: return null
        return parsed.id
    }

    fun parseFromToolMessage(message: ChatMessage): BrainstormSnapshot? {
        if (message.role != MessageRole.TOOL) return null
        val obj = parseJsonObject(message.content) ?: return null
        return parseFromJsonObject(
            obj = obj,
            fallbackId = "inline-${message.id}",
            toolRunning = message.toolStatus == ToolStatus.RUNNING,
        )
    }

    fun parseFromJsonObject(
        obj: JsonObject?,
        fallbackId: String = "",
        toolRunning: Boolean = false,
    ): BrainstormSnapshot? {
        if (obj == null) return null
        val typeTag = (obj["type"] as? JsonPrimitive)?.contentOrNull
        val brainstormId = (obj["brainstorm_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val presentationError =
            (obj["presentation_error"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val speakersEl = obj["speakers"] as? JsonArray
        val repliesEl = obj["replies"] as? JsonArray
        val fanOutEl = obj["fan_out"] as? JsonArray

        val isBrainstormPayload =
            typeTag == "hermes.brainstorm.v1" ||
                brainstormId != null ||
                (presentationError == "brainstorm_snapshot_unavailable" && (repliesEl != null || fanOutEl != null))
        if (!isBrainstormPayload) return null

        val resolvedId = brainstormId ?: fallbackId.takeIf { it.isNotBlank() } ?: return null

        val parsedSpeakers =
            when {
                speakersEl != null && speakersEl.isNotEmpty() -> {
                    decodeSpeakers(speakersEl)
                }

                repliesEl != null && repliesEl.isNotEmpty() -> {
                    decodeRepliesOrFanOut(
                        repliesEl,
                        defaultStatus = "pending",
                    )
                }

                fanOutEl != null && fanOutEl.isNotEmpty() -> {
                    decodeRepliesOrFanOut(fanOutEl, defaultStatus = "queued")
                }

                else -> {
                    emptyList()
                }
            }

        val explicitStatus = (obj["status"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val inferredStatus =
            if (explicitStatus != null && explicitStatus != "queued") {
                explicitStatus
            } else {
                inferStatus(
                    speakers = parsedSpeakers,
                    topLevelStatus = explicitStatus,
                    toolRunning = toolRunning,
                )
            }

        return BrainstormSnapshot(
            id = resolvedId,
            session_id = (obj["session_id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            status = inferredStatus,
            speakers = parsedSpeakers,
            summary_input = (obj["summary_input"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            presentation_error = presentationError,
        )
    }

    fun mergeSnapshot(
        existing: BrainstormSnapshot?,
        incoming: BrainstormSnapshot?,
    ): BrainstormSnapshot? {
        if (existing == null) return incoming
        if (incoming == null) return existing
        if (existing.id != incoming.id) return incoming

        // Server `unknown` (interrupted / overdue) must not be masked by an earlier running ack.
        if (incoming.status == "unknown") {
            return incoming.copy(
                speakers = if (incoming.speakers.isNotEmpty()) incoming.speakers else existing.speakers,
                presentation_error = incoming.presentation_error ?: existing.presentation_error,
            )
        }
        if (existing.status == "unknown" && incoming.status == "running") {
            return existing
        }

        val preferIncoming =
            when {
                incoming.status in TERMINAL_STATUSES && existing.status == "running" -> true

                existing.status in TERMINAL_STATUSES && incoming.status == "running" -> false

                incoming.speakers.size > existing.speakers.size -> true

                incoming.speakers.count { it.reply.isNotBlank() || it.error.isNotBlank() } >=
                    existing.speakers.count { it.reply.isNotBlank() || it.error.isNotBlank() } -> true

                else -> false
            }

        val primary = if (preferIncoming) incoming else existing
        val secondary = if (preferIncoming) existing else incoming

        return primary.copy(
            session_id = primary.session_id.ifBlank { secondary.session_id },
            created_at = primary.created_at ?: secondary.created_at,
            finished_at = primary.finished_at ?: secondary.finished_at,
            speakers = if (primary.speakers.isNotEmpty()) primary.speakers else secondary.speakers,
            summary_input = primary.summary_input.ifBlank { secondary.summary_input },
            presentation_error = primary.presentation_error ?: secondary.presentation_error,
        )
    }

    fun collectMergedFromMessages(
        messages: List<ChatMessage>,
        hydratedById: Map<String, BrainstormSnapshot>,
    ): Map<String, BrainstormSnapshot> {
        if (messages.isEmpty() && hydratedById.isEmpty()) return emptyMap()
        val merged = linkedMapOf<String, BrainstormSnapshot>()
        hydratedById.forEach { (id, snap) ->
            merged[id] = snap
        }
        messages.forEach { msg ->
            val parsed = parseFromToolMessage(msg) ?: return@forEach
            val combined = mergeSnapshot(merged[parsed.id], parsed)
            if (combined != null) {
                merged[parsed.id] = combined
            }
        }
        return merged
    }

    fun resolveForToolMessage(
        message: ChatMessage,
        mergedById: Map<String, BrainstormSnapshot>,
    ): BrainstormSnapshot? {
        val parsed = parseFromToolMessage(message) ?: return null
        return mergeSnapshot(parsed, mergedById[parsed.id]) ?: parsed
    }

    /**
     * When multiple tool rows share one `brainstorm_id` (e.g., an acknowledgement row and a completion row
     * that did not share a tool_call_id), keep only the last row so the brainstorm card renders once,
     * immediately preceding Hermes's synthesis.
     */
    fun duplicateBrainstormMessageIds(turns: List<ChatTurn>): Set<String> {
        val lastMessageIdByBrainstormId = linkedMapOf<String, String>()
        val occurrences = mutableListOf<Pair<String, String>>()
        turns.forEach { turn ->
            if (turn is ChatTurn.Agent) {
                turn.entries.forEach { entry ->
                    if (entry is AgentEntry.ToolRow) {
                        val bid = brainstormDedupeKey(entry.message) ?: return@forEach
                        occurrences += entry.message.id to bid
                        lastMessageIdByBrainstormId[bid] = entry.message.id
                    }
                }
            }
        }
        if (occurrences.size <= lastMessageIdByBrainstormId.size) return emptySet()
        return occurrences
            .filter { (msgId, bid) -> lastMessageIdByBrainstormId[bid] != msgId }
            .mapTo(HashSet()) { it.first }
    }

    private fun parseJsonObject(raw: String): JsonObject? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
    }

    private fun decodeSpeakers(array: JsonArray): List<BrainstormSpeakerSnapshot> =
        array.mapNotNull { el ->
            runCatching { json.decodeFromJsonElement<BrainstormSpeakerSnapshot>(el) }.getOrNull()
        }

    private fun decodeRepliesOrFanOut(
        array: JsonArray,
        defaultStatus: String,
    ): List<BrainstormSpeakerSnapshot> =
        array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val toRaw = (obj["to"] as? JsonPrimitive)?.contentOrNull.orEmpty().removePrefix("@")
            val profile = (obj["profile"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: toRaw
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: profile
            val avatarEl: JsonElement? = obj["avatar"]
            val deliveryId = (obj["delivery_id"] as? JsonPrimitive)?.contentOrNull
            val rawError = (obj["error"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val rawDetail = (obj["detail"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val status =
                (obj["status"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: if (rawError.isNotBlank()) "failed" else defaultStatus
            val reply = (obj["reply"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val setupIncomplete = (obj["setup_incomplete"] as? JsonPrimitive)?.booleanOrNull
            BrainstormSpeakerSnapshot(
                profile = profile,
                name = name,
                avatar = avatarEl,
                delivery_id = deliveryId,
                status = status,
                reply = reply,
                error = rawError.ifBlank { if (status in setOf("failed", "pending", "cancelled")) rawDetail else "" },
                setup_incomplete = setupIncomplete,
            )
        }

    private fun inferStatus(
        speakers: List<BrainstormSpeakerSnapshot>,
        topLevelStatus: String?,
        toolRunning: Boolean,
    ): String {
        if (speakers.isEmpty()) {
            return if (toolRunning || topLevelStatus == "queued") "running" else "unknown"
        }
        val statuses = speakers.map { it.status }.toSet()
        if (toolRunning || "queued" in statuses) {
            return "running"
        }
        if ("pending" in statuses || "failed" in statuses || "cancelled" in statuses) {
            return "partial"
        }
        if (statuses.all { it == "settled" }) {
            return "completed"
        }
        return "unknown"
    }

    private val TERMINAL_STATUSES = setOf("completed", "partial", "unknown")
}
