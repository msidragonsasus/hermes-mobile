package com.m57.hermescontrol.miniapp.contract

import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.navigation.ActivityScreen

data class NativeMiniAppFeature(
    val id: String,
    val defaultRoute: String,
    val routes: Map<String, NavKey>,
)

/** The host supplies compiled destinations. Manifest class names are never reflected or loaded. */
class NativeMiniAppFeatures(
    features: List<NativeMiniAppFeature>,
) {
    private val byId = features.associateBy { it.id }

    init {
        require(byId.size == features.size)
        require(features.all { it.defaultRoute in it.routes })
    }

    fun resolve(
        featureId: String,
        route: String?,
    ): NavKey? {
        val feature = byId[featureId] ?: return null
        return feature.routes[route ?: feature.defaultRoute]
    }

    companion object {
        /** Only real existing surfaces. The example Development business feature is not installed. */
        val builtIn =
            NativeMiniAppFeatures(
                listOf(
                    NativeMiniAppFeature("chat", "hermes/chat", mapOf("hermes/chat" to ChatScreen)),
                    NativeMiniAppFeature("activity", "shell/activity", mapOf("shell/activity" to ActivityScreen)),
                ),
            )
    }
}
