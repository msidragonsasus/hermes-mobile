package com.m57.hermescontrol.data.config

import androidx.datastore.core.DataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerStoreTest {
    @Test
    fun connectionChangesInvalidateSynchronouslyIncludingRapidReturnBeforePersistence() =
        runTest {
            val first = ConnectionProfile(id = "first", name = "First", baseUrl = "http://100.64.10.20:9119/")
            val second = ConnectionProfile(id = "second", name = "Second", baseUrl = first.baseUrl)
            val initial = ServerStoreState(connectionProfiles = listOf(first, second), selectedProfileId = first.id)
            val dataStore =
                object : DataStore<ServerStoreState> {
                    override val data: Flow<ServerStoreState> = flowOf(initial)

                    override suspend fun updateData(
                        transform: suspend (ServerStoreState) -> ServerStoreState,
                    ): ServerStoreState = transform(initial)
                }
            var changes = 0
            val store = ServerStore(dataStore, backgroundScope) { changes++ }
            store.update { it.copy(selectedProfileId = second.id) }
            store.update { it.copy(selectedProfileId = first.id) }
            assertEquals(2, changes)
            store.update { it.copy(autoReconnect = false) }
            assertEquals(2, changes)
            store.update {
                it.copy(connectionProfiles = listOf(first.copy(baseUrl = "http://100.64.10.21:9119/"), second))
            }
            assertEquals(3, changes)
        }

    @Test
    fun failedConsentRevocationPreventsPublishingAChangedConnection() =
        runTest {
            val first = ConnectionProfile(id = "first", name = "First", baseUrl = "http://100.64.10.20:9119/")
            val second = ConnectionProfile(id = "second", name = "Second", baseUrl = "http://100.64.10.21:9119/")
            val initial = ServerStoreState(connectionProfiles = listOf(first, second), selectedProfileId = first.id)
            var writes = 0
            val dataStore =
                object : DataStore<ServerStoreState> {
                    override val data: Flow<ServerStoreState> = flowOf(initial)

                    override suspend fun updateData(
                        transform: suspend (ServerStoreState) -> ServerStoreState,
                    ): ServerStoreState {
                        writes++
                        return transform(initial)
                    }
                }
            val store = ServerStore(dataStore, backgroundScope) { error("synthetic consent persistence failure") }
            assertTrue(runCatching { store.update { it.copy(selectedProfileId = second.id) } }.isFailure)
            assertEquals(first.id, store.getLatestState().selectedProfileId)
            assertEquals(0, writes)
        }

    @Test
    fun testDefaultValues() {
        val state = ServerStoreState()
        assertEquals("127.0.0.1", state.host)
        assertEquals(9119, state.port)
        assertTrue(state.autoReconnect)
        assertEquals("token", state.wsAuthParam)
        assertEquals(1.0f, state.chatFontScale)
        assertTrue(state.connectionProfiles.isEmpty())
        assertNull(state.selectedProfileId)
    }

    @Test
    fun testPureOps_addOrUpdate() {
        val state = ServerStoreState()
        val profile = ConnectionProfile(id = "1", name = "Test", host = "10.0.0.1", port = 8080)

        val newState = state.addOrUpdate(profile)
        assertEquals(1, newState.connectionProfiles.size)
        assertEquals(profile, newState.connectionProfiles[0])

        // Replace
        val updatedProfile = profile.copy(host = "10.0.0.2")
        val finalState = newState.addOrUpdate(updatedProfile)
        assertEquals(1, finalState.connectionProfiles.size)
        assertEquals("10.0.0.2", finalState.connectionProfiles[0].host)
    }

    @Test
    fun testPureOps_selfHealed() {
        // Test orphaned selected profile ID
        val state =
            ServerStoreState(
                connectionProfiles = emptyList(),
                selectedProfileId = "orphaned-id",
            )
        val healed = state.selfHealed()
        assertNull(healed.selectedProfileId)
    }

    @Test
    fun testResolvedHostAndPort() {
        val profile = ConnectionProfile(id = "prof-1", name = "Custom", host = "10.0.0.5", port = 8000)
        val state =
            ServerStoreState(
                host = "127.0.0.1",
                port = 9119,
                connectionProfiles = listOf(profile),
                selectedProfileId = "prof-1",
            )

        assertEquals("10.0.0.5", state.resolvedHost)
        assertEquals(8000, state.resolvedPort)

        // Unselected
        val unselectedState = state.copy(selectedProfileId = null)
        assertEquals("127.0.0.1", unselectedState.resolvedHost)
        assertEquals(9119, unselectedState.resolvedPort)
    }
}
