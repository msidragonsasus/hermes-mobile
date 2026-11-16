package com.m57.hermescontrol.ui.chat.components

import android.graphics.drawable.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.asDrawable
import coil3.compose.AsyncImage
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalConversationStyle
import com.m57.hermescontrol.theme.LocalSpacing

/** Shared image surface: cropped pending thumbnail, or bounded, uncropped conversation media. */
@Composable
fun GifImageThumbnail(
    model: Any,
    contentDescription: String?,
    isGif: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var failed by remember(model) { mutableStateOf(false) }
    var loaded by remember(model) { mutableStateOf(false) }
    var ratio by remember(model) { mutableStateOf(4f / 3f) }
    var isPlaying by remember(model) { mutableStateOf(true) }
    var animation by remember(model) { mutableStateOf<Animatable?>(null) }
    DisposableEffect(animation) {
        val current = animation
        onDispose { current?.stop() }
    }
    val toggle = {
        isPlaying = !isPlaying
        if (isPlaying) animation?.start() else animation?.stop()
        Unit
    }
    val context = LocalContext.current
    val style = LocalConversationStyle.current
    val spacing = LocalSpacing.current
    BoxWithConstraints(modifier.widthIn(max = if (compact) style.pendingImageSize else style.messageImageMaxWidth)) {
        val width =
            if (compact) {
                minOf(maxWidth, style.pendingImageSize)
            } else {
                minOf(maxWidth, (style.messageImageMaxHeight * ratio).coerceAtLeast(style.actionSize))
            }
        val height =
            when {
                compact -> width
                failed -> 96.dp
                else -> (width / ratio).coerceIn(style.actionSize, style.messageImageMaxHeight)
            }
        Box(
            (if (failed && !compact) Modifier.width(width).heightIn(min = 96.dp) else Modifier.size(width, height))
                .clip(MaterialTheme.shapes.medium)
                .then(if (!loaded) Modifier.background(MaterialTheme.colorScheme.surfaceContainer) else Modifier)
                .clickable(role = Role.Button, onClick = onClick)
                .testTag(if (compact) "attachment_preview" else "conversation_image"),
        ) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                onSuccess = { result ->
                    failed = false
                    loaded = true
                    ratio =
                        result.result.image.width
                            .coerceAtLeast(1)
                            .toFloat() /
                        result.result.image.height
                            .coerceAtLeast(1)
                    val drawable = result.result.image.asDrawable(context.resources)
                    animation = drawable as? Animatable
                    if (isPlaying) animation?.start() else animation?.stop()
                },
                onError = {
                    failed = true
                    loaded = false
                },
                modifier =
                    Modifier.matchParentSize().then(
                        if (loaded) Modifier.testTag("conversation_image_loaded") else Modifier,
                    ),
                contentScale = if (compact) ContentScale.Crop else ContentScale.Fit,
            )
            if (!loaded && !failed) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp)
            }
            if (failed) {
                if (compact) {
                    Icon(
                        Icons.Outlined.BrokenImage,
                        stringResource(R.string.rich_answer_image_failed),
                        Modifier.align(Alignment.Center).size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.rich_answer_image_failed),
                        Modifier.align(Alignment.Center).padding(spacing.lg).testTag("conversation_image_error"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isGif && loaded) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(spacing.xs),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ) {
                    if (compact) {
                        Text(
                            "GIF",
                            Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        Row(
                            Modifier
                                .heightIn(min = style.actionSize)
                                .clickable(role = Role.Button, onClick = toggle)
                                .testTag("conversation_gif_toggle")
                                .padding(horizontal = spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                stringResource(if (isPlaying) R.string.gif_pause else R.string.gif_play),
                                Modifier.size(20.dp),
                            )
                            Text(" GIF", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
