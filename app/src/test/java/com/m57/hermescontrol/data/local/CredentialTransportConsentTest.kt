package com.m57.hermescontrol.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialTransportConsentTest {
    private val endpoint = "http://100.64.10.20:9119/"
    private val scope = "$endpoint\nconnection-a\nprofile-a"
    private var saved: String? = null
    private var failWrite = false

    private fun store() =
        CredentialTransportConsent(
            read = { saved },
            write = {
                check(!failWrite) { "synthetic persistence failure" }
                saved = it
            },
        )

    @Test
    fun defaultOffAndCommittedGrantRestoresOnlyForSameContext() {
        val store = store()
        val initial = store.state(scope, endpoint)
        assertNull(initial.allowedEndpoint)
        val granted = store.enable(initial, scope, endpoint)
        assertEquals(endpoint, granted.allowedEndpoint)
        assertNotNull(saved)
        assertEquals(endpoint, store().state(scope, endpoint).allowedEndpoint)
        assertNull(store().state("$scope-other", endpoint).allowedEndpoint)
    }

    @Test
    fun switchingAwayAndBackSynchronouslyRevokesGrantAndStaleConfirmation() {
        val store = store()
        val initial = store.state(scope, endpoint)
        val granted = store.enable(initial, scope, endpoint)
        store.invalidateContext()
        store.invalidateContext()
        val returned = store.state(scope, endpoint)
        assertNull(saved)
        assertNull(returned.allowedEndpoint)
        assertTrue(returned.generation > granted.generation)
        assertTrue(runCatching { store.enable(initial, scope, endpoint) }.isFailure)
        assertNull(store().state(scope, endpoint).allowedEndpoint)
    }

    @Test
    fun revokeAndRegrantCannotReviveAnOldSourceSnapshot() {
        val store = store()
        val old = store.enable(store.state(scope, endpoint), scope, endpoint)
        store.invalidateContext()
        val renewed = store.enable(store.state(scope, endpoint), scope, endpoint)
        assertFalse(old == renewed)
        assertFalse(old.grantId == renewed.grantId)
        assertEquals(endpoint, renewed.allowedEndpoint)
    }

    @Test
    fun contextChangesNeverTransferPermissionOrAcceptStalePrompt() {
        for (other in listOf(
            "${endpoint}other\nconnection-a\nprofile-a",
            "$endpoint\nconnection-b\nprofile-a",
            "$endpoint\nconnection-a\nprofile-b",
        )) {
            saved = null
            val store = store()
            val initial = store.state(scope, endpoint)
            store.enable(initial, scope, endpoint)
            assertNull(store.state(other, endpoint).allowedEndpoint)
            assertNull(saved)
            assertTrue(runCatching { store.enable(initial, other, endpoint) }.isFailure)
        }
    }

    @Test
    fun failedGrantNeverEnablesAndFailedRevocationStaysDeniedUntilCommitted() {
        val store = store()
        failWrite = true
        assertTrue(runCatching { store.enable(store.state(scope, endpoint), scope, endpoint) }.isFailure)
        assertNull(store.state(scope, endpoint).allowedEndpoint)
        assertNull(saved)
        failWrite = false
        store.enable(store.state(scope, endpoint), scope, endpoint)
        failWrite = true
        assertTrue(runCatching { store.invalidateContext() }.isFailure)
        assertNull(store.state(scope, endpoint).allowedEndpoint)
        assertTrue(runCatching { store.invalidateContext() }.isFailure)
        failWrite = false
        store.invalidateContext()
        assertNull(saved)
    }

    @Test
    fun strictLiteralTailnetOriginEligibilityRejectsParserAliasesAndOtherNetworks() {
        for (value in listOf(endpoint, endpoint.removeSuffix("/"), "http://100.127.255.254:9120/")) {
            assertNotNull(value, credentialTransportEndpoint(value))
        }
        for (value in listOf(
            "",
            " $endpoint",
            "$endpoint\n",
            endpoint.replace("http:", "HTTP:"),
            endpoint.replace("http:", "https:"),
            "http://100.63.255.255:9119/",
            "http://100.128.0.1:9119/",
            "http://192.168.1.2:9119/",
            "http://gateway.example.test:9119/",
            "http://[::1]:9119/",
            "http://100.064.10.20:9119/",
            "http://100.64.10.256:9119/",
            "http://100.64.10.20:0/",
            "http://100.64.10.20:65536/",
            "http://100.64.10.20:09119/",
            "http://@100.64.10.20:9119/",
            "http://user@100.64.10.20:9119/",
            "${endpoint}prefix/",
            "$endpoint./",
            "${endpoint}a/../",
            "$endpoint?",
            "$endpoint#",
            "$endpoint?x=1",
            "$endpoint#fragment",
        )) {
            assertNull(value, credentialTransportEndpoint(value))
        }
    }
}
