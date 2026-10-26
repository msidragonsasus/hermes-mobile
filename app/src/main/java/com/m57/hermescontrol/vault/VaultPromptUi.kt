package com.m57.hermescontrol.vault

import com.m57.hermescontrol.data.ws.VaultPromptRequest
import com.m57.hermescontrol.domain.hermes.ConnectionScope

/** Transient request identity and display metadata; never contains entered credentials. */
data class VaultPromptUi(
    val scope: ConnectionScope,
    val request: VaultPromptRequest,
    val sendFailed: Boolean = false,
)
