package com.m57.hermescontrol.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A stalled upgrade must fail instead of leaving the client CONNECTING forever. */
class WebSocketHandshakeTimeoutTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): OkHttpClient =
        OkHttpProvider.configureWebSocket(OkHttpClient.Builder(), handshakeTimeoutMs = HANDSHAKE_MS).build()

    private fun request(): Request = Request.Builder().url(server.url("/api/ws")).build()

    @Test
    fun productionClientBoundsTheHandshake() {
        val built = OkHttpProvider.configureWebSocket(OkHttpClient.Builder()).build()
        assertEquals(OkHttpProvider.WS_HANDSHAKE_TIMEOUT_MS.toInt(), built.callTimeoutMillis)
        assertEquals(0, built.readTimeoutMillis)
    }

    @Test
    fun stalledUpgradeFailsWithinHandshakeTimeout() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val failed = CountDownLatch(1)
        val opened = AtomicBoolean(false)
        client().newWebSocket(
            request(),
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    opened.set(true)
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    failed.countDown()
                }
            },
        )
        assertTrue(failed.await(5, TimeUnit.SECONDS), "stalled upgrade never reported failure")
        assertTrue(!opened.get())
    }

    @Test
    fun establishedSocketOutlivesHandshakeTimeout() {
        val received = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (text == "after-timeout") received.countDown()
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        webSocket.close(code, null)
                    }
                },
            ),
        )
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        val socket =
            client().newWebSocket(
                request(),
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        opened.countDown()
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        closed.countDown()
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        failed.set(true)
                    }
                },
            )
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        Thread.sleep(HANDSHAKE_MS * 3)
        assertTrue(!failed.get(), "call timeout tore down an established socket")
        assertTrue(socket.send("after-timeout"))
        assertTrue(received.await(5, TimeUnit.SECONDS))
        socket.close(1000, null)
        assertTrue(closed.await(5, TimeUnit.SECONDS))
    }

    private companion object {
        const val HANDSHAKE_MS = 500L
    }
}
