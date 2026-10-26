package com.m57.hermescontrol.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesChartColorsTest {
    @Test
    fun everySeriesColorIsDistinctAndReadableOnNativeAnswerSurfaces() {
        ThemePreset.entries.forEach { preset ->
            listOf(true, false).forEach { dark ->
                val scheme = resolveColorScheme(preset, dark)
                val colors = chartColorsFor(scheme.primary, dark).series
                assertEquals(4, colors.size)
                assertEquals("$preset dark=$dark", 4, colors.distinct().size)
                // Primary is the preset's own contract; the added pool must be readable everywhere.
                colors.drop(1).forEach { color ->
                    listOf(scheme.surface, scheme.surfaceContainerLow, scheme.surfaceContainerHigh).forEach { bg ->
                        assertTrue(
                            "$preset dark=$dark $color on $bg must be >= 3:1",
                            contrast(color, bg) >= 3f,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun poolColorNearestToPrimaryIsDropped() {
        val colors = chartColorsFor(ChartLightPool[2], dark = false).series
        assertEquals(ChartLightPool[2], colors.first())
        assertEquals(1, colors.count { it == ChartLightPool[2] })
    }

    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
