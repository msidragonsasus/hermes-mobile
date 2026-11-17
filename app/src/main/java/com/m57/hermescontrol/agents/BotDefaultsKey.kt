package com.m57.hermescontrol.agents

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data class BotDefaultsKey(
    val profileName: String,
) : NavKey
