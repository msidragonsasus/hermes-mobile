package com.m57.hermescontrol.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Mono Expressive shape scale — the full Material 3 Expressive ladder
 * (`corner-*` tokens). Hierarchy comes from contrasting these steps, so do not
 * round everything the same amount.
 *
 * | Token               | Radius | Use case                                  |
 * |---------------------|--------|-------------------------------------------|
 * | extraSmall          | 4 dp   | Badges, inline toggles                    |
 * | small               | 8 dp   | Chips, text fields, pressed small buttons |
 * | medium              | 12 dp  | Standard cards, list rows                 |
 * | large               | 16 dp  | Large cards, FAB                          |
 * | largeIncreased      | 20 dp  | Medium FAB                                |
 * | extraLarge          | 28 dp  | Dialogs, sheets, Large FAB                |
 * | extraLargeIncreased | 32 dp  | Hero cards                                |
 * | extraExtraLarge     | 48 dp  | Oversized feature surfaces                |
 *
 * `corner-full` is [androidx.compose.foundation.shape.CircleShape] (pills, round buttons).
 */
val HermesShapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        largeIncreased = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
        extraLargeIncreased = RoundedCornerShape(32.dp),
        extraExtraLarge = RoundedCornerShape(48.dp),
    )
