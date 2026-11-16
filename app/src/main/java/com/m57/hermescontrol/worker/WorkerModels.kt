package com.m57.hermescontrol.worker

import com.m57.hermescontrol.data.ws.toAny
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

@Serializable
data class InboxRef(
    val job_id: String? = null,
    val execution_id: String? = null,
    val escrow_id: String? = null,
    val digest: String? = null,
    val deny_only: Boolean = false,
)

@Serializable
data class InboxItem(
    val seq: Long,
    val id: String,
    val kind: String,
    val title: String,
    val body: String = "",
    val ref: InboxRef? = null,
    val created_at: String = "",
    val acked_at: String? = null,
) {
    val isResult: Boolean get() = kind == KIND_RESULT
    val isDiagnostic: Boolean get() = kind == KIND_DIAGNOSTIC
    val isApproval: Boolean get() = kind == KIND_APPROVAL
    val isMemoryDiff: Boolean get() = kind == KIND_MEMORY_DIFF

    companion object {
        const val KIND_RESULT = "result"
        const val KIND_DIAGNOSTIC = "diagnostic"
        const val KIND_APPROVAL = "approval"
        const val KIND_MEMORY_DIFF = "memory_diff"
    }
}

@Serializable
data class InboxResponse(
    val items: List<InboxItem> = emptyList(),
    val latest_seq: Long = 0L,
)

@Serializable
data class AckResponse(
    val ok: Boolean = false,
)

@Serializable
data class EscrowDisplay(
    val title: String = "",
    val command: String? = null,
    val description: String? = null,
    val impact: String? = null,
    val alternative: String? = null,
    val deny_only: Boolean = false,
)

@Serializable
data class EscrowResult(
    val ok: Boolean = false,
    val summary: String? = null,
    val at: String? = null,
)

@Serializable
data class EscrowRecord(
    val schema_version: Int = 1,
    val id: String,
    val status: String = "pending",
    val job_id: String? = null,
    val job_name: String? = null,
    val execution_id: String? = null,
    val profile: String? = null,
    val kind: String = "command",
    val tool_name: String? = null,
    val display: EscrowDisplay = EscrowDisplay(),
    val digest: String,
    val created_at: String = "",
    val expires_at: String? = null,
    val deliver: String? = null,
    val result: EscrowResult? = null,
)

@Serializable
data class EscrowsResponse(
    val items: List<EscrowRecord> = emptyList(),
)

@Serializable
data class EscrowDecisionRequest(
    val decision: String,
    val digest: String,
)

@Serializable
data class EscrowDecisionResponse(
    val ok: Boolean = false,
    val reason: String? = null,
    val status: String? = null,
)

@Serializable
data class PushRegisterRequest(
    val token: String,
    val device_label: String = "",
)

@Serializable
data class PushUnregisterRequest(
    val token: String,
)

enum class EscrowDecision(
    val wireValue: String,
) {
    APPROVE("approve"),
    DENY("deny"),
}

data class TicklePayload(
    val type: String,
    val version: String,
    val seq: Long?,
)

data class WorkerScheduledJob(
    val id: String,
    val name: String,
    val scheduleText: String,
    val enabled: Boolean,
    val nextRunAt: String?,
    val lastRunStatus: String?,
)

data class WorkerSubagent(
    val subagentId: String,
    val status: String,
    val goal: String?,
    val model: String?,
)

object WorkerJson {
    val codec = Json { ignoreUnknownKeys = true }

    fun decodeInboxResponse(raw: String): InboxResponse = codec.decodeFromString(InboxResponse.serializer(), raw)

    fun decodeEscrowsResponse(raw: String): EscrowsResponse = codec.decodeFromString(EscrowsResponse.serializer(), raw)
}

object WorkerPushProtocol {
    const val TICKLE_TYPE = "hermes.tickle"
    const val SUPPORTED_VERSION = "1"

    fun parseTickle(data: Map<String, String>): TicklePayload? {
        val type = data["t"]?.trim() ?: return null
        if (type != TICKLE_TYPE) return null
        val version = data["v"]?.trim().orEmpty().ifEmpty { SUPPORTED_VERSION }
        if (version != SUPPORTED_VERSION) return null
        val seq = data["seq"]?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()
        return TicklePayload(type = type, version = version, seq = seq)
    }
}

object WorkerDecisionRequests {
    fun build(
        decision: EscrowDecision,
        digest: String?,
        denyOnly: Boolean,
    ): EscrowDecisionRequest? {
        if (digest.isNullOrBlank()) return null
        if (denyOnly && decision == EscrowDecision.APPROVE) return null
        return EscrowDecisionRequest(
            decision = decision.wireValue,
            digest = digest,
        )
    }
}

object WorkerWsParsers {
    private val ACTIVE_SUBAGENT_STATES = setOf("running", "queued", "steered")

    /** WS results arrive as [JsonElement] from the live client and as plain maps in tests. */
    private fun plain(result: Any?): Any? = if (result is JsonElement) result.toAny() else result

    fun parseCronManageJobs(raw: Any?): List<WorkerScheduledJob> {
        val rows =
            when (val result = plain(raw)) {
                is Map<*, *> -> (result["jobs"] as? List<*>) ?: (result["items"] as? List<*>) ?: emptyList<Any?>()
                is List<*> -> result
                else -> return emptyList()
            }
        return rows.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val name = (map["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val id =
                ((map["id"] ?: map["job_id"]) as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: name
            val state = (map["state"] as? String)?.lowercase()
            val enabled =
                when (val rawEnabled = map["enabled"]) {
                    is Boolean -> rawEnabled
                    else -> state != "paused" && state != "disabled"
                }
            val scheduleObj = map["schedule"] as? Map<*, *>
            val scheduleText =
                (scheduleObj?.get("display") as? String)
                    ?: (map["schedule_display"] as? String)
                    ?: (map["schedule"] as? String)
                    ?: (scheduleObj?.get("expr") as? String)
                    ?: ""
            val nextRunAt = (map["next_run"] as? String) ?: (map["next_run_at"] as? String)
            val lastStatus = (map["last_run_status"] as? String) ?: (map["last_status"] as? String)
            WorkerScheduledJob(
                id = id,
                name = name,
                scheduleText = scheduleText,
                enabled = enabled,
                nextRunAt = nextRunAt,
                lastRunStatus = lastStatus,
            )
        }
    }

    fun parseSubagents(result: Any?): List<WorkerSubagent> {
        val rows = (plain(result) as? Map<*, *>)?.get("subagents") as? List<*> ?: return emptyList()
        return rows.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val id = (map["subagent_id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val status = (map["status"] as? String)?.lowercase() ?: return@mapNotNull null
            if (status !in ACTIVE_SUBAGENT_STATES) return@mapNotNull null
            WorkerSubagent(
                subagentId = id,
                status = status,
                goal = (map["goal"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                model = (map["model"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

object WorkerInboxFormatters {
    private val tokyoZone: ZoneId = ZoneId.of("Asia/Tokyo")

    fun isCreatedOnTokyoDate(
        isoTimestamp: String,
        targetDate: LocalDate = LocalDate.now(tokyoZone),
    ): Boolean {
        if (isoTimestamp.isBlank()) return false
        return runCatching {
            OffsetDateTime.parse(isoTimestamp).atZoneSameInstant(tokyoZone).toLocalDate() == targetDate
        }.getOrDefault(false)
    }

    fun notificationPreview(body: String): String {
        val cleaned =
            body
                .lineSequence()
                .map { line ->
                    line
                        .trim()
                        .removePrefix("#")
                        .removePrefix("- ")
                        .removePrefix("* ")
                        .trim()
                }.firstOrNull { it.isNotEmpty() }
                .orEmpty()
        return cleaned.take(140)
    }
}
