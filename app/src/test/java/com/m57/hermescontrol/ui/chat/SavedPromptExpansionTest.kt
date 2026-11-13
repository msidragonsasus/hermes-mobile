package com.m57.hermescontrol.ui.chat

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPromptExpansionTest {
    private val scope =
        object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }

    private fun restored(state: SavedPromptExpansion): SavedPromptExpansion {
        val saved = with(SavedPromptExpansion.Saver) { scope.save(state) }
        return requireNotNull(SavedPromptExpansion.Saver.restore(requireNotNull(saved)))
    }

    @Test
    fun sameIdentityAndContentRestoresExpansionAfterDisposal() {
        val fingerprint = promptContentFingerprint("original")
        val state = restored(SavedPromptExpansion("message", fingerprint, true))
        assertTrue(state.expandedFor("message", fingerprint))
    }

    @Test
    fun editedOffscreenContentCannotRestoreAnOldExpandedFlag() {
        val old = promptContentFingerprint("old")
        val state = restored(SavedPromptExpansion("message", old, true))
        assertFalse(state.expandedFor("message", promptContentFingerprint("changed")))
    }

    @Test
    fun differentMessageCannotReuseRestoredExpansion() {
        val fingerprint = promptContentFingerprint("same text")
        val state = restored(SavedPromptExpansion("old-id", fingerprint, true))
        assertFalse(state.expandedFor("new-id", fingerprint))
    }

    @Test
    fun hugePromptUsesFixedSizeFingerprintWithoutChangingText() {
        val input = "日本語😀".repeat(100000)
        val fingerprint = promptContentFingerprint(input)
        assertEquals(64, fingerprint.length)
        assertEquals(fingerprint, promptContentFingerprint(input))
        assertFalse(fingerprint == promptContentFingerprint(input + "x"))
        val state = restored(SavedPromptExpansion("id", fingerprint, false))
        assertFalse(state.expandedFor("id", fingerprint))
    }
}
