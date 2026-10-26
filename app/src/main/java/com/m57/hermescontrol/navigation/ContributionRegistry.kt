package com.m57.hermescontrol.navigation

import androidx.navigation3.runtime.NavKey
import kotlin.reflect.KClass

/** Construction is atomic: ambiguous IDs, routes or Navigation3 key types never enter the registry. */
class ContributionRegistry(
    contributions: List<AppContribution>,
) {
    val destinations: List<DestinationMetadata>

    init {
        require(contributions.all { it.id.isNotBlank() }) { "Contribution ID must not be blank" }
        require(contributions.map { it.id }.distinct().size == contributions.size) { "Duplicate contribution ID" }
        val declared = contributions.flatMap { it.destinations }
        require(
            declared.all { it.id.isNotBlank() && it.route.isNotBlank() },
        ) { "Destination identity must not be blank" }
        require(declared.map { it.id }.distinct().size == declared.size) { "Duplicate destination ID" }
        require(declared.map { it.route }.distinct().size == declared.size) { "Duplicate destination route" }
        require(declared.map { it.keyType }.distinct().size == declared.size) { "Duplicate destination key type" }
        require(declared.all { it.screen == null || it.screen.key::class == it.keyType }) { "Screen key type mismatch" }
        // Preserve declaration order for equal priorities, including across contributions.
        destinations = declared.sortedBy { it.order }
    }

    fun forRoute(route: String): DestinationMetadata? = destinations.firstOrNull { it.route == route }

    fun forKey(keyType: KClass<out NavKey>): DestinationMetadata? = destinations.firstOrNull { it.keyType == keyType }
}
