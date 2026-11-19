package com.m57.hermescontrol.data.local

/** Owner Apps share the main Hermes profile within the selected authenticated connection. */
internal object AppScope {
    const val PROFILE = AuthManager.DEFAULT_PROFILE_ID

    // Keep the persisted main-profile keys and exclude caches previously written by specialists.
    fun connectionKey(): String = connectionKey(AuthManager.baseUrlFlow.value, AuthManager.selectedProfileFlow.value)

    fun connectionKey(
        baseUrl: String,
        connectionId: String?,
    ): String = listOf(baseUrl, connectionId.orEmpty(), PROFILE).joinToString("\n")
}
