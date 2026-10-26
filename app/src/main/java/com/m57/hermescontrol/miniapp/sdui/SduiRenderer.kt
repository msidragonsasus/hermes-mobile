package com.m57.hermescontrol.miniapp.sdui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing

/**
 * Renders an already validated component tree. The set is closed: `when` over the sealed
 * [SduiNode] is exhaustive, so there is no fallback that could draw something unrecognised.
 * Nothing here runs on composition; buttons only report their fixed [SduiAction].
 */
@Composable
internal fun SduiRenderer(
    nodes: List<SduiNode>,
    actionEnabled: (SduiAction) -> Boolean,
    onAction: (SduiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md)) {
        nodes.forEach { SduiNodeView(it, actionEnabled, onAction) }
    }
}

@Composable
private fun SduiNodeView(
    node: SduiNode,
    actionEnabled: (SduiAction) -> Boolean,
    onAction: (SduiAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    when (node) {
        is SduiSection -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    Text(
                        node.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    node.children.forEach { SduiNodeView(it, actionEnabled, onAction) }
                }
            }
        }

        is SduiColumn -> {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                node.children.forEach { SduiNodeView(it, actionEnabled, onAction) }
            }
        }

        is SduiText -> {
            Text(node.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
        }

        is SduiStatus -> {
            SduiStatusView(node.text, node.tone)
        }

        is SduiKeyValue -> {
            Column(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    node.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(node.value, style = MaterialTheme.typography.bodyLarge)
            }
        }

        is SduiButton -> {
            val modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("sdui_button_${node.action.wire}")
            val enabled = node.enabled && actionEnabled(node.action)
            if (node.action == SduiAction.MYPAGE_CHECK) {
                Button(
                    onClick = { onAction(node.action) },
                    enabled = enabled,
                    modifier = modifier,
                    shapes = ButtonDefaults.shapes(),
                ) { Text(node.label) }
            } else {
                OutlinedButton(
                    onClick = { onAction(node.action) },
                    enabled = enabled,
                    modifier = modifier,
                    shapes = ButtonDefaults.shapes(),
                ) { Text(node.label) }
            }
        }
    }
}

/** Tone is carried by icon and spoken label as well as colour. Colours are the semantic status set. */
@Composable
internal fun SduiStatusView(
    text: String,
    tone: SduiTone,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHermesStatusColors.current
    val spacing = LocalSpacing.current
    val (icon, tint, label) =
        when (tone) {
            SduiTone.INFO -> Triple(Icons.Filled.Info, colors.info, "情報")
            SduiTone.SUCCESS -> Triple(Icons.Filled.CheckCircle, colors.success, "成功")
            SduiTone.WARNING -> Triple(Icons.Filled.Warning, colors.warning, "注意")
            SduiTone.ERROR -> Triple(Icons.Filled.Error, colors.error, "エラー")
        }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(spacing.md).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = label, tint = tint)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
