package com.m57.hermescontrol.openclaw

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

@Serializable data object OpenClawChatKey : NavKey

@Serializable data object OpenClawSettingsKey : NavKey

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

internal fun strings(value: JsonElement?): List<String> =
    (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

internal fun obj(vararg values: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*values))

internal fun value(text: String?): JsonElement = text?.let(::JsonPrimitive) ?: JsonNull

/** OpenClaw uses camelCase and req/res/event frames, independently of Hermes JSON-RPC. */
internal data class OpenClawEndpoint(
    val url: HttpUrl,
) {
    init {
        val host = url.host
        if (!url.isHttps && (':' in host || host.all { it in '0'..'9' || it == '.' })) {
            if (':' !in host) {
                val components = host.split('.')
                require(
                    components.size == 4 &&
                        components.all {
                            it.isNotEmpty() && (it.length == 1 || it.first() != '0') &&
                                (it.toIntOrNull() ?: -1) in 0..255
                        },
                ) { "Private network required" }
            }
            require(runCatching { privateAddress(InetAddress.getByName(host)) }.getOrDefault(false)) {
                "Private network required"
            }
        }
    }

    val webSocketUrl: String get() =
        url.toString().replaceFirst(
            if (url.isHttps) "https:" else "http:",
            if (url.isHttps) "wss:" else "ws:",
        )

    companion object {
        fun parse(input: String): OpenClawEndpoint {
            val raw = input.trim()
            require(
                raw.startsWith("ws://") || raw.startsWith("wss://") || raw.startsWith("http://") ||
                    raw.startsWith("https://"),
            )
            val url =
                raw.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").toHttpUrlOrNull()
                    ?: error("GatewayのURLを確認してください")
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "URLには認証情報・クエリ・フラグメントを含められません"
            }
            return OpenClawEndpoint(url)
        }
    }
}

/** Rechecked on DNS resolution too; plaintext credentials must stay on the private network. */
internal fun privateAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true
    val bytes = address.address
    return (bytes.size == 4 && bytes[0].toInt() and 255 == 100 && bytes[1].toInt() and 192 == 64) ||
        (bytes.size == 16 && bytes[0].toInt() and 254 == 252)
}

internal data class ClawMessage(
    val id: String,
    val role: String,
    val text: String,
)

internal data class ClawSession(
    val key: String,
    val title: String,
)

internal data class ClawModel(
    val id: String,
    val name: String,
)

/** Sequence numbers are per run; never compare the next run against the previous run's counter. */
internal class ClawStreamOrder {
    private val sequences = linkedMapOf<Pair<String, String>, Long>()

    fun accept(
        selectedSession: String,
        payload: JsonObject,
    ): Boolean {
        val session = payload.string("sessionKey") ?: return false
        val run = payload.string("runId") ?: return false
        val sequence = (payload["seq"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return false
        if (session != selectedSession || sequence < 0) return false
        val key = session to run
        if (sequence <= (sequences[key] ?: -1)) return false
        sequences[key] = sequence
        if (sequences.size > 128) sequences.remove(sequences.keys.first())
        return true
    }
}

internal fun messageText(message: JsonObject?): String {
    if (message == null) return ""
    val content = message["content"]
    if (content is JsonPrimitive) return content.contentOrNull.orEmpty()
    return (content as? JsonArray)
        ?.mapNotNull { part ->
            (part as? JsonObject)?.takeIf { it.string("type") == "text" }?.string("text")
        }?.joinToString("\n")
        .orEmpty()
        .ifBlank { message.string("text").orEmpty() }
}

/** Sparse, hash-guarded config patch for one agent; preserve every sibling/credential. */
internal fun agentDefaultsPatch(
    config: JsonObject,
    agentId: String,
    model: String?,
    effort: String?,
): JsonObject {
    require(model != null || effort != null)
    val fields =
        buildMap<String, JsonElement> {
            model?.let { put("model", obj("primary" to value(it))) }
            effort?.let { put("thinkingDefault", value(it)) }
        }
    val agents = config.obj("agents") ?: error("OpenClawのagent設定を読み込めません")
    val entries = agents.obj("entries")
    if (entries != null && entries[agentId] is JsonObject) {
        return obj("agents" to obj("entries" to obj(agentId to JsonObject(fields))))
    }
    val list = agents["list"] as? JsonArray
    if (list != null && list.any { (it as? JsonObject)?.string("id") == agentId }) {
        return obj(
            "agents" to
                obj(
                    "list" to
                        JsonArray(
                            list.map { item ->
                                val row = item as? JsonObject
                                if (row?.string("id") == agentId) {
                                    val updates = fields.toMutableMap()
                                    if (model != null &&
                                        row.obj("model") != null
                                    ) {
                                        updates["model"] =
                                            JsonObject(row.obj("model")!! + mapOf("primary" to value(model)))
                                    }
                                    JsonObject(row + updates)
                                } else {
                                    item
                                }
                            },
                        ),
                ),
        )
    }
    // No per-agent roster means the single configured agent inherits shared defaults.
    require(entries == null && list == null) { "対象のagent設定が見つかりません" }
    return obj("agents" to obj("defaults" to JsonObject(fields)))
}
