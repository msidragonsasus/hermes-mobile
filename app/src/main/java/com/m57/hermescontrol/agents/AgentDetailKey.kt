package com.m57.hermescontrol.agents

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Store only the selected destination, never a Chat session or relay credential. */
@Serializable
data class AgentDetailKey(
    val agentId: String,
) : NavKey
