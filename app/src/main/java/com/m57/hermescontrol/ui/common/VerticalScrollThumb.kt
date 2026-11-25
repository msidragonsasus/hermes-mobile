package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Draws an always-visible scroll cue in the viewport. Apply before verticalScroll. */
@Composable
internal fun Modifier.verticalScrollThumb(state: ScrollState): Modifier {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    return drawWithContent {
        drawContent()
        val thumb =
            verticalScrollThumbGeometry(size.height, state.maxValue, state.value, 16.dp.toPx())
                ?: return@drawWithContent
        val width = 3.dp.toPx()
        val inset = 2.dp.toPx()
        val left = if (layoutDirection == LayoutDirection.Ltr) size.width - inset - width else inset
        drawRoundRect(
            color,
            topLeft = Offset(left, thumb.top),
            size = Size(width, thumb.height),
            cornerRadius = CornerRadius(width / 2),
        )
    }
}

internal data class VerticalScrollThumbGeometry(
    val top: Float,
    val height: Float,
)

internal fun verticalScrollThumbGeometry(
    viewportHeight: Float,
    scrollRange: Int,
    scrollOffset: Int,
    minHeight: Float,
): VerticalScrollThumbGeometry? {
    if (viewportHeight <= 0 || scrollRange <= 0 || scrollRange == Int.MAX_VALUE) return null
    val proportionalHeight = viewportHeight * (viewportHeight / (viewportHeight + scrollRange))
    val height = maxOf(proportionalHeight, minHeight).coerceAtMost(viewportHeight)
    val progress = scrollOffset.coerceIn(0, scrollRange).toFloat() / scrollRange
    return VerticalScrollThumbGeometry((viewportHeight - height) * progress, height)
}
