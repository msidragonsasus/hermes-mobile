package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.domain.hermes.ConnectionScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveSessionHolderTest {
    @Test
    fun `background replies remember bounded scoped ids without replacing active chat`() {
        val scope = ConnectionScope("server", null, null, "process", 1)
        ActiveSessionHolder.setScoped("active-runtime", "active-saved", scope)
        for (index in 0..32) ActiveSessionHolder.rememberNotificationSession(scope, "runtime-$index", "saved-$index")
        assertEquals("active-runtime", ActiveSessionHolder.activeSessionId.value)
        assertEquals("active-saved", ActiveSessionHolder.resolveStoredSessionId(scope, "active-runtime"))
        assertNull(ActiveSessionHolder.resolveStoredSessionId(scope, "runtime-0"))
        assertEquals("saved-32", ActiveSessionHolder.resolveStoredSessionId(scope, "runtime-32"))
        assertNull(ActiveSessionHolder.resolveStoredSessionId(scope.copy(generation = 2), "runtime-32"))
        ActiveSessionHolder.clear()
        assertNull(ActiveSessionHolder.resolveStoredSessionId(scope, "runtime-32"))
    }

    @Test
    fun `notification lookup requires exact scope and a known saved id`() {
        val scope = ConnectionScope("server", "connection", "profile", "process", 1)
        ActiveSessionHolder.setScoped("runtime", "saved", scope)
        assertEquals("saved", ActiveSessionHolder.resolveStoredSessionId(scope, "runtime"))
        assertNull(ActiveSessionHolder.resolveStoredSessionId(scope.copy(generation = 2), "runtime"))
        assertNull(ActiveSessionHolder.resolveStoredSessionId(scope.copy(serverProfileId = "other"), "runtime"))
        assertNull(ActiveSessionHolder.resolveStoredSessionId(scope, "unknown-runtime"))
        ActiveSessionHolder.set("runtime", "saved")
        assertNull(ActiveSessionHolder.resolveStoredSessionId(scope, "runtime"))
        ActiveSessionHolder.setScoped("runtime", null, scope)
        assertNull(ActiveSessionHolder.resolveStoredSessionId(scope, "runtime"))
        ActiveSessionHolder.clear()
        assertNull(ActiveSessionHolder.resolveStoredSessionId(scope, "runtime"))
    }

    @After
    fun tearDown() {
        ActiveSessionHolder.clear()
    }

    @Test
    fun `runtime and stored ids resolve in both directions`() {
        ActiveSessionHolder.set("runtime-session", "stored-session")

        assertEquals("stored-session", ActiveSessionHolder.resolveStoredSessionId("runtime-session"))
        assertEquals("runtime-session", ActiveSessionHolder.resolveRuntimeSessionId("stored-session"))
    }

    @Test
    fun `session switch discards the previous mapping`() {
        ActiveSessionHolder.set("runtime-previous", "stored-previous")
        ActiveSessionHolder.clear()
        ActiveSessionHolder.set("runtime-current", "stored-current")

        assertEquals("runtime-previous", ActiveSessionHolder.resolveStoredSessionId("runtime-previous"))
        assertNull(ActiveSessionHolder.resolveRuntimeSessionId("stored-previous"))
    }

    @Test
    fun `mapping can be rebuilt after reconnect`() {
        ActiveSessionHolder.set("runtime-before", "stored-session")
        ActiveSessionHolder.clear()

        assertNull(ActiveSessionHolder.activeSessionId.value)
        assertNull(ActiveSessionHolder.resolveRuntimeSessionId("stored-session"))

        ActiveSessionHolder.set("runtime-after", "stored-session")
        assertEquals("runtime-after", ActiveSessionHolder.resolveRuntimeSessionId("stored-session"))
    }
}
