package com.m57.hermescontrol.openclaw

import com.m57.hermescontrol.data.model.EnvVarRevealRequest
import com.m57.hermescontrol.data.remote.ApiClient

/** Reuse the existing authenticated key-management API. The server key stays in memory only. */
internal suspend fun existingOpenRouterTitleKey(): String? {
    val api = ApiClient.hermesApi
    val metadata = api.getEnvVars()
    if (!metadata.isSuccessful || metadata.body()?.get("OPENROUTER_API_KEY")?.isSet != true) return null
    val response = api.revealEnvVar(EnvVarRevealRequest("OPENROUTER_API_KEY"))
    if (!response.isSuccessful) return null
    return response
        .body()
        ?.takeIf { it.key == "OPENROUTER_API_KEY" }
        ?.value
        ?.takeIf { it.isNotBlank() }
}

internal suspend fun resolveClawTitleKey(
    localKey: String?,
    ownerIsCurrent: () -> Boolean,
    enabled: Boolean = true,
    readExisting: suspend () -> String? = ::existingOpenRouterTitleKey,
): String? {
    if (!enabled || !ownerIsCurrent()) return null
    val key = localKey?.takeIf { it.isNotBlank() } ?: readExisting()
    return key?.takeIf { it.isNotBlank() && ownerIsCurrent() }
}
