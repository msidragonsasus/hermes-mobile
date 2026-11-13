package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.ServerEndpoint
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayWebSocketProbeTest {
    @Test
    fun authenticatedGatewayReadyPreservesProxyPathAndCredentialEncoding() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(
                                webSocket: WebSocket,
                                response: Response,
                            ) {
                                webSocket.send("""{"method":"event","params":{"type":"gateway.ready","payload":{}}}""")
                            }
                        },
                    ),
                )
                val endpoint =
                    ServerEndpoint.parse(
                        server.url("/hermes/").toString(),
                        CleartextPolicy.ALLOW_WITH_WARNING,
                    )
                assertTrue(GatewayWebSocketProbe.check(endpoint, "ticket", "probe+/=", timeoutMs = 2_000))
                val request = server.takeRequest()
                assertEquals("/hermes/api/ws", request.requestUrl?.encodedPath)
                assertEquals("probe+/=", request.requestUrl?.queryParameter("ticket"))
            }
        }

    @Test
    fun rejectedUpgradeAndEarlyCloseBothFailConnectionCheck() =
        runBlocking {
            MockWebServer().use { server ->
                val endpoint = ServerEndpoint.parse(server.url("/").toString(), CleartextPolicy.ALLOW_WITH_WARNING)
                server.enqueue(MockResponse().setResponseCode(403))
                assertFalse(GatewayWebSocketProbe.check(endpoint, "token", "rejected", timeoutMs = 2_000))
                server.enqueue(
                    MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(
                                webSocket: WebSocket,
                                response: Response,
                            ) {
                                webSocket.close(1008, "Rejected")
                            }
                        },
                    ),
                )
                assertFalse(GatewayWebSocketProbe.check(endpoint, "token", "rejected", timeoutMs = 2_000))
            }
        }

    @Test
    fun idleUpgradeWithoutGatewayReadyTimesOut() =
        runBlocking {
            MockWebServer().use { server ->
                val endpoint = ServerEndpoint.parse(server.url("/").toString(), CleartextPolicy.ALLOW_WITH_WARNING)
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
                assertFalse(GatewayWebSocketProbe.check(endpoint, "token", "idle", timeoutMs = 200))
            }
        }
}
