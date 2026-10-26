package com.m57.hermescontrol.openclaw

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Dns
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OpenClawClientTest {
    private class Gateway(
        private val reject: Boolean = false,
    ) : WebSocketListener() {
        val received = LinkedBlockingQueue<JsonObject>()
        lateinit var socket: WebSocket

        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            socket = webSocket
            webSocket.send(
                """{"type":"event","event":"connect.challenge","payload":{"nonce":"test-nonce","ts":1792339200000}}""",
            )
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            val frame = Json.parseToJsonElement(text) as JsonObject
            received.add(frame)
            if (frame.string("method") == "connect") {
                if (reject) {
                    webSocket.send(
                        """
                        {
                          "type": "res",
                          "id": "connect",
                          "ok": false,
                          "error": {
                            "code": "NOT_PAIRED",
                            "details": {
                              "code": "PAIRING_REQUIRED"
                            },
                            "message": "do not display secret echoed by server"
                          }
                        }
                        """.trimIndent(),
                    )
                } else {
                    webSocket.send(
                        """
                        {
                          "type": "res",
                          "id": "connect",
                          "ok": true,
                          "payload": {
                            "type": "hello-ok",
                            "protocol": 4,
                            "features": {
                              "methods": [
                                "chat.history",
                                "chat.send",
                                "sessions.patch"
                              ]
                            },
                            "auth": {
                              "scopes": [
                                "operator.read",
                                "operator.write"
                              ]
                            }
                          }
                        }
                        """.trimIndent(),
                    )
                }
            } else {
                webSocket.send(
                    obj(
                        "type" to value("res"),
                        "id" to value(frame.string("id")),
                        "ok" to kotlinx.serialization.json.JsonPrimitive(true),
                        "payload" to obj("messages" to JsonArray(emptyList())),
                    ).toString(),
                )
            }
        }
    }

    @Test
    fun realWebSocketUsesSignedHandshakeAndCorrelatesRpcWithoutHermesAuth() =
        runBlocking {
            val server = MockWebServer()
            val gateway = Gateway()
            server.enqueue(MockResponse().withWebSocketUpgrade(gateway))
            server.start()
            val client =
                OpenClawClient(
                    OpenClawEndpoint.parse(server.url("/").toString()),
                    ClawIdentity(ByteArray(32)),
                    "openclaw-secret",
                )
            try {
                withTimeout(5000) { client.connect() }
                val connect = gateway.received.poll(1, TimeUnit.SECONDS)!!
                assertEquals("req", connect.string("type"))
                val params = connect.obj("params")!!
                assertEquals("operator", params.string("role"))
                assertEquals("openclaw-secret", params.obj("auth")!!.string("token"))
                assertEquals("test-nonce", params.obj("device")!!.string("nonce"))
                assertNotNull(params.obj("device")!!.string("signature"))
                assertFalse(client.canAdmin())
                assertTrue(client.canWrite())
                val history =
                    withTimeout(5000) { client.rpc("chat.history", obj("sessionKey" to value("agent:main:main"))) }
                assertTrue(history.array("messages").isEmpty())
                assertEquals("chat.history", gateway.received.poll(1, TimeUnit.SECONDS)!!.string("method"))
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals(null, request.getHeader("Authorization"))
                assertEquals(null, request.getHeader("Cookie"))
                assertFalse(client.supports("config.patch"))
            } finally {
                client.close()
                server.shutdown()
            }
        }

    @Test
    fun plaintextDnsPolicyRejectsPublicMixedAndEmptyResultsOnEveryLookup() {
        val privateResults =
            listOf("127.0.0.1", "192.168.1.5", "100.64.0.1", "fd7a:115c:a1e0::1").map(InetAddress::getByName)
        val publicResults = listOf("8.8.8.8", "2001:4860:4860::8888").map(InetAddress::getByName)
        var results = privateResults
        val client =
            OpenClawClient.isolatedClient(
                OpenClawEndpoint.parse("ws://gateway.example.invalid/"),
                Dns { results },
            )
        assertEquals(privateResults, client.dns.lookup("gateway.example.invalid"))
        listOf(publicResults, privateResults + publicResults, emptyList()).forEach { rejected ->
            results = rejected
            assertThrows(UnknownHostException::class.java) { client.dns.lookup("gateway.example.invalid") }
        }
        results = privateResults
        assertEquals(privateResults, client.dns.lookup("gateway.example.invalid"))
    }

    @Test
    fun secureDnsPolicyKeepsPublicGatewayAddresses() {
        val addresses = listOf("8.8.8.8", "2001:4860:4860::8888").map(InetAddress::getByName)
        val client =
            OpenClawClient.isolatedClient(
                OpenClawEndpoint.parse("wss://gateway.example.invalid/"),
                Dns { addresses },
            )
        assertEquals(addresses, client.dns.lookup("gateway.example.invalid"))
    }

    @Test
    fun plaintextWebSocketsStayDirectUnderHttpAndSocksSystemProxies() =
        runBlocking {
            val previousSelector = ProxySelector.getDefault()
            try {
                listOf(Proxy.Type.HTTP, Proxy.Type.SOCKS).forEach { proxyType ->
                    val server = MockWebServer()
                    val proxyServer = MockWebServer()
                    val gateway = Gateway()
                    server.enqueue(MockResponse().withWebSocketUpgrade(gateway))
                    server.start(InetAddress.getByName("127.0.0.1"), 0)
                    proxyServer.start(InetAddress.getByName("127.0.0.1"), 0)
                    val selections = AtomicInteger()
                    val selectedUris = LinkedBlockingQueue<URI>()
                    val selector =
                        object : ProxySelector() {
                            override fun select(uri: URI): List<Proxy> {
                                if (uri.scheme !in setOf("http", "https")) return listOf(Proxy.NO_PROXY)
                                selections.incrementAndGet()
                                selectedUris.add(uri)
                                return listOf(Proxy(proxyType, InetSocketAddress("127.0.0.1", proxyServer.port)))
                            }

                            override fun connectFailed(
                                uri: URI,
                                socketAddress: SocketAddress,
                                error: IOException,
                            ) = Unit
                        }
                    ProxySelector.setDefault(selector)
                    val endpoint = OpenClawEndpoint.parse(server.url("/").toString())
                    val client = OpenClawClient(endpoint, ClawIdentity(ByteArray(32)), "openclaw-secret")
                    try {
                        assertEquals(Proxy.NO_PROXY, OpenClawClient.isolatedClient(endpoint).proxy)
                        val secureClient =
                            OpenClawClient.isolatedClient(
                                OpenClawEndpoint(
                                    endpoint.url
                                        .newBuilder()
                                        .scheme("https")
                                        .build(),
                                ),
                            )
                        assertEquals(null, secureClient.proxy)
                        assertTrue(secureClient.proxySelector === selector)
                        withTimeout(5000) { client.connect() }
                        assertEquals("connect", gateway.received.poll(1, TimeUnit.SECONDS)!!.string("method"))
                        assertEquals(1, server.requestCount)
                        assertEquals("Selected routes: $selectedUris", 0, selections.get())
                        assertEquals(0, proxyServer.requestCount)
                    } finally {
                        client.close()
                        server.shutdown()
                        proxyServer.shutdown()
                    }
                }
            } finally {
                ProxySelector.setDefault(previousSelector)
            }
        }

    @Test
    fun pairingFailureIsActionableAndNeverDisplaysRawServerPayload() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().withWebSocketUpgrade(Gateway(reject = true)))
            server.start()
            val client =
                OpenClawClient(OpenClawEndpoint.parse(server.url("/").toString()), ClawIdentity(ByteArray(32)), "token")
            try {
                val error = runCatching { withTimeout(5000) { client.connect() } }.exceptionOrNull() as ClawFailure
                assertEquals("PAIRING_REQUIRED", error.code)
                assertTrue(error.userMessage.contains("承認"))
                assertFalse(error.userMessage.contains("secret"))
                assertEquals(ClawConnection.DISCONNECTED, client.connectionState.value)
                assertFalse(client.supports("chat.send"))
            } finally {
                client.close()
                server.shutdown()
            }
        }

    @Test
    fun closeSettlesInFlightRequestAndDisablesSends() =
        runBlocking {
            val server = MockWebServer()
            val gateway =
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send(
                            """{"type":"event","event":"connect.challenge","payload":{"nonce":"n","ts":1}}""",
                        )
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if ((Json.parseToJsonElement(text) as JsonObject).string("method") == "connect") {
                            webSocket.send(
                                """
                                {
                                  "type": "res",
                                  "id": "connect",
                                  "ok": true,
                                  "payload": {
                                    "type": "hello-ok",
                                    "features": {
                                      "methods": [
                                        "chat.send"
                                      ]
                                    },
                                    "auth": {
                                      "scopes": [
                                        "operator.write"
                                      ]
                                    }
                                  }
                                }
                                """.trimIndent(),
                            )
                        }
                    }
                }
            server.enqueue(MockResponse().withWebSocketUpgrade(gateway))
            server.start()
            val client =
                OpenClawClient(OpenClawEndpoint.parse(server.url("/").toString()), ClawIdentity(ByteArray(32)), "token")
            try {
                withTimeout(5000) { client.connect() }
                val request = async { runCatching { client.rpc("chat.send", obj("message" to value("hello"))) } }
                delay(50)
                client.close()
                val failure = withTimeout(1000) { request.await() }.exceptionOrNull()
                assertTrue(failure is ClawFailure)
                assertFalse(client.supports("chat.send"))
            } finally {
                client.close()
                server.shutdown()
            }
        }

    @Test
    fun remoteCloseReconnectsAndReadsHistoryWithoutReplayingMessages() =
        runBlocking {
            val server = MockWebServer()
            val gateways = listOf(Gateway(), Gateway())
            gateways.forEach { server.enqueue(MockResponse().withWebSocketUpgrade(it)) }
            server.start()
            var generation = 0
            try {
                maintainClawConnection(
                    connect = { ready ->
                        val index = generation++
                        val client =
                            OpenClawClient(
                                OpenClawEndpoint.parse(server.url("/").toString()),
                                ClawIdentity(ByteArray(32)),
                                "token",
                            )
                        try {
                            withTimeout(5000) { client.connect() }
                            client.rpc("chat.history", obj("sessionKey" to value("agent:main:a")))
                            ready()
                            if (index == 0) {
                                gateways[index].socket.close(1001, "restart")
                                withTimeout(5000) {
                                    client.connectionState.first { it == ClawConnection.DISCONNECTED }
                                }
                                throw ClawFailure("DISCONNECTED")
                            }
                        } finally {
                            client.close()
                        }
                    },
                    onFailure = { _, retry -> assertTrue(retry) },
                    wait = {},
                )
                assertEquals(2, generation)
                gateways.forEach { gateway ->
                    assertEquals(listOf("connect", "chat.history"), gateway.received.map { it.string("method") })
                }
            } finally {
                server.shutdown()
            }
        }
}
