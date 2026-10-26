package com.m57.hermescontrol.openclaw

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.Proxy
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal enum class ClawConnection { DISCONNECTED, CONNECTING, CONNECTED }

internal class ClawFailure(
    val code: String,
    val definitive: Boolean = false,
) : Exception(code) {
    val userMessage: String get() =
        when (code) {
            "PAIRING_REQUIRED", "NOT_PAIRED" -> {
                "端末承認がまだ完了していません。OpenClawの管理画面で「Hermes Mobile」を承認し、再接続してください。"
            }

            "AUTH_TOKEN_MISMATCH", "AUTH_TOKEN_MISSING", "AUTH_PASSWORD_MISMATCH", "AUTH_REQUIRED" -> {
                "OpenClawの認証情報を確認してください。Hermesの認証情報とは別です。"
            }

            "FORBIDDEN", "AUTH_SCOPE_MISMATCH", "MISSING_SCOPE" -> {
                "この操作の権限がありません。OpenClaw側で端末の権限を確認してください。"
            }

            "NOT_SENT" -> {
                "送信できませんでした。接続を確認してください。"
            }

            "UNSUPPORTED" -> {
                "接続中のOpenClawはこの操作に対応していません。"
            }

            else -> {
                "OpenClawとの通信を確認できませんでした。再接続して履歴と設定を確認してください。"
            }
        }
}

/** One socket generation. No Hermes interceptors, cookie jar, request retries or log payloads. */
internal class OpenClawClient(
    private val endpoint: OpenClawEndpoint,
    private val identity: ClawIdentity,
    private val token: String?,
    private val requestedScopes: List<String> = listOf("operator.read", "operator.write", "operator.admin"),
    private val http: OkHttpClient = isolatedClient(endpoint),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val hello = CompletableDeferred<JsonObject>()
    private val eventFlow = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256)
    val events = eventFlow.asSharedFlow()
    private val connection = MutableStateFlow(ClawConnection.DISCONNECTED)
    val connectionState = connection.asStateFlow()

    @Volatile private var socket: WebSocket? = null

    @Volatile private var closed = false

    @Volatile private var challengeSent = false
    var methods: Set<String> = emptySet()
        private set
    var scopes: Set<String> = emptySet()
        private set

    suspend fun connect(): JsonObject {
        check(socket == null && !closed)
        connection.value = ClawConnection.CONNECTING
        return try {
            socket = http.newWebSocket(Request.Builder().url(endpoint.webSocketUrl).build(), listener)
            withTimeout(35_000) { hello.await() }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun supports(method: String): Boolean = connection.value == ClawConnection.CONNECTED && method in methods

    fun canWrite(): Boolean = "operator.write" in scopes || "operator.admin" in scopes

    fun canAdmin(): Boolean = "operator.admin" in scopes

    suspend fun rpc(
        method: String,
        params: JsonObject = obj(),
    ): JsonObject {
        if (connection.value != ClawConnection.CONNECTED) throw ClawFailure("DISCONNECTED", definitive = true)
        if (!supports(method)) throw ClawFailure("UNSUPPORTED", definitive = true)
        val id = UUID.randomUUID().toString()
        val response = CompletableDeferred<JsonObject>()
        pending[id] = response
        try {
            val frame = obj("type" to value("req"), "id" to value(id), "method" to value(method), "params" to params)
            if (socket?.send(frame.toString()) != true) throw ClawFailure("NOT_SENT", definitive = true)
            return withTimeout(20_000) { response.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun close() {
        fail(ClawFailure("DISCONNECTED"))
        socket?.cancel()
    }

    private fun fail(error: ClawFailure) {
        closed = true
        connection.value = ClawConnection.DISCONNECTED
        if (!hello.isCompleted) hello.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private val listener =
        object : WebSocketListener() {
            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                if (closed) return
                try {
                    val frame = json.parseToJsonElement(text) as? JsonObject ?: return
                    when (frame.string("type")) {
                        "event" -> {
                            if (frame.string("event") == "connect.challenge" && !challengeSent) {
                                challengeSent = true
                                val challenge = frame.obj("payload") ?: error("challenge")
                                val nonce = challenge.string("nonce")?.takeIf { it.isNotBlank() } ?: error("nonce")
                                val timestamp =
                                    (challenge["ts"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
                                        ?: error("timestamp")
                                val params =
                                    obj(
                                        "minProtocol" to JsonPrimitive(3),
                                        "maxProtocol" to JsonPrimitive(4),
                                        "client" to
                                            obj(
                                                "id" to value("openclaw-android"),
                                                "displayName" to value("Hermes Mobile"),
                                                "version" to value("1.0"),
                                                "platform" to value("android"),
                                                "mode" to value("ui"),
                                            ),
                                        "role" to value("operator"),
                                        "scopes" to JsonArray(requestedScopes.map(::JsonPrimitive)),
                                        "caps" to JsonArray(listOf(value("chat-only-assistant-text"))),
                                        "auth" to if (token.isNullOrBlank()) obj() else obj("token" to value(token)),
                                        "device" to
                                            obj(
                                                "id" to value(identity.id),
                                                "publicKey" to value(identity.publicKey),
                                                "signature" to
                                                    value(identity.sign(nonce, timestamp, token, requestedScopes)),
                                                "signedAt" to JsonPrimitive(timestamp),
                                                "nonce" to value(nonce),
                                            ),
                                    )
                                check(
                                    webSocket.send(
                                        obj(
                                            "type" to value("req"),
                                            "id" to value("connect"),
                                            "method" to value("connect"),
                                            "params" to params,
                                        ).toString(),
                                    ),
                                )
                            } else if (connection.value == ClawConnection.CONNECTED && !eventFlow.tryEmit(frame)) {
                                // Force a history resync instead of silently dropping transcript events.
                                webSocket.cancel()
                                fail(ClawFailure("EVENT_OVERFLOW"))
                            }
                        }

                        "res" -> {
                            val id = frame.string("id") ?: return
                            if ((frame["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
                                val error = frame.obj("error")
                                val failure =
                                    ClawFailure(
                                        error?.obj("details")?.string("code") ?: error?.string("code") ?: "RPC_ERROR",
                                        definitive = true,
                                    )
                                if (id ==
                                    "connect"
                                ) {
                                    hello.completeExceptionally(failure)
                                } else {
                                    pending.remove(id)?.completeExceptionally(failure)
                                }
                                return
                            }
                            val payload = frame.obj("payload") ?: obj()
                            if (id == "connect" && !hello.isCompleted) {
                                check(payload.string("type") == "hello-ok")
                                methods = strings(payload.obj("features")?.get("methods")).toSet()
                                scopes = strings(payload.obj("auth")?.get("scopes")).toSet()
                                connection.value = ClawConnection.CONNECTED
                                hello.complete(payload)
                            } else {
                                pending.remove(id)?.complete(payload)
                            }
                        }
                    }
                } catch (_: Exception) {
                    webSocket.cancel()
                    fail(ClawFailure("PROTOCOL_ERROR"))
                }
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) {
                fail(ClawFailure("DISCONNECTED"))
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                fail(ClawFailure("DISCONNECTED"))
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                webSocket.close(code, null)
            }
        }

    companion object {
        internal fun isolatedClient(
            endpoint: OpenClawEndpoint,
            dns: Dns = Dns.SYSTEM,
        ): OkHttpClient =
            OkHttpClient
                .Builder()
                .cookieJar(CookieJar.NO_COOKIES)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .apply { if (!endpoint.url.isHttps) proxy(Proxy.NO_PROXY) }
                .dns { host ->
                    dns.lookup(host).also { addresses ->
                        if (!endpoint.url.isHttps && (addresses.isEmpty() || addresses.any { !privateAddress(it) })) {
                            throw UnknownHostException("Private network required")
                        }
                    }
                }.build()
    }
}
