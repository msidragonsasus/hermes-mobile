package com.m57.hermescontrol.data.ws

/** Non-secret metadata only. The answer is never part of a chat message or saved state. */
data class VaultPromptRequest(
    val id: String,
    val sessionId: String,
    val kind: VaultPromptKind,
    val origin: String? = null,
    val site: String? = null,
    val hint: String? = null,
)

enum class VaultPromptKind(
    val method: String,
) {
    LOGIN("vault.save_login"),
    CODE("vault.code"),
    UNLOCK("vault.unlock_prompt"),
}
