package com.m57.hermescontrol.domain.hermes

const val CLARIFICATION_DECLINED_ANSWER = "The user cancelled — no answer provided."

/** Server-provided text only. Missing impact/target/expiry must remain unknown in UI. */
data class AttentionDetails(
    val action: String? = null,
    val target: String? = null,
    val impact: String? = null,
    val reversibility: String? = null,
    val permissions: List<String> = emptyList(),
    val patterns: List<String> = emptyList(),
    val approvalChoices: List<String>? = null,
    val options: List<String> = emptyList(),
    val multiSelect: Boolean = false,
)

sealed interface AttentionSignal {
    data class Requested(
        val items: List<AttentionItem>,
    ) : AttentionSignal

    data class Expired(
        val scope: ConnectionScope,
        val runtimeId: String,
        val requestId: String,
    ) : AttentionSignal

    data class Gap(
        val scope: ConnectionScope,
    ) : AttentionSignal
}
