package com.m57.hermescontrol.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelVisibilityStoreTest {
    @Test
    fun `preferences survive recreation and stay isolated by connection and profile`() {
        val saved = mutableMapOf<String, String>()
        val store = ModelVisibilityStore({ saved[it] }, { scope, value -> saved[scope] = value })
        val first = store.observe("server-a/profile-1")
        store.update("server-a/profile-1") { it.setProviderVisible("openai", false) }
        assertFalse(first.value.isVisible("openai", "one"))
        assertTrue(store.observe("server-b/profile-1").value.isVisible("openai", "one"))
        assertTrue(store.observe("server-a/profile-2").value.isVisible("openai", "one"))
        val recreated = ModelVisibilityStore({ saved[it] }, { scope, value -> saved[scope] = value })
        assertEquals(first.value, recreated.observe("server-a/profile-1").value)
    }

    @Test
    fun `a failed save keeps the observed previous state and can be retried`() {
        var fail = true
        val store = ModelVisibilityStore({ null }, { _, _ -> check(!fail) })
        val state = store.observe("a")
        assertTrue(runCatching { store.update("a") { it.setProviderVisible("openai", false) } }.isFailure)
        assertTrue(state.value.isVisible("openai", "one"))
        fail = false
        store.update("a") { it.setProviderVisible("openai", false) }
        assertFalse(state.value.isVisible("openai", "one"))
    }

    @Test
    fun `unreadable preferences are reported without overwriting saved choices`() {
        var writes = 0
        val store = ModelVisibilityStore({ "unreadable" }, { _, _ -> writes++ })
        assertTrue(runCatching { store.observe("a") }.isFailure)
        assertTrue(runCatching { store.update("a") { ModelVisibility() } }.isFailure)
        assertEquals(0, writes)
    }

    @Test
    fun `updates from different controls apply to the latest persisted state`() {
        val store = ModelVisibilityStore({ null }, { _, _ -> })
        store.update("a") { it.setProviderVisible("openai", false) }
        store.update("a") { it.setProviderVisible("other", false) }
        assertFalse(store.observe("a").value.isVisible("openai", "one"))
        assertFalse(store.observe("a").value.isVisible("other", "one"))
        store.update("a") { ModelVisibility() }
        assertTrue(store.observe("a").value.isVisible("openai", "one"))
        assertTrue(store.observe("a").value.isVisible("other", "one"))
    }
}
