package com.m57.hermescontrol.theme.presets

import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.PaletteColors
import com.m57.hermescontrol.theme.buildTheme

// ---------------------------------------------------------------------
// Mono Expressive — neutral roles come from material-color-utilities'
// SchemeMonochrome; the primary roles come from a single electric-blue
// tonal palette (#3D5AFE). Values mirror the "Mono Expressive" design
// system tokens 1:1 (tokens.json).
//
// Blue is the only accent: "press here" / "this is moving". Selection and
// secondary emphasis stay grey (secondaryContainer), and hierarchy comes
// from shape, size and weight rather than more hues.
// ---------------------------------------------------------------------

private val MxDarkPrimary = Color(0xFFBBC3FF)
private val MxDarkOnPrimary = Color(0xFF001D93)
private val MxDarkPrimaryContainer = Color(0xFF002CCD)
private val MxDarkOnPrimaryContainer = Color(0xFFDEE0FF)
private val MxDarkSecondary = Color(0xFFC6C6C6)
private val MxDarkOnSecondary = Color(0xFF1B1B1B)
private val MxDarkSecondaryContainer = Color(0xFF474747)
private val MxDarkOnSecondaryContainer = Color(0xFFE2E2E2)
private val MxDarkTertiary = Color(0xFFE2E2E2)
private val MxDarkOnTertiary = Color(0xFF1B1B1B)
private val MxDarkTertiaryContainer = Color(0xFF919191)
private val MxDarkOnTertiaryContainer = Color(0xFF000000)
private val MxDarkSurface = Color(0xFF131313)
private val MxDarkOnSurface = Color(0xFFE2E2E2)
private val MxDarkSurfaceVariant = Color(0xFF474747)
private val MxDarkOnSurfaceVariant = Color(0xFFC6C6C6)
private val MxDarkContainerLowest = Color(0xFF0E0E0E)
private val MxDarkContainerLow = Color(0xFF1B1B1B)
private val MxDarkContainer = Color(0xFF1F1F1F)
private val MxDarkContainerHigh = Color(0xFF2A2A2A)
private val MxDarkContainerHighest = Color(0xFF353535)
private val MxDarkSurfaceDim = Color(0xFF131313)
private val MxDarkSurfaceBright = Color(0xFF393939)
private val MxDarkInverseSurface = Color(0xFFE2E2E2)
private val MxDarkInverseOnSurface = Color(0xFF303030)
private val MxDarkOutline = Color(0xFF919191)
private val MxDarkOutlineVariant = Color(0xFF474747)
private val MxDarkError = Color(0xFFFFB4AB)
private val MxDarkOnError = Color(0xFF690005)
private val MxDarkErrorContainer = Color(0xFF93000A)
private val MxDarkOnErrorContainer = Color(0xFFFFDAD6)

private val MxLightPrimary = Color(0xFF2848EE)
private val MxLightOnPrimary = Color(0xFFFFFFFF)
private val MxLightPrimaryContainer = Color(0xFFDEE0FF)
private val MxLightOnPrimaryContainer = Color(0xFF000F5D)
private val MxLightSecondary = Color(0xFF5E5E5E)
private val MxLightOnSecondary = Color(0xFFFFFFFF)
private val MxLightSecondaryContainer = Color(0xFFD4D4D4)
private val MxLightOnSecondaryContainer = Color(0xFF1B1B1B)
private val MxLightTertiary = Color(0xFF3B3B3B)
private val MxLightOnTertiary = Color(0xFFE2E2E2)
private val MxLightTertiaryContainer = Color(0xFF747474)
private val MxLightOnTertiaryContainer = Color(0xFFFFFFFF)
private val MxLightSurface = Color(0xFFF9F9F9)
private val MxLightOnSurface = Color(0xFF1B1B1B)
private val MxLightSurfaceVariant = Color(0xFFE2E2E2)
private val MxLightOnSurfaceVariant = Color(0xFF474747)
private val MxLightContainerLowest = Color(0xFFFFFFFF)
private val MxLightContainerLow = Color(0xFFF3F3F3)
private val MxLightContainer = Color(0xFFEEEEEE)
private val MxLightContainerHigh = Color(0xFFE8E8E8)
private val MxLightContainerHighest = Color(0xFFE2E2E2)
private val MxLightSurfaceDim = Color(0xFFDADADA)
private val MxLightSurfaceBright = Color(0xFFF9F9F9)
private val MxLightInverseSurface = Color(0xFF303030)
private val MxLightInverseOnSurface = Color(0xFFF1F1F1)
private val MxLightOutline = Color(0xFF777777)
private val MxLightOutlineVariant = Color(0xFFC6C6C6)
private val MxLightError = Color(0xFFBA1A1A)
private val MxLightOnError = Color(0xFFFFFFFF)
private val MxLightErrorContainer = Color(0xFFFFDAD6)
private val MxLightOnErrorContainer = Color(0xFF410002)

// Semantic status hues are not part of the design system (red is reserved for
// errors, blue for the accent). Success/warning keep the hued foregrounds the
// other presets use so status never depends on lightness alone; info reuses the
// accent blue. Containers stay neutral, like the surfaces they sit on.
private val MxDarkSuccess = Color(0xFF81D692)
private val MxDarkWarning = Color(0xFFECC248)
private val MxLightSuccess = Color(0xFF126D2B)
private val MxLightWarning = Color(0xFF725C00)

/** Default theme (Mono Expressive) — monochrome base with one electric-blue accent. */
val DefaultTheme =
    buildTheme(
        dark =
            PaletteColors(
                primary = MxDarkPrimary,
                onPrimary = MxDarkOnPrimary,
                primaryContainer = MxDarkPrimaryContainer,
                onPrimaryContainer = MxDarkOnPrimaryContainer,
                secondary = MxDarkSecondary,
                onSecondary = MxDarkOnSecondary,
                secondaryContainer = MxDarkSecondaryContainer,
                onSecondaryContainer = MxDarkOnSecondaryContainer,
                tertiary = MxDarkTertiary,
                onTertiary = MxDarkOnTertiary,
                tertiaryContainer = MxDarkTertiaryContainer,
                onTertiaryContainer = MxDarkOnTertiaryContainer,
                background = MxDarkSurface,
                onBackground = MxDarkOnSurface,
                surface = MxDarkSurface,
                onSurface = MxDarkOnSurface,
                surfaceVariant = MxDarkSurfaceVariant,
                onSurfaceVariant = MxDarkOnSurfaceVariant,
                surfaceContainerLowest = MxDarkContainerLowest,
                surfaceContainerLow = MxDarkContainerLow,
                surfaceContainer = MxDarkContainer,
                surfaceContainerHigh = MxDarkContainerHigh,
                surfaceContainerHighest = MxDarkContainerHighest,
                inverseSurface = MxDarkInverseSurface,
                inverseOnSurface = MxDarkInverseOnSurface,
                inversePrimary = MxLightPrimary,
                outline = MxDarkOutline,
                outlineVariant = MxDarkOutlineVariant,
                scrim = Color.Black,
                status =
                    HermesStatusColors(
                        success = MxDarkSuccess,
                        successContainer = MxDarkContainerHigh,
                        onSuccess = MxDarkSurface,
                        warning = MxDarkWarning,
                        warningContainer = MxDarkContainerHigh,
                        onWarning = MxDarkSurface,
                        error = MxDarkError,
                        errorContainer = MxDarkErrorContainer,
                        onError = MxDarkOnError,
                        onErrorContainer = MxDarkOnErrorContainer,
                        info = MxDarkPrimary,
                        infoContainer = MxDarkContainerHigh,
                        onInfo = MxDarkSurface,
                    ),
                surfaceDim = MxDarkSurfaceDim,
                surfaceBright = MxDarkSurfaceBright,
            ),
        light =
            PaletteColors(
                primary = MxLightPrimary,
                onPrimary = MxLightOnPrimary,
                primaryContainer = MxLightPrimaryContainer,
                onPrimaryContainer = MxLightOnPrimaryContainer,
                secondary = MxLightSecondary,
                onSecondary = MxLightOnSecondary,
                secondaryContainer = MxLightSecondaryContainer,
                onSecondaryContainer = MxLightOnSecondaryContainer,
                tertiary = MxLightTertiary,
                onTertiary = MxLightOnTertiary,
                tertiaryContainer = MxLightTertiaryContainer,
                onTertiaryContainer = MxLightOnTertiaryContainer,
                background = MxLightSurface,
                onBackground = MxLightOnSurface,
                surface = MxLightSurface,
                onSurface = MxLightOnSurface,
                surfaceVariant = MxLightSurfaceVariant,
                onSurfaceVariant = MxLightOnSurfaceVariant,
                surfaceContainerLowest = MxLightContainerLowest,
                surfaceContainerLow = MxLightContainerLow,
                surfaceContainer = MxLightContainer,
                surfaceContainerHigh = MxLightContainerHigh,
                surfaceContainerHighest = MxLightContainerHighest,
                inverseSurface = MxLightInverseSurface,
                inverseOnSurface = MxLightInverseOnSurface,
                inversePrimary = MxDarkPrimary,
                outline = MxLightOutline,
                outlineVariant = MxLightOutlineVariant,
                scrim = Color.Black,
                status =
                    HermesStatusColors(
                        success = MxLightSuccess,
                        successContainer = MxLightContainerHigh,
                        onSuccess = MxLightOnPrimary,
                        warning = MxLightWarning,
                        warningContainer = MxLightContainerHigh,
                        onWarning = MxLightOnPrimary,
                        error = MxLightError,
                        errorContainer = MxLightErrorContainer,
                        onError = MxLightOnError,
                        onErrorContainer = MxLightOnErrorContainer,
                        info = MxLightPrimary,
                        infoContainer = MxLightContainerHigh,
                        onInfo = MxLightOnPrimary,
                    ),
                surfaceDim = MxLightSurfaceDim,
                surfaceBright = MxLightSurfaceBright,
            ),
    )
