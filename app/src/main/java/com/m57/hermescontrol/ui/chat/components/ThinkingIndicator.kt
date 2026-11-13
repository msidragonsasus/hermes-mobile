package com.m57.hermescontrol.ui.chat.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import kotlin.math.abs

/** One slow left-to-right sweep of the lighter band across the label. */
internal const val THINKING_SHIMMER_PERIOD_MS = 2400

/** Half-width of the band as a fraction of the label width (a broad, quiet band). */
private const val BAND_HALF_WIDTH = 0.6f

private const val SHIMMER_STOPS = 16

/**
 * sRGB transfer and primaries, but stored as half floats rather than 8-bit
 * channels, so derived colors keep the source hue and saturation instead of
 * drifting when rounded to whole sRGB steps.
 */
private val SHIMMER_COLOR_SPACE = ColorSpaces.ExtendedSrgb

/**
 * 「思考中」: the fixed label at the conversation tail while a turn works without
 * visible reply text. Only the letters move: a broad band of the same hue at a
 * higher-contrast lightness sweeps slowly across them. No dots, spinner,
 * blinking, color or surface. When system animations are off (duration scale 0)
 * the label is static in the base color. Never shows reasoning text.
 */
@Composable
fun ThinkingIndicator(
    modifier: Modifier = Modifier,
    animate: Boolean = rememberAnimationsEnabled(),
) {
    if (animate) {
        val transition = rememberInfiniteTransition(label = "thinking_shimmer")
        val phase =
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(THINKING_SHIMMER_PERIOD_MS, easing = LinearEasing)),
                label = "thinking_shimmer_phase",
            )
        ThinkingLabel(phase = { phase.value }, modifier = modifier)
    } else {
        ThinkingLabel(phase = null, modifier = modifier)
    }
}

/**
 * The label at a fixed shimmer [phase] (0..1, read only while drawing), or
 * static when null. Separate so render checks can pin the phase.
 */
@Composable
internal fun ThinkingLabel(
    phase: (() -> Float)?,
    modifier: Modifier = Modifier,
) {
    // Theme values are read here, before entering the draw lambda.
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val peak = base.withLightnessOf(MaterialTheme.colorScheme.onSurface)
    val shimmer =
        if (phase == null) {
            Modifier
        } else {
            Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush =
                            Brush.horizontalGradient(
                                *thinkingShimmerStops(phase(), base, peak),
                                startX = 0f,
                                endX = size.width,
                            ),
                        blendMode = BlendMode.SrcIn,
                    )
                }
        }
    Text(
        text = stringResource(R.string.chat_thinking_indicator),
        style = MaterialTheme.typography.bodyMedium,
        color = base,
        modifier =
            modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("thinking_indicator")
                .then(shimmer),
    )
}

@Composable
private fun rememberAnimationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}

/**
 * Weight (0..1) of the lighter band at horizontal [fraction] of the label for
 * [phase] 0..1. The band enters from the left and leaves on the right, so both
 * ends of the cycle leave the whole label in the readable base color.
 */
internal fun thinkingShimmerWeight(
    phase: Float,
    fraction: Float,
): Float {
    val center = -BAND_HALF_WIDTH + (1f + 2f * BAND_HALF_WIDTH) * phase.coerceIn(0f, 1f)
    val distance = abs(fraction - center) / BAND_HALF_WIDTH
    return (1f - distance).coerceIn(0f, 1f)
}

/** Gradient stops across the label: same hue and saturation, only lightness moves. */
internal fun thinkingShimmerStops(
    phase: Float,
    base: Color,
    peak: Color,
): Array<Pair<Float, Color>> {
    val from = base.toHsl()
    val to = peak.toHsl()
    return Array(SHIMMER_STOPS + 1) { index ->
        val fraction = index.toFloat() / SHIMMER_STOPS
        val weight = thinkingShimmerWeight(phase, fraction)
        fraction to
            Color.hsl(
                hue = from.hue,
                saturation = from.saturation,
                lightness = from.lightness + (to.lightness - from.lightness) * weight,
                alpha = base.alpha,
                colorSpace = SHIMMER_COLOR_SPACE,
            )
    }
}

internal data class Hsl(
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
)

/** [this] color's hue and saturation at the lightness of [target]. */
internal fun Color.withLightnessOf(target: Color): Color {
    val hsl = toHsl()
    return Color.hsl(hsl.hue, hsl.saturation, target.toHsl().lightness, alpha, SHIMMER_COLOR_SPACE)
}

internal fun Color.toHsl(): Hsl {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val lightness = (max + min) / 2f
    val delta = max - min
    if (delta == 0f) return Hsl(0f, 0f, lightness)
    val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val sector =
        when (max) {
            red -> (green - blue) / delta + if (green < blue) 6f else 0f
            green -> (blue - red) / delta + 2f
            else -> (red - green) / delta + 4f
        }
    return Hsl((sector * 60f).coerceIn(0f, 359.999f), saturation.coerceIn(0f, 1f), lightness)
}
