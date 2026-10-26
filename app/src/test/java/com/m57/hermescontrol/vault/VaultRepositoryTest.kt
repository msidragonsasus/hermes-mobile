package com.m57.hermescontrol.vault

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VaultRepositoryTest {
    @Test
    fun originBindingDropsPathAndSecretsInUrlAreRejected() {
        assertEquals("https://example.test:8443", vaultOrigin("https://EXAMPLE.test:8443/login?token=private#anchor"))
        assertNull(vaultOrigin("https://user:pass@example.test"))
        assertNull(vaultOrigin("javascript:alert(1)"))
        assertNull(vaultOrigin("not a website"))
    }

    @Test
    fun loginUsesVaultContractAndRetainsPasswordWhitespace() =
        runBlocking {
            var sent: Map<String, Any> = emptyMap()
            val repo =
                VaultRepository({ method, params ->
                    assertEquals("vault.add", method)
                    sent = params
                    mapOf("id" to "login-id")
                })
            assertEquals(
                "login-id",
                repo.addLogin(" Example ", "https://example.test/login?q=private", " user ", " password ", "OTP"),
            )
            assertEquals("login", sent["kind"])
            assertEquals("https://example.test", sent["origin"])
            assertEquals("Example", sent["label"])
            val secret = sent["secret"] as Map<*, *>
            assertEquals("user", secret["identifier"])
            assertEquals(" password ", secret["password"])
            assertEquals("OTP", secret["otp_secret"])
            assertFalse(sent.containsKey("password"))
        }

    @Test
    fun listDecodesOnlyMetadataAndHandlesWrappedRpcResults() =
        runBlocking {
            val repo =
                VaultRepository({ _, _ ->
                    mapOf(
                        "result" to
                            mapOf(
                                "items" to
                                    listOf(
                                        mapOf(
                                            "id" to "one",
                                            "kind" to "login",
                                            "label" to "Example",
                                            "identifier" to "user",
                                            "has_otp" to true,
                                            "backend" to "local",
                                            "password" to "NEVER_PERSIST_THIS",
                                        ),
                                    ),
                            ),
                    )
                })
            val item = repo.list().single()
            assertEquals("user", item.identifier)
            assertTrue(item.has_otp == true)
            assertFalse(item.toString().contains("NEVER_PERSIST_THIS"))
        }

    @Test
    fun ownerChangeRejectsLateResponseAndFurtherWrites() =
        runBlocking {
            var current = true
            var calls = 0
            val repo =
                VaultRepository({ _, _ ->
                    calls++
                    current = false
                    mapOf("items" to emptyList<Any>())
                }, { current })
            assertTrue(runCatching { repo.list() }.isFailure)
            assertTrue(runCatching { repo.addLogin("Example", "https://example.test", "user", "pass", "") }.isFailure)
            assertEquals(1, calls)
        }

    @Test
    fun invalidFormsAndExternalDeletionNeverReachRpc() =
        runBlocking {
            var calls = 0
            val repo =
                VaultRepository({ _, _ ->
                    calls++
                    emptyMap<String, Any>()
                })
            assertTrue(runCatching { repo.addLogin("Example", "https://example.test", "user", "", "") }.isFailure)
            assertTrue(runCatching { repo.addLogin("Example", "file:///private", "user", "pass", "") }.isFailure)
            assertTrue(
                runCatching { repo.remove(VaultItem("id", "login", "external", backend = "bitwarden")) }.isFailure,
            )
            assertEquals(0, calls)
        }

    @Test
    fun missingAcknowledgementsAreNotReportedAsSuccessOrRetried() =
        runBlocking {
            var calls = 0
            val repo =
                VaultRepository({ _, _ ->
                    calls++
                    mapOf("removed" to false)
                })
            assertTrue(runCatching { repo.addLogin("Example", "https://example.test", "user", "pass", "") }.isFailure)
            assertTrue(runCatching { repo.remove(VaultItem("id", "login", "Example")) }.isFailure)
            assertEquals(2, calls)
        }
}
