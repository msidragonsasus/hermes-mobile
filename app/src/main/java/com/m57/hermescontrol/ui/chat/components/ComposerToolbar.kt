package com.m57.hermescontrol.ui.chat.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R

/** Reasoning levels the gateway accepts, in slider order, with their labels. */
internal val reasoningLevels: List<Pair<String, Int>> =
    listOf(
        "none" to R.string.chat_reasoning_none,
        "minimal" to R.string.chat_reasoning_minimal,
        "low" to R.string.chat_reasoning_low,
        "medium" to R.string.chat_reasoning_medium,
        "high" to R.string.chat_reasoning_high,
        "xhigh" to R.string.chat_reasoning_xhigh,
        "max" to R.string.chat_reasoning_max,
        "ultra" to R.string.chat_reasoning_ultra,
    )

@StringRes
internal fun reasoningLabelRes(level: String?): Int =
    reasoningLevels.firstOrNull { it.first == (level ?: "medium") }?.second ?: R.string.chat_reasoning_medium

/** Levels offered for the current model; "none" is dropped when the model always reasons (issue #946). */
internal fun availableReasoningLevels(canDisableReasoning: Boolean?): List<Pair<String, Int>> =
    if (canDisableReasoning == false) reasoningLevels.filterNot { it.first == "none" } else reasoningLevels

/** `openai-codex/gpt-6.1-sol` → `gpt-6.1-sol`: the provider prefix is noise in a one-line label. */
fun friendlyModelName(model: String?): String? = model?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

/**
 * Model and reasoning controls on their own quiet row beneath the input capsule.
 * Plain text buttons (no filled chips) so nothing looks permanently "selected". The reasoning
 * button opens the floating level slider ([ReasoningLevelPanel]) above the composer.
 */
@Composable
fun ComposerOptionsRow(
    currentSessionModel: String?,
    reasoningLevel: String?,
    onModelTap: () -> Unit,
    onReasoningTap: () -> Unit,
    modifier: Modifier = Modifier,
    supportsReasoning: Boolean? = null,
    enabled: Boolean = true,
    reasoningDisplayName: String? = null,
    compact: Boolean = false,
) {
    // Compact sits inside the open composer beside send: no prefixes, stronger labels.
    val labelColor = if (compact) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val modelName = friendlyModelName(currentSessionModel) ?: stringResource(R.string.chat_model_default)
    val effortName =
        reasoningDisplayName ?: stringResource(
            R.string.chat_reasoning_label,
            stringResource(reasoningLabelRes(reasoningLevel)),
        )
    Row(
        modifier = (if (compact) modifier else modifier.fillMaxWidth()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TextButton(
            onClick = onModelTap,
            enabled = enabled,
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 220.dp).testTag("model_chip"),
        ) {
            Text(
                text = if (compact) modelName else "model: $modelName",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (compact) FontWeight.SemiBold else null,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!compact) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = labelColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        TextButton(
            onClick = onReasoningTap,
            enabled = enabled && supportsReasoning != false,
            modifier = Modifier.testTag("reasoning_chip"),
        ) {
            if (!compact) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    tint = labelColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = if (compact) effortName else "effort: $effortName",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (compact) FontWeight.SemiBold else null,
                color = labelColor,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/**
 * Floating reasoning picker: "**High** reasoning" over a pill-shaped stepped slider. Tap a stop or
 * drag the thumb; the level is applied when the finger lifts.
 */
@Composable
fun ReasoningLevelPanel(
    reasoningLevel: String?,
    canDisableReasoning: Boolean?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val levels = availableReasoningLevels(canDisableReasoning)
    val committed = levels.indexOfFirst { it.first == (reasoningLevel ?: "medium") }.coerceAtLeast(0)
    var preview by remember(committed) { mutableIntStateOf(committed) }
    val labels = levels.map { stringResource(it.second) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.testTag("reasoning_panel").padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = labels[preview] + stringResource(R.string.chat_reasoning_slider_suffix),
                style = MaterialTheme.typography.titleMedium,
            )
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                SteppedSlider(
                    count = levels.size,
                    selected = preview,
                    onPreview = { preview = it },
                    onCommit = { onSelect(levels[it].first) },
                    modifier = Modifier.padding(4.dp),
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                levels.forEachIndexed { index, (level, _) ->
                    FilterChip(
                        selected = preview == index,
                        onClick = {
                            preview = index
                            onSelect(level)
                        },
                        label = { Text(labels[index]) },
                        modifier = Modifier.testTag("reasoning_level_$level"),
                        border = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun SteppedSlider(
    count: Int,
    selected: Int,
    onPreview: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumb = 44.dp
    val density = LocalDensity.current
    val track = MaterialTheme.colorScheme.primary
    val dot = MaterialTheme.colorScheme.onPrimary
    val restDot = MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(modifier.fillMaxWidth().height(thumb)) {
        val stepWidth = (maxWidth - thumb) / (count - 1).coerceAtLeast(1)
        val thumbOffset by animateDpAsState(stepWidth * selected, label = "thumb")

        fun indexAt(x: Float): Int {
            val px = with(density) { (x - thumb.toPx() / 2) / stepWidth.toPx() }
            return px.let { kotlin.math.round(it).toInt() }.coerceIn(0, count - 1)
        }
        var dragIndex by remember { mutableIntStateOf(selected) }
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .pointerInput(count, stepWidth, density) {
                    detectTapGestures { offset ->
                        val i = indexAt(offset.x)
                        onPreview(i)
                        onCommit(i)
                    }
                }.pointerInput(count, stepWidth, density) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragIndex = indexAt(it.x) },
                        onDragEnd = { onCommit(dragIndex) },
                        onHorizontalDrag = { change, _ ->
                            dragIndex = indexAt(change.position.x)
                            onPreview(dragIndex)
                        },
                    )
                },
        ) {
            // Filled track from the start to the thumb.
            Box(
                Modifier
                    .height(thumb)
                    .width(thumbOffset + thumb)
                    .clip(CircleShape)
                    .background(track),
            )
            repeat(count) { i ->
                Box(
                    Modifier.offset(x = stepWidth * i).size(thumb),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (i <= selected) dot.copy(alpha = 0.8f) else restDot.copy(alpha = 0.6f)),
                    )
                }
            }
            Box(
                Modifier
                    .offset(x = thumbOffset)
                    .size(thumb)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimary),
            )
        }
    }
}
