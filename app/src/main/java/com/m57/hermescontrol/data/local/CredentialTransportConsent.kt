package com.m57.hermescontrol.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

internal fun credentialTransportEndpoint(value: String): HttpUrl? {
    val parsed = value.toHttpUrlOrNull() ?: return null
    if (parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
        parsed.encodedPath != "/" || parsed.encodedQuery != null || parsed.encodedFragment != null ||
        value !in setOf(parsed.toString(), parsed.toString().removeSuffix("/"))
    ) {
        return null
    }
    val octets = parsed.host.split('.')
    if (octets.size != 4 || octets.any { it.toIntOrNull()?.takeIf { number -> number in 0..255 }?.toString() != it }) {
        return null
    }
    if (octets[0] != "100" || octets[1].toInt() !in 64..127) return null
    return parsed
}

internal data class CredentialTransportState(
    val scope: String,
    val endpoint: String?,
    val generation: Long,
    val grantId: String?,
    val serverProfile: String = "default",
    val configuredEndpoint: String = endpoint.orEmpty(),
) {
    val allowedEndpoint: String? get() = endpoint?.takeIf { grantId != null }
}

internal class CredentialTransportConsent(
    private val read: () -> String?,
    private val write: (String?) -> Unit,
) {
    @Serializable
    private data class Grant(
        val scope: String,
        val endpoint: String,
        val id: String,
    )

    private var loaded = false
    private var grant: Grant? = null
    private var generation = 0L
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()
    private var observedScope: String? = null
    private var revocationPending = false

    @Synchronized
    fun reset() {
        loaded = false
        grant = null
        observedScope = null
        revocationPending = false
        generation++
        _changes.value = generation
    }

    private fun load(): Grant? {
        if (!loaded) {
            grant = read()?.let { runCatching { Json.decodeFromString<Grant>(it) }.getOrNull() }
            loaded = true
        }
        return grant
    }

    @Synchronized
    fun invalidateContext() {
        generation++
        revocationPending = revocationPending || load() != null
        grant = null
        _changes.value = generation
        if (revocationPending) {
            write(null)
            revocationPending = false
        }
    }

    @Synchronized
    fun state(
        scope: String,
        endpoint: String,
        serverProfile: String = "default",
    ): CredentialTransportState {
        val changed = observedScope != null && observedScope != scope
        observedScope = scope
        if (changed) invalidateContext()
        val eligible = credentialTransportEndpoint(endpoint)?.toString()
        val current = load()?.takeIf { it.scope == scope && it.endpoint == eligible && it.id.isNotBlank() }
        return CredentialTransportState(scope, eligible, generation, current?.id, serverProfile, endpoint)
    }

    @Synchronized
    fun enable(
        expected: CredentialTransportState,
        scope: String,
        endpoint: String,
        serverProfile: String = expected.serverProfile,
    ): CredentialTransportState {
        check(
            expected == state(scope, endpoint, serverProfile) && expected.endpoint != null,
        ) { "接続先が変わりました。設定を開き直してください。" }
        val next = Grant(scope, checkNotNull(expected.endpoint), UUID.randomUUID().toString())
        write(Json.encodeToString(next))
        grant = next
        loaded = true
        revocationPending = false
        generation++
        _changes.value = generation
        return state(scope, endpoint, serverProfile)
    }
}
