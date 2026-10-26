package com.m57.hermescontrol.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCatalogTest {
    private val scope = AgentScope(AgentId.BRO, "owner-one", "relay-one", "profile-one")

    @Test
    fun candidateHasOnlyBroAndHermesWithNoVerifiedConnectionOrCapability() {
        assertEquals(listOf("bro", "Hermes"), AgentCatalog.connections.map { it.displayName })
        assertEquals(listOf(AgentId.BRO, AgentId.HERMES), AgentCatalog.connections.map { it.id })
        assertEquals(
            listOf(AgentAdapterKind.BRO_PRIVATE_RELAY, AgentAdapterKind.HERMES_DASHBOARD),
            AgentCatalog.connections.map { it.adapterKind },
        )
        AgentCatalog.connections.forEach { agent ->
            assertEquals(AgentConnectionState.UNVERIFIED, agent.state)
            assertNull(agent.verifiedScope)
            AgentCapability.entries.forEach { capability ->
                assertEquals(AgentCapabilityState.UNKNOWN, agent.capabilityState(capability))
                assertFalse(agent.canUse(capability, null))
                assertFalse(agent.canUse(capability, scope.copy(agentId = agent.id)))
            }
        }
    }

    @Test
    fun advertisedSupportCannotBypassConnectionVerification() {
        val agent =
            AgentCatalog.find(AgentId.BRO)!!.copy(
                capabilities = AgentCapability.entries.associateWith { AgentCapabilityState.SUPPORTED },
            )
        AgentCapability.entries.forEach { assertFalse(agent.canUse(it, scope)) }
    }

    @Test
    fun capabilityRequiresVerifiedCurrentOwnerConnectionProfileAndAgent() {
        val agent =
            AgentCatalog.find(AgentId.BRO)!!.copy(
                state = AgentConnectionState.VERIFIED,
                verifiedScope = scope,
                capabilities = mapOf(AgentCapability.READ_REQUESTS to AgentCapabilityState.SUPPORTED),
            )
        assertTrue(agent.canUse(AgentCapability.READ_REQUESTS, scope))
        assertFalse(agent.canUse(AgentCapability.SUBMIT_REQUEST, scope))
        listOf(
            null,
            scope.copy(ownerId = "other-owner"),
            scope.copy(connectionId = "other-relay"),
            scope.copy(profileId = "other-profile"),
            scope.copy(agentId = AgentId.HERMES),
        ).forEach { assertFalse(agent.canUse(AgentCapability.READ_REQUESTS, it)) }
        listOf(AgentConnectionState.DISCONNECTED, AgentConnectionState.AUTH_REQUIRED).forEach {
            assertFalse(agent.copy(state = it).canUse(AgentCapability.READ_REQUESTS, scope))
        }
    }

    @Test
    fun unknownUnsupportedAndBlockedCapabilitiesAreUnavailableEvenWithVerifiedIdentity() {
        AgentCapabilityState.entries.filter { it != AgentCapabilityState.SUPPORTED }.forEach { capabilityState ->
            val agent =
                AgentCatalog.find(AgentId.BRO)!!.copy(
                    state = AgentConnectionState.VERIFIED,
                    verifiedScope = scope,
                    capabilities = AgentCapability.entries.associateWith { capabilityState },
                )
            AgentCapability.entries.forEach { assertFalse(agent.canUse(it, scope)) }
        }
    }

    @Test
    fun missingOrWrongAgentIdentityCannotBeMarkedVerified() {
        val bro = AgentCatalog.find(AgentId.BRO)!!
        assertThrows(IllegalArgumentException::class.java) { bro.copy(state = AgentConnectionState.VERIFIED) }
        assertThrows(IllegalArgumentException::class.java) {
            bro.copy(verifiedScope = scope.copy(agentId = AgentId.HERMES))
        }
        assertThrows(IllegalArgumentException::class.java) { scope.copy(ownerId = " ") }
        assertThrows(IllegalArgumentException::class.java) { scope.copy(connectionId = "") }
        assertThrows(IllegalArgumentException::class.java) { scope.copy(profileId = "") }
    }

    @Test
    fun unknownRouteNeverFallsBackToAnotherAgent() {
        assertEquals(AgentId.BRO, agentIdFromRoute("BRO"))
        assertEquals(AgentId.HERMES, agentIdFromRoute("HERMES"))
        listOf("", "OPENCLAW", "provider-api", "BRO/../../HERMES").forEach { assertNull(agentIdFromRoute(it)) }
    }
}
