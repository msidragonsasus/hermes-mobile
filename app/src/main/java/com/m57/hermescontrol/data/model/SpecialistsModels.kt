package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class SpecialistCapabilitiesResponse(
    val version: Int = 1,
    val setup: Boolean = false,
    val brainstorm_results: Boolean = false,
    val group_retirement_ready: Boolean = false,
    val server_generated_id: Boolean = false,
    val setup_conversation: Boolean = false,
    val setup_apply: Boolean = false,
    val edit_conversation: Boolean = false,
) {
    val supportsSetupChat: Boolean
        get() = setup_conversation && setup_apply
}

@Serializable
data class SpecialistTemplate(
    val id: String,
    val title: String,
    val description: String,
)

@Serializable
data class SpecialistTemplatesResponse(
    val templates: List<SpecialistTemplate> = emptyList(),
)

@Serializable
data class CreateSpecialistRequest(
    val name: String? = null,
    val title: String,
    val avatar: BotAvatarMeta? = null,
    val template_id: String = "blank",
    val request_id: String,
)

@Serializable
data class CreateSpecialistResponse(
    val name: String,
    val setup_completed: Boolean = false,
    val setup: SpecialistSetupSnapshot? = null,
)

@Serializable
data class SpecialistSetupDraft(
    val id: String,
    val description: String = "",
    val soul: String = "",
    val skills: List<String> = emptyList(),
    val autonomy: String = "",
    val routines: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val kind: String = "setup",
    val before: SpecialistSettingsPreview? = null,
    val after: SpecialistSettingsPreview? = null,
)

@Serializable
data class SpecialistSettingsPreview(
    val description: String = "",
    val sources: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val autonomy: String = "",
    val routines: List<String> = emptyList(),
    val soul: String = "",
)

@Serializable
data class SpecialistSetupMessage(
    val id: String,
    val role: String,
    val content: String,
)

@Serializable
data class SpecialistSetupTurn(
    val request_id: String,
    val session_id: String,
    val mode: String = "setup",
    val conversation_revision: Int,
    val revision: Int,
    val user: SpecialistSetupMessage,
    val assistant: SpecialistSetupMessage,
    val card: SpecialistSetupDraft? = null,
)

@Serializable
data class SpecialistPendingTurn(
    val request_id: String,
    val message: String,
    val expires_at: Double,
)

@Serializable
data class SpecialistSetupTurnRequest(
    val request_id: String,
    val session_id: String,
    val expected_revision: Int,
    val expected_conversation_revision: Int,
    val text: String,
    val mode: String,
)

@Serializable
data class SpecialistSetupApplyRequest(
    val request_id: String,
    val session_id: String,
    val expected_revision: Int,
)

@Serializable
data class SpecialistSetupApplyResponse(
    val activation: String,
    val session_id: String,
    val settings_revision: Int,
    val applied_revision: Int,
)

@Serializable
data class SpecialistSetupSnapshot(
    val name: String,
    val setup_completed: Boolean = false,
    val revision: Int = 0,
    val draft: SpecialistSetupDraft? = null,
    val questions: List<String> = emptyList(),
    val available_skills: List<String> = emptyList(),
    val activation: String = "awaiting_confirmation",
    val routing_ready: Boolean = false,
    val settings_revision: Int? = null,
    val applied_revision: Int? = null,
    val conversation_revision: Int = 0,
    val greeting: SpecialistSetupMessage? = null,
    val conversation_turns: List<SpecialistSetupTurn> = emptyList(),
    val pending_turn: SpecialistPendingTurn? = null,
) {
    val isNextSessionBoundaryActivation: Boolean
        get() = activation == "next_session_boundary"
}

@Serializable
data class SpecialistSetupDraftRequest(
    val expected_revision: Int,
    val role: String,
    val sources: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val autonomy: String,
    val routines: List<String> = emptyList(),
)

@Serializable
data class SpecialistSetupConfirmRequest(
    val expected_revision: Int,
    val draft_id: String,
    val request_id: String,
)

@Serializable
data class BrainstormSpeakerSnapshot(
    val profile: String = "",
    val name: String = "",
    val avatar: JsonElement? = null,
    val delivery_id: String? = null,
    val status: String = "pending",
    val reply: String = "",
    val error: String = "",
    val setup_incomplete: Boolean? = null,
) {
    fun resolvedAvatar(json: Json = DEFAULT_JSON): BotAvatarMeta? {
        val obj = avatar as? JsonObject ?: return null
        return runCatching { json.decodeFromJsonElement<BotAvatarMeta>(obj) }.getOrNull()
    }

    val displayName: String
        get() = name.ifBlank { profile }

    companion object {
        private val DEFAULT_JSON =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
    }
}

@Serializable
data class BrainstormSnapshot(
    val id: String,
    val session_id: String = "",
    val status: String = "unknown",
    val created_at: Double? = null,
    val finished_at: Double? = null,
    val speakers: List<BrainstormSpeakerSnapshot> = emptyList(),
    val summary_input: String = "",
    val presentation_error: String? = null,
)

@Serializable
data class BrainstormsListResponse(
    val items: List<BrainstormSnapshot> = emptyList(),
)
