package com.m57.hermescontrol.terminal

import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.navigation.AppContribution
import com.m57.hermescontrol.navigation.DestinationMetadata
import kotlinx.serialization.Serializable

@Serializable
data object TerminalScreenKey : NavKey

class TerminalContribution : AppContribution {
    override val id = "terminal.native"
    override val destinations =
        listOf(DestinationMetadata("terminal.home", "terminal/home", TerminalScreenKey::class, 1300))
}
