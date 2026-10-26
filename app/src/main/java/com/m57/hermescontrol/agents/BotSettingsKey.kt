package com.m57.hermescontrol.agents

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Bot management opened straight onto one bot's edit panel ("このbotの設定"). */
@Serializable
data class BotSettingsKey(
    val profileName: String,
) : NavKey
