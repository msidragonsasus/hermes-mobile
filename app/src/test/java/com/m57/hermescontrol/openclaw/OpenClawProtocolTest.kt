package com.m57.hermescontrol.openclaw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.Base64

class OpenClawProtocolTest {
    private fun parse(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    @Test
    fun streamingRejectsOtherSessionsAndDuplicateSequencesButAcceptsANewRun() {
        val cursor = ClawStreamOrder()

        fun event(
            session: String,
            run: String,
            seq: Long,
        ) = obj(
            "sessionKey" to value(session),
            "runId" to value(run),
            "seq" to kotlinx.serialization.json.JsonPrimitive(seq),
        )
        assertFalse(cursor.accept("a", event("other", "run-1", 1)))
        assertTrue(cursor.accept("a", event("a", "run-1", 10)))
        assertFalse(cursor.accept("a", event("a", "run-1", 9)))
        assertFalse(cursor.accept("a", event("a", "run-1", 10)))
        assertTrue(cursor.accept("a", event("a", "run-2", 0)))
        assertTrue(cursor.accept("a", event("a", "run-2", 1)))
        assertFalse(cursor.accept("a", event("a", "run-1", 10)))
    }

    @Test
    fun endpointsKeepProxyPathAndRejectCredentialUrls() {
        assertEquals(
            "wss://claw.example/private/",
            OpenClawEndpoint.parse("https://claw.example/private/").webSocketUrl,
        )
        assertEquals("ws://100.103.190.68:18789/", OpenClawEndpoint.parse("ws://100.103.190.68:18789").webSocketUrl)
        listOf("ftp://host/", "wss://user:password@host", "ws://host/?token=secret", "https://host/#secret").forEach {
            assertThrows(IllegalArgumentException::class.java) { OpenClawEndpoint.parse(it) }
        }
    }

    @Test
    fun plaintextPolicyAcceptsLanAndTailnetButRejectsPublicAddresses() {
        listOf(
            "127.0.0.1",
            "10.2.3.4",
            "192.168.1.5",
            "172.16.0.1",
            "100.64.0.1",
            "100.127.255.254",
            "::1",
            "fd7a:115c:a1e0::1",
        ).forEach {
            assertTrue(it, privateAddress(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "100.63.255.255", "100.128.0.1", "172.32.0.1", "2001:4860:4860::8888").forEach {
            assertFalse(it, privateAddress(InetAddress.getByName(it)))
        }
    }

    @Test
    fun plaintextEndpointsRejectPublicLiteralRepresentations() {
        listOf(
            "ws://8.8.8.8/",
            "http://8.8.8.8/",
            "ws://134744072/",
            "ws://8.8.2056/",
            "ws://100.63.255.255/",
            "ws://100.128.0.1/",
            "ws://172.32.0.1/",
            "ws://0.0.0.0/",
            "ws://224.0.0.1/",
            "ws://[2001:4860:4860::8888]/",
            "ws://[2001:4860:4860:0:0:0:0:8888]/",
            "ws://[::ffff:8.8.8.8]/",
            "ws://[::ffff:808:808]/",
            "ws://[::]/",
        ).forEach { input ->
            assertThrows(input, IllegalArgumentException::class.java) { OpenClawEndpoint.parse(input) }
        }
    }

    @Test
    fun plaintextEndpointsKeepPrivateLiteralsAndDeferHostnamesToDns() {
        listOf(
            "ws://127.0.0.1/",
            "ws://10.2.3.4/",
            "ws://192.168.1.5/",
            "ws://172.16.0.1/",
            "ws://172.31.255.255/",
            "ws://169.254.1.1/",
            "ws://100.64.0.1/",
            "ws://100.127.255.255/",
            "ws://[::1]/",
            "ws://[fe80::1]/",
            "ws://[fd7a:115c:a1e0::1]/",
            "ws://[::ffff:192.168.1.5]/",
            "ws://[::ffff:c0a8:105]/",
            "ws://gateway.example.invalid:18789/private/",
        ).forEach { input ->
            assertTrue(input, OpenClawEndpoint.parse(input).webSocketUrl.startsWith("ws://"))
        }
    }

    @Test
    fun plaintextEndpointsRejectMalformedOrAmbiguousNumericHostsBeforeResolution() {
        listOf(
            "127.1",
            "2130706433",
            "127.0.0.1.",
            "127..1",
            "127.0.0.0.1",
            "256.1",
            "127.16777216",
            "4294967296",
            "1234567890123456",
            "010.0.0.1",
            "127.01",
            "0000002130706433",
        ).forEach { host ->
            assertThrows(host, IllegalArgumentException::class.java) {
                val url =
                    OpenClawEndpoint
                        .parse("wss://127.0.0.1/")
                        .url
                        .newBuilder()
                        .host(host)
                        .scheme("http")
                        .build()
                OpenClawEndpoint(url)
            }
        }
    }

    @Test
    fun endpointConstructionAndCopyCannotBypassPlaintextLiteralPolicy() {
        val secure = OpenClawEndpoint.parse("wss://8.8.8.8/")
        val publicCleartext =
            secure.url
                .newBuilder()
                .scheme("http")
                .build()
        assertThrows(IllegalArgumentException::class.java) { OpenClawEndpoint(publicCleartext) }
        assertThrows(IllegalArgumentException::class.java) { secure.copy(url = publicCleartext) }
        assertEquals("wss://8.8.8.8/", secure.copy().webSocketUrl)
        listOf("wss://[2001:4860:4860::8888]/", "https://[::ffff:8.8.8.8]/").forEach { input ->
            assertTrue(input, OpenClawEndpoint.parse(input).url.isHttps)
        }
    }

    @Test
    fun identitySignsServerNonceTimeTokenAndExactScopes() {
        val identity = ClawIdentity(ByteArray(32) { it.toByte() })
        val signature =
            identity.sign(
                "challenge",
                1792339200000,
                "shared-secret",
                listOf("operator.read", "operator.write"),
            )
        val expected =
            "v3|${identity.id}|openclaw-android|ui|operator|" +
                "operator.read,operator.write|1792339200000|shared-secret|challenge|android|"
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(Base64.getUrlDecoder().decode(identity.publicKey), 0))
        val bytes = expected.toByteArray(Charsets.UTF_8)
        verifier.update(bytes, 0, bytes.size)
        assertTrue(verifier.verifySignature(Base64.getUrlDecoder().decode(signature)))
        assertTrue(
            signature !=
                identity.sign(
                    "different-nonce",
                    1792339200000,
                    "shared-secret",
                    listOf("operator.read", "operator.write"),
                ),
        )
        assertTrue(
            signature !=
                identity.sign("challenge", 1792339200000, "other-secret", listOf("operator.read", "operator.write")),
        )
    }

    @Test
    fun olderAgentListPatchPreservesFallbacksAndOtherAgents() {
        val before =
            parse(
                """
                {
                  "agents": {
                    "list": [
                      {
                        "id": "main",
                        "model": {
                          "primary": "p/old",
                          "fallbacks": [
                            "p/fallback"
                          ]
                        },
                        "workspace": "/keep"
                      },
                      {
                        "id": "other",
                        "model": "p/other"
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )
        val patch = agentDefaultsPatch(before, "main", "p/new", null)
        val rows = patch.obj("agents")!!.array("list")
        val main = rows[0] as JsonObject
        assertEquals("p/new", main.obj("model")!!.string("primary"))
        assertEquals(listOf("p/fallback"), strings(main.obj("model")!!["fallbacks"]))
        assertEquals("/keep", main.string("workspace"))
        assertEquals(before.obj("agents")!!.array("list")[1], rows[1])
        assertFalse(main.containsKey("thinkingDefault"))
    }

    @Test
    fun entryMapPatchChangesOnlyRequestedAgentField() {
        val before = parse("""{"agents":{"entries":{"a":{"model":"p/a"},"b":{"model":"p/b"}}}}""")
        val patch = agentDefaultsPatch(before, "a", null, "high")
        assertEquals(parse("""{"agents":{"entries":{"a":{"thinkingDefault":"high"}}}}"""), patch)
        assertThrows(IllegalArgumentException::class.java) { agentDefaultsPatch(before, "missing", "p/new", null) }
    }

    @Test
    fun singleAgentUsesSharedDefaultsWithoutClobberingOtherFields() {
        val patch =
            agentDefaultsPatch(parse("""{"agents":{"defaults":{"workspace":"/keep"}}}"""), "main", "p/new", "high")
        assertEquals(parse("""{"agents":{"defaults":{"model":{"primary":"p/new"},"thinkingDefault":"high"}}}"""), patch)
    }

    @Test
    fun historyDisplaysOnlyTextContentAndKeepsHermesProtocolSeparate() {
        assertEquals(
            "hello\nworld",
            messageText(
                parse(
                    """
                    {
                      "content": [
                        {
                          "type": "thinking",
                          "thinking": "private reasoning"
                        },
                        {
                          "type": "text",
                          "text": "hello"
                        },
                        {
                          "type": "text",
                          "text": "world"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals("text", messageText(parse("""{"content":"text"}""")))
        assertEquals(
            "",
            messageText(parse("""{"content":[{"type":"toolCall","name":"tool","arguments":{"secret":"value"}}]}""")),
        )
    }
}
