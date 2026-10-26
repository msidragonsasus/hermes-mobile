package com.m57.hermescontrol.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Four chart series colors for native answers. Series 1 is the theme primary; the
 * rest come from a fixed pool per light/dark mode, each at least 3:1 against the
 * theme surfaces (see HermesChartColorsTest). The pool color closest to primary is
 * dropped so the four series stay distinguishable in every preset.
 */
@Immutable
data class HermesChartColors(
    val series: List<Color>,
) {
    operator fun get(index: Int): Color = series[index.mod(series.size)]
}

internal val ChartDarkPool =
    listOf(Color(0xFFF2B880), Color(0xFFB9A6F5), Color(0xFF8ECEFF), Color(0xFF8FD6B4))
internal val ChartLightPool =
    listOf(Color(0xFF874610), Color(0xFF5A3EA8), Color(0xFF175A92), Color(0xFF0B5E50))

internal fun chartColorsFor(
    primary: Color,
    dark: Boolean,
): HermesChartColors {
    val pool = if (dark) ChartDarkPool else ChartLightPool
    val nearest =
        pool.minBy {
            val r = it.red - primary.red
            val g = it.green - primary.green
            val b = it.blue - primary.blue
            r * r + g * g + b * b
        }
    return HermesChartColors(listOf(primary) + pool.filter { it != nearest })
}

val LocalHermesChartColors =
    staticCompositionLocalOf { chartColorsFor(Color(0xFFBBC3FF), dark = true) }
