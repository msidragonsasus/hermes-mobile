package com.m57.hermescontrol.ui.chat.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingIndicatorShimmerTest {
    // Grey Mono Expressive tokens and a tinted (Gruvbox-like) pair.
    private val greyBase = Color(0xFF5F5F5F)
    private val greyStrong = Color(0xFF1B1B1B)
    private val tintedBase = Color(0xFFA89984)
    private val tintedStrong = Color(0xFFEBDBB2)

    @Test
    fun bandMovesLeftToRightAndLeavesTheLabelPlainAtBothEnds() {
        val fractions = (0..10).map { it / 10f }
        assertTrue(fractions.all { thinkingShimmerWeight(0f, it) == 0f })
        assertTrue(fractions.all { thinkingShimmerWeight(1f, it) == 0f })
        val peaks =
            listOf(0.3f, 0.5f, 0.7f).map { phase -> fractions.maxBy { thinkingShimmerWeight(phase, it) } }
        assertTrue("The bright band travels in reading order: $peaks", peaks[0] < peaks[1] && peaks[1] < peaks[2])
        assertEquals(1f, thinkingShimmerWeight(0.5f, 0.5f), 0.0001f)
    }

    @Test
    fun onlyLightnessChangesAcrossTheGradient() {
        listOf(greyBase to greyStrong, tintedBase to tintedStrong).forEach { (base, strong) ->
            val peak = base.withLightnessOf(strong)
            val from = base.toHsl()
            val stops = (0..20).flatMap { thinkingShimmerStops(it / 20f, base, peak).toList() }
            stops.forEach { (_, color) ->
                val hsl = color.toHsl()
                if (from.saturation > 0f) assertEquals(from.hue, hsl.hue, 0.5f)
                assertEquals(from.saturation, hsl.saturation, 0.01f)
                assertEquals(base.alpha, color.alpha, 0.0001f)
            }
            val lightness = stops.map { it.second.toHsl().lightness }
            val peakLightness = strong.toHsl().lightness
            assertEquals(peakLightness, peak.toHsl().lightness, 0.01f)
            assertTrue(lightness.any { kotlin.math.abs(it - from.lightness) < 0.01f })
            assertTrue(lightness.any { kotlin.math.abs(it - peakLightness) < 0.01f })
            val low = minOf(from.lightness, peakLightness) - 0.01f
            val high = maxOf(from.lightness, peakLightness) + 0.01f
            assertTrue(lightness.all { it in low..high })
        }
    }

    @Test
    fun greyStaysGrey() {
        val peak = greyBase.withLightnessOf(greyStrong)
        assertEquals(0f, peak.toHsl().saturation, 0.01f)
        thinkingShimmerStops(0.5f, greyBase, peak).forEach { (_, color) ->
            assertEquals(color.red, color.green, 0.01f)
            assertEquals(color.green, color.blue, 0.01f)
        }
    }

    @Test
    fun sweepIsSlow() {
        assertTrue(THINKING_SHIMMER_PERIOD_MS >= 2000)
    }
}
