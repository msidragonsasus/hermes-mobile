package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptDisclosurePolicyTest {
    @Test
    fun longUserInputStartsCollapsedWithAControl() {
        assertTrue(PromptDisclosurePolicy.shouldCollapse(MessageRole.USER, true, false, false))
        assertTrue(PromptDisclosurePolicy.showControl(MessageRole.USER, true, false))
        assertEquals(12, PromptDisclosurePolicy.COLLAPSED_LINE_LIMIT)
    }

    @Test
    fun shortUserInputIsNeverClippedAndHasNoControl() {
        for (expanded in listOf(false, true)) {
            assertFalse(PromptDisclosurePolicy.shouldCollapse(MessageRole.USER, false, expanded, false))
        }
        assertFalse(PromptDisclosurePolicy.showControl(MessageRole.USER, false, false))
    }

    @Test
    fun expandAndCollapseKeepTheControlAvailable() {
        assertFalse(PromptDisclosurePolicy.shouldCollapse(MessageRole.USER, true, true, false))
        assertTrue(PromptDisclosurePolicy.showControl(MessageRole.USER, true, false))
        assertTrue(PromptDisclosurePolicy.shouldCollapse(MessageRole.USER, true, false, false))
    }

    @Test
    fun searchRevealsTailAndClearingSearchRestoresManualState() {
        assertFalse(PromptDisclosurePolicy.shouldCollapse(MessageRole.USER, true, false, true))
        assertFalse(PromptDisclosurePolicy.showControl(MessageRole.USER, true, true))
        assertTrue(PromptDisclosurePolicy.shouldCollapse(MessageRole.USER, true, false, false))
        assertFalse(PromptDisclosurePolicy.shouldCollapse(MessageRole.USER, true, true, false))
    }

    @Test
    fun outputAndSystemRolesNeverCollapseEvenIfOverflowing() {
        MessageRole.entries.filter { it != MessageRole.USER }.forEach { role ->
            for (expanded in listOf(false, true)) {
                for (search in listOf(false, true)) {
                    assertFalse(PromptDisclosurePolicy.shouldCollapse(role, true, expanded, search))
                    assertFalse(PromptDisclosurePolicy.showControl(role, true, search))
                }
            }
        }
    }
}
