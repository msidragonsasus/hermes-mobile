package com.m57.hermescontrol.navigation

import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ScreenDefinition
import kotlin.reflect.KClass

/** Built-in destinations only. Content and specialized entry installers remain in the existing app. */
data class DestinationMetadata(
    val id: String,
    val route: String,
    val keyType: KClass<out NavKey>,
    val order: Int,
    val screen: ScreenDefinition? = null,
)

interface AppContribution {
    val id: String
    val destinations: List<DestinationMetadata>
}
