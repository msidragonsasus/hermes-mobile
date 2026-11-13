package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.ServerEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CookieJar
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Tests the authenticated gateway on a disposable socket before switching the live connection. */
internal object GatewayWebSocketProbe {
    private val client by lazy {
        OkHttpProvider.websocket
            .newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun check(
        endpoint: ServerEndpoint,
        authParameter: String,
        credential: String,
        timeoutMs: Long = 10_000,
    ): Boolean {
        val ready = CompletableDeferred<Boolean>()
        val socket =
            client.newWebSocket(
                Request.Builder().url(endpoint.webSocketUrl(authParameter, credential)).build(),
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        val frame =
                            runCatching {
                                OkHttpProvider.json.decodeFromString<JsonRpcResponse>(
                                    text,
                                )
                            }.getOrNull()
                        if (frame?.id == null && frame?.method == "event" &&
                            frame.params?.get("type") == "gateway.ready".toJsonElement()
                        ) {
                            ready.complete(true)
                        }
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        ready.complete(false)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        ready.complete(false)
                    }
                },
            )
        return try {
            withTimeoutOrNull(timeoutMs) { ready.await() } == true
        } finally {
            socket.cancel()
        }
    }
}
