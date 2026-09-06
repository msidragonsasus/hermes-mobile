package com.m57.hermescontrol.miniapp.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Offline camelCase manifest contract. A decoded declaration grants no runtime permission. */
@Serializable
data class MiniAppManifest(
    val schemaVersion: String,
    val id: String,
    val name: Map<String, String>,
    val version: String,
    val publisher: MiniAppPublisher,
    val entry: MiniAppEntry,
    val permissions: List<String>,
    val description: Map<String, String>? = null,
    val minHostVersion: String? = null,
    val icon: MiniAppIcon? = null,
    val contributions: MiniAppContributions = MiniAppContributions(),
    val network: MiniAppNetwork = MiniAppNetwork(),
    val offline: MiniAppOffline = MiniAppOffline(),
    val integrity: MiniAppIntegrity? = null,
)

@Serializable
data class MiniAppPublisher(
    val id: String,
    val name: String,
)

@Serializable
sealed interface MiniAppEntry {
    @Serializable
    @SerialName("native")
    data class Native(
        val featureId: String,
        val initialRoute: String? = null,
    ) : MiniAppEntry

    @Serializable
    @SerialName("sdui")
    data class Sdui(
        val documentUrl: String,
        val initialRoute: String,
        val componentVersion: String = "1.0",
    ) : MiniAppEntry

    @Serializable
    @SerialName("web")
    data class Web(
        val url: String,
        val allowedOrigins: List<String>,
        val messageProtocolVersion: String = "1.0",
    ) : MiniAppEntry
}

@Serializable
sealed interface MiniAppIcon {
    @Serializable
    @SerialName("material")
    data class Material(
        val name: String,
    ) : MiniAppIcon

    @Serializable
    @SerialName("remote")
    data class Remote(
        val url: String,
        val sha256: String? = null,
    ) : MiniAppIcon
}

@Serializable
data class MiniAppContributions(
    val homeCards: List<MiniAppRouteContribution> = emptyList(),
    val commands: List<MiniAppCommandContribution> = emptyList(),
    val composerActions: List<MiniAppRouteContribution> = emptyList(),
    val activityRenderers: List<MiniAppRouteContribution> = emptyList(),
    val settingsSections: List<MiniAppRouteContribution> = emptyList(),
    val notificationRoutes: List<MiniAppRouteContribution> = emptyList(),
)

@Serializable
data class MiniAppRouteContribution(
    val id: String,
    val route: String,
    val priority: Int = 0,
    val renderer: String? = null,
)

@Serializable
data class MiniAppCommandContribution(
    val id: String,
    val label: Map<String, String>,
    val action: String,
    val description: Map<String, String>? = null,
    val keywords: List<String> = emptyList(),
)

@Serializable
data class MiniAppNetwork(
    val allowedOrigins: List<String> = emptyList(),
)

@Serializable
enum class MiniAppCachePolicy {
    @SerialName("none")
    NONE,

    @SerialName("metadata")
    METADATA,

    @SerialName("content")
    CONTENT,
}

@Serializable
data class MiniAppOffline(
    val cachePolicy: MiniAppCachePolicy = MiniAppCachePolicy.NONE,
    val maxAgeSeconds: Int = 0,
    val allowDrafts: Boolean = false,
)

@Serializable
data class MiniAppIntegrity(
    val manifestSha256: String? = null,
    val bundleSha256: String? = null,
    val signature: String? = null,
    val keyId: String? = null,
)
