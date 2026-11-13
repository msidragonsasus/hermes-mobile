package com.m57.hermescontrol.data.update

import com.m57.hermescontrol.data.local.AuthManager
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Production scope snapshot over a mocked AuthManager (no prefs/DataStore):
 * the scope changes on credential replacement, epoch movement (logout,
 * re-login, A→B→A) and active-profile change; a snapshot taken while a
 * connection change is in progress is refused; bindings never carry another
 * scope's credential.
 */
class AuthManagerUpdateScopesTest {
    private val epoch = MutableStateFlow(2L)
    private val active = MutableStateFlow<String?>(null)
    private var token: String? = "token-A"
    private var base = "http://100.64.0.2:9119/"
    private var gated = false
    private var cookie: String? = null

    @Before
    fun setUp() {
        mockkObject(AuthManager)
        every { AuthManager.connectionEpoch } returns epoch
        every { AuthManager.activeProfileId } returns active
        every { AuthManager.getBaseUrl() } answers { base }
        every { AuthManager.isGatedMode() } answers { gated }
        every { AuthManager.getToken() } answers { token }
        every { AuthManager.getSelectedProfileId() } returns "default"
        AuthManagerUpdateScopes.cookieHeaderFor = { cookie }
    }

    @After
    fun tearDown() {
        AuthManagerUpdateScopes.cookieHeaderFor = AuthManagerUpdateScopes.defaultCookieHeader
        unmockkAll()
    }

    private fun server() = AuthManagerUpdateScopes.current(UpdateSource.CONNECTED_SERVER)

    @Test
    fun scopeHoldsNoSecretButChangesWhenTheCredentialIsReplaced() {
        val first = server()!!
        assertFalse(first.toString().contains("token-A"))
        token = "token-A2"
        val replaced = server()!!
        assertNotEquals(first, replaced)
        assertNull("old scope must not bind to the new credential", AuthManagerUpdateScopes.bind(first))
        assertNotNull(AuthManagerUpdateScopes.bind(replaced))
    }

    @Test
    fun epochMovementInvalidatesEvenWhenEverythingElseReturnsToTheSameValues() {
        val a = server()!!
        epoch.value = 4L // switch to B ...
        epoch.value = 6L // ... and back to A with the same token
        val again = server()!!
        assertNotEquals(a, again)
        assertNull(AuthManagerUpdateScopes.bind(a))
    }

    @Test
    fun activeProfileChangeAndLogoutChangeTheScope() {
        val a = server()!!
        active.value = "work"
        assertNotEquals(a, server())
        token = null
        assertNull("signed out", server())
    }

    @Test
    fun snapshotDuringConnectionChangeIsRefused() {
        epoch.value = 3L // odd: a setter is mid-change
        assertNull(server())
        epoch.value = 4L
        every { AuthManager.getToken() } answers {
            epoch.value = 6L // change completes while the snapshot is being taken
            token
        }
        assertNull(server())
    }

    @Test
    fun gatedModeBindsOnlyTheCookieSnapshotForThatServer() {
        gated = true
        cookie = null
        assertNull("no session cookie for this server", server())
        cookie = "hermes_session_at=abc"
        val scope = server()!!
        assertTrue(scope.gatedAuth)
        val binding = AuthManagerUpdateScopes.bind(scope)!!
        assertEquals(scope, binding.scope)
        assertFalse(binding.toString().contains("abc"))
        cookie = "hermes_session_at=other"
        assertNull(AuthManagerUpdateScopes.bind(scope))
    }
}
