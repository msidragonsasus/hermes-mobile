package com.m57.hermescontrol.vault

import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.toJsonElement
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Metadata only. The server never returns the password or authenticator key. */
@Serializable
data class VaultItem(
    val id: String,
    val kind: String,
    val label: String,
    val origin: String? = null,
    val identifier: String? = null,
    val identifier_type: String? = null,
    val has_otp: Boolean? = null,
    val backend: String = "local",
)

@Serializable
private data class VaultList(
    val items: List<VaultItem>,
)

/** Origin binding deliberately drops paths, queries and fragments; userinfo is refused. */
internal fun vaultOrigin(value: String): String? {
    val url = value.trim().toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return url
        .newBuilder()
        .encodedPath("/")
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .removeSuffix("/")
}

class VaultRepository(
    private val rpc: suspend (String, Map<String, Any>) -> Any?,
    private val isCurrent: () -> Boolean = { true },
) {
    constructor(scope: ConnectionScope) : this(
        rpc = { method, params -> HermesWsClient.requestVault(scope, method, params).await() },
        isCurrent = { HermesWsClient.matchesCurrentScope(scope) },
    )

    private suspend fun call(
        method: String,
        params: Map<String, Any> = emptyMap(),
    ): JsonObject {
        check(isCurrent()) { "Vault owner changed" }
        val raw = rpc(method, params)
        check(isCurrent()) { "Vault owner changed" }
        val payload =
            when (raw) {
                is JsonObject -> raw
                is Map<*, *> -> raw.toJsonElement() as JsonObject
                else -> error("Invalid vault response")
            }
        return payload["result"] as? JsonObject ?: payload
    }

    suspend fun list(): List<VaultItem> =
        OkHttpProvider.json
            .decodeFromJsonElement<VaultList>(call("vault.list"))
            .items
            .filter { it.kind == "login" }

    // The secret exists only in this in-flight call, never in saved UI state or a local store.
    suspend fun addLogin(
        label: String,
        origin: String,
        identifier: String,
        password: String,
        otp: String,
    ): String {
        require(label.isNotBlank() && identifier.isNotBlank() && password.isNotEmpty())
        val site = requireNotNull(vaultOrigin(origin))
        val secret =
            mutableMapOf(
                "identifier_type" to "username",
                "identifier" to identifier.trim(),
                "password" to password,
            )
        if (otp.isNotBlank()) secret["otp_secret"] = otp.trim()
        val result =
            call(
                "vault.add",
                mapOf(
                    "kind" to "login",
                    "label" to label.trim(),
                    "origin" to site,
                    "secret" to secret,
                ),
            )
        return result["id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: error("Missing vault acknowledgement")
    }

    suspend fun remove(item: VaultItem) {
        require(item.backend == "local")
        check(call("vault.remove", mapOf("id" to item.id))["removed"]?.jsonPrimitive?.booleanOrNull == true)
    }
}
