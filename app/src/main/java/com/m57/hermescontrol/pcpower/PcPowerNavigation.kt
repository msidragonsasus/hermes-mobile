package com.m57.hermescontrol.pcpower

import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.navigation.AppContribution
import com.m57.hermescontrol.navigation.DestinationMetadata
import kotlinx.serialization.Serializable

@Serializable
data object PcPowerScreenKey : NavKey

class PcPowerContribution : AppContribution {
    override val id = "pc-power.native"
    override val destinations =
        listOf(
            DestinationMetadata("pc-power.home", "pc-power/home", PcPowerScreenKey::class, 1200),
        )
}
