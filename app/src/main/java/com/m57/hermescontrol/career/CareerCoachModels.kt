package com.m57.hermescontrol.career

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class CareerTarget(
    val kind: String,
    val id: String,
    val version: Int? = null,
    val source_refs: List<CareerSourceRef> = emptyList(),
)

@Serializable
data class CareerSourceRef(
    val kind: String,
    val id: String,
    val account_id: String? = null,
)

@Serializable
data class CareerExecution(
    val provider: String,
    val model: String,
    val reasoning_effort: String,
)

@Serializable
data class CareerModelOption(
    val provider: String,
    val id: String,
    val label: String = id,
    val reasoning_efforts: List<String> = emptyList(),
    val source: String = "unknown",
    val inference_verified: Boolean = false,
)

@Serializable
data class CareerDailySettings(
    val version: Int = 0,
    val execution: CareerExecution? = null,
    val source: String = "server_default",
    val reason: String? = null,
)

@Serializable
data class CareerWorkCapability(
    val id: String,
    val state: String,
    val missing_company_ids: List<String> = emptyList(),
)

@Serializable
data class CareerProviderError(
    val provider: String,
    val reason: String,
)

@Serializable
data class CareerCapabilities(
    val plugin_version: String = "",
    val api_version: Int = 2,
    val features: List<String> = emptyList(),
    val models: List<CareerModelOption> = emptyList(),
    val provider_errors: List<CareerProviderError> = emptyList(),
    val state: String = "checking",
    val reason: String? = null,
    val checked_at: String = "",
    val default_execution: CareerExecution? = null,
    val daily_settings: CareerDailySettings = CareerDailySettings(),
    val work: List<CareerWorkCapability> = emptyList(),
) {
    fun supports(execution: CareerExecution): Boolean =
        models.any {
            it.provider == execution.provider && it.id == execution.model &&
                execution.reasoning_effort in it.reasoning_efforts
        }
}

@Serializable
data class CareerArtifact(
    val id: String,
    val version: Int,
    val entity_version: Int = 0,
    val title: String,
    val path: String,
    val preview: String = "",
    val characters: Int = 0,
    val server_saved: Boolean = false,
    val github_synced: Boolean = false,
)

@Serializable
data class CareerCoachMessage(
    val id: String,
    val run_id: String,
    val role: String,
    val text: String,
    val seq: Long = 0,
    val created_at: String = "",
)

@Serializable
data class CareerCoachRun(
    val id: String,
    val kind: String,
    val state: String,
    val result: JsonObject = JsonObject(emptyMap()),
    val cancel_requested: Int = 0,
    val created_at: String = "",
    val execution: CareerExecution? = null,
    val artifacts: List<CareerArtifact> = emptyList(),
) {
    val active: Boolean get() = state in setOf("queued", "running", "waiting_user", "paused")
}

@Serializable
data class CareerCoachSnapshot(
    val api_version: Int = 2,
    val profile: String = "default",
    val messages: List<CareerCoachMessage> = emptyList(),
    val runs: List<CareerCoachRun> = emptyList(),
    val cursor: Long = 0,
    val older_before: Long? = null,
    val checkpoint: JsonObject = JsonObject(emptyMap()),
    val checked_at: String = "",
    val thread_id: String = "global",
    val archived: Boolean = false,
    val shared_context: JsonObject = JsonObject(emptyMap()),
    val sync: JsonObject = JsonObject(emptyMap()),
    val company_context: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class CareerCoachEvent(
    val seq: Long,
    val run_id: String,
    val type: String,
    val data: JsonObject,
    val created_at: String = "",
)

@Serializable
data class CareerCoachEvents(
    val events: List<CareerCoachEvent> = emptyList(),
    val cursor: Long = 0,
    val more: Boolean = false,
    val thread_id: String = "global",
)

@Serializable
data class CareerCoachCache(
    val snapshot: CareerCoachSnapshot? = null,
    val draft: String = "",
    val target: CareerTarget? = null,
    val pending: CareerPendingOperation? = null,
    val execution: CareerExecution? = null,
    val capabilities: CareerCapabilities? = null,
    val revisionDrafts: Map<String, String> = emptyMap(),
    val answerContext: JsonObject? = null,
    val historyExpanded: Boolean = false,
)

data class CareerCoachUiState(
    val initialized: Boolean = false,
    val readOnly: Boolean = false,
    val snapshot: CareerCoachSnapshot? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val stale: Boolean = true,
    val draft: String = "",
    val target: CareerTarget? = null,
    val pending: CareerPendingOperation? = null,
    val progress: Map<String, String> = emptyMap(),
    val error: String? = null,
    val execution: CareerExecution? = null,
    val capabilities: CareerCapabilities? = null,
    val capabilitiesChecked: Boolean = false,
    val capabilitiesError: String? = null,
    val dailySettings: CareerDailySettings? = null,
    val revisionDrafts: Map<String, String> = emptyMap(),
    val answerContext: JsonObject? = null,
    val loadingOlder: Boolean = false,
    val historyExpanded: Boolean = false,
    val localWritable: Boolean = true,
)
