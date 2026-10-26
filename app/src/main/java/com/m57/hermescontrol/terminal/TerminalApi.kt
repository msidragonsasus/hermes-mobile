package com.m57.hermescontrol.terminal

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
data class TerminalStatus(
    val api_version: Int,
    val enabled: Boolean,
    val user: String? = null,
)

@Serializable
data class TerminalSessionInfo(
    val id: String,
    val closed: Boolean,
)

@Serializable
data class TerminalSessions(
    val sessions: List<TerminalSessionInfo>,
)

@Serializable
data class TerminalOutput(
    val id: String,
    val start: Long,
    val next: Long,
    val truncated: Boolean,
    val data: String,
    val closed: Boolean,
    val last_input: Long,
    val input_state: String,
)

@Serializable
data class TerminalInputReceipt(
    val sequence: Long,
    val state: String,
)

/** An HTTP error answer from the terminal API. Network failures stay plain IOExceptions. */
internal class TerminalHttpException(
    val code: Int,
    message: String,
) : IOException(message)

/**
 * Shell input, passwords and login codes must not travel in clear text. Plain http is accepted
 * only on loopback and Tailscale (WireGuard-encrypted) addresses.
 */
internal fun terminalTransportAllowed(
    https: Boolean,
    host: String,
): Boolean {
    if (https) return true
    val name = host.lowercase().trimEnd('.')
    if (name == "localhost" || name == "127.0.0.1" || name == "::1" || name == "[::1]") return true
    if (name.endsWith(".ts.net")) return true
    val parts = name.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    // Tailscale CGNAT range 100.64.0.0/10.
    return octets[0] == 100 && octets[1] in 64..127
}

internal class TerminalApi(
    private val scope: String,
) {
    private val endpoint = AuthManager.endpointForBuild()
    private val profile = AuthManager.activeProfileId.value
    val serverLabel: String = "${endpoint.baseUrl.host}:${endpoint.baseUrl.port}"

    // No logging, redirects, refresh/retry or application-level replay for shell input.
    private val client =
        OkHttpProvider.base
            .newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    fun checkScope() {
        check(scope == AuthManager.uiPreferenceScope()) { "接続先が変わりました。開き直してください。" }
        check(AuthManager.isGatedMode()) { "ターミナルにはDashboardへのログインが必要です。" }
        check(terminalTransportAllowed(endpoint.baseUrl.isHttps, endpoint.baseUrl.host)) {
            "暗号化されていない接続(http)ではターミナルを使えません。httpsかTailscale経由で接続してください。"
        }
    }

    inline fun <reified T> call(
        method: String,
        path: String,
        body: String? = null,
        offset: Long? = null,
    ): T {
        checkScope()
        val url = endpoint.baseUrl.newBuilder().addPathSegments("api/plugins/mobile-terminal/$path")
        if (profile != null) url.addQueryParameter("profile", profile)
        if (offset != null) url.addQueryParameter("offset", offset.toString())
        val request =
            Request
                .Builder()
                .url(url.build())
                .method(method, body?.toRequestBody("application/json".toMediaType()))
                .build()
        val result =
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw TerminalHttpException(
                        response.code,
                        when (response.code) {
                            401, 403 -> "Dashboardへのログインと権限を確認してください。"
                            404 -> "サーバーのターミナル機能、またはセッションが見つかりません。"
                            503 -> "サーバー側のターミナルが無効です。管理者による導入が必要です。"
                            429 -> "セッションの上限です。不要なセッションを終了してください。"
                            else -> "ターミナルの操作結果を確認できません。"
                        },
                    )
                }
                val source = response.body ?: throw IOException("応答がありません。")
                source.source().request(262145)
                if (source.source().buffer.size > 262144) throw IOException("応答が大きすぎます。")
                val text = source.source().readUtf8()
                OkHttpProvider.json.decodeFromString<T>(text)
            }
        checkScope()
        return result
    }
}
