package com.m57.hermescontrol.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Spacing tokens — Mono Expressive's 4 dp grid (`space-*` in tokens.json).
 *
 * | Token | Value  | DS token  | Use case                                  |
 * |-------|--------|-----------|-------------------------------------------|
 * | xs    | 4 dp   | space-1   | Hairline gaps, chip inner padding         |
 * | sm    | 8 dp   | space-2   | Default inner padding, icon-to-text gap   |
 * | md    | 12 dp  | space-3   | Row content, screen edge, section gaps    |
 * | lg    | 16 dp  | space-4   | Between groups, card content              |
 * | xl    | 24 dp  | space-6   | Between major sections                    |
 * | xxl   | 32 dp  | space-8   | Large section break, rare                 |
 *
 * Access in composables via `LocalSpacing.current` or the [Spacing] object.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Alias for backwards-compatibility — existing `Spacing.xs` calls still work. */
val SpacingDefaults = Spacing

val LocalSpacing = staticCompositionLocalOf { Spacing }
