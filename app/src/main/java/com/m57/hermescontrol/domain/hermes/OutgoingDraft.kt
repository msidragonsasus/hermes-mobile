package com.m57.hermescontrol.domain.hermes

import com.m57.hermescontrol.data.model.Attachment
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** UNKNOWN means a write may have reached Hermes. It is never a retry instruction. */
@Serializable
enum class DraftState { DRAFT, UNKNOWN }

@Serializable
data class OutgoingDraft(
    val id: String,
    val scope: ConnectionScope,
    val savedSessionId: String?,
    val runtimeSessionId: String?,
    val text: String,
    val attachments: List<Attachment>,
    val createdAt: Long,
    val state: DraftState = DraftState.DRAFT,
) {
    /** Stable across socket generations and process recreation, distinct across profiles. */
    val targetKey: String
        get() = scope.draftTargetKey()
}

fun ConnectionScope.draftTargetKey(): String =
    Json.encodeToString(listOf(serverIdentity, connectionProfileId, serverProfileId))
