package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningLevelsTest {
    @Test
    fun sliderStopsKeepTheirGatewayValuesAndAccessibleLabels() {
        val all = availableReasoningLevels(true)
        assertEquals(listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"), all.map { it.first })
        assertEquals(all, availableReasoningLevels(null))
        assertEquals(all.drop(1), availableReasoningLevels(false))
        all.forEach { (level, label) -> assertEquals(label, reasoningLabelRes(level)) }
        assertEquals(R.string.chat_reasoning_medium, reasoningLabelRes(null))
    }
}
