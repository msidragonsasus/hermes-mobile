package com.m57.hermescontrol.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Mono Expressive type scale (design system `tokens.json`, "Baseline" + "Emphasized").
 *
 * Baseline is the Material 3 scale. Emphasized is the Expressive variant: the same
 * size and line height with more weight (500 for display through body, 700 for
 * title-medium/small and labels). Use it for selected labels, key numbers and a
 * screen's hero heading — at most once per block.
 *
 * The family is the platform sans (Roboto with Noto Sans CJK JP for Japanese),
 * which is the design system's own fallback stack; Roboto Flex itself is not bundled.
 */
private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    letterSpacing: TextUnit,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing,
)

val Typography =
    Typography(
        // Display — hero numbers, big stats
        displayLarge = style(57, 64, FontWeight.Normal, (-0.25).sp),
        displayMedium = style(45, 52, FontWeight.Normal, 0.sp),
        displaySmall = style(36, 44, FontWeight.Normal, 0.sp),
        // Headline — empty-state titles, screen intros
        headlineLarge = style(32, 40, FontWeight.Normal, 0.sp),
        headlineMedium = style(24, 32, FontWeight.Normal, 0.sp),
        headlineSmall = style(20, 28, FontWeight.Normal, 0.sp),
        // Title — TopAppBar, card headings
        titleLarge = style(20, 28, FontWeight.Normal, 0.sp),
        titleMedium = style(16, 22, FontWeight.Medium, 0.sp),
        titleSmall = style(14, 20, FontWeight.Medium, 0.sp),
        // Body — primary reading
        bodyLarge = style(16, 24, FontWeight.Normal, 0.sp),
        bodyMedium = style(14, 20, FontWeight.Normal, 0.sp),
        bodySmall = style(12, 16, FontWeight.Normal, 0.sp),
        // Label — buttons, chips, captions
        labelLarge = style(14, 20, FontWeight.Medium, 0.sp),
        labelMedium = style(12, 16, FontWeight.Medium, 0.sp),
        labelSmall = style(11, 16, FontWeight.Medium, 0.sp),
        // Emphasized — Expressive variants (same metrics, heavier weight)
        displayLargeEmphasized = style(57, 64, FontWeight.Medium, 0.sp),
        displayMediumEmphasized = style(45, 52, FontWeight.Medium, 0.sp),
        displaySmallEmphasized = style(36, 44, FontWeight.Medium, 0.sp),
        headlineLargeEmphasized = style(32, 40, FontWeight.Medium, 0.sp),
        headlineMediumEmphasized = style(24, 32, FontWeight.Medium, 0.sp),
        headlineSmallEmphasized = style(20, 28, FontWeight.Medium, 0.sp),
        titleLargeEmphasized = style(20, 28, FontWeight.Medium, 0.sp),
        titleMediumEmphasized = style(16, 22, FontWeight.Bold, 0.sp),
        titleSmallEmphasized = style(14, 20, FontWeight.Bold, 0.sp),
        bodyLargeEmphasized = style(16, 24, FontWeight.Medium, 0.sp),
        bodyMediumEmphasized = style(14, 20, FontWeight.Medium, 0.sp),
        bodySmallEmphasized = style(12, 16, FontWeight.Medium, 0.sp),
        labelLargeEmphasized = style(14, 20, FontWeight.Bold, 0.sp),
        labelMediumEmphasized = style(12, 16, FontWeight.Bold, 0.sp),
        labelSmallEmphasized = style(11, 16, FontWeight.Bold, 0.sp),
    )
