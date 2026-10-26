package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Grouped settings for a single connected service (DESIGN.md "Connected service
 * screens", user decision 2026-10-24). Each section is one rounded group with
 * hairline dividers inside; the section label sits outside the group. Use only on
 * management/detail screens, never for feeds or lists of items.
 */

/** Label above a [SettingsGroup]. */
@Composable
fun SettingsGroupLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Collects the rows of one group; dividers are drawn between rows only. */
class SettingsGroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/** One rounded group. Renders nothing when no row was added. */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: SettingsGroupScope.() -> Unit,
) {
    val scope = SettingsGroupScope().apply(content)
    if (scope.rows.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            scope.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                row()
            }
        }
    }
}

/**
 * A row inside [SettingsGroup]: optional leading avatar/icon, title, one meta line,
 * and either trailing value text with a chevron or a trailing control (menu).
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = Color.Unspecified,
    titleColor: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
    value: String? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
    maxLines: Int = 2,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color =
                    when {
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        titleColor != Color.Unspecified -> titleColor
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (subtitleColor != Color.Unspecified) subtitleColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        value?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke(this)
    }
}

/** Round initial avatar for an account row. The color only tells accounts apart. */
@Composable
fun SettingsAvatar(
    text: String,
    seed: String = text,
) {
    val colors =
        listOf(
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
        )
    val (container, content) = colors[Math.floorMod(seed.hashCode(), colors.size)]
    Box(
        Modifier.size(40.dp).background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.take(2), style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/** Rounded-square service mark used in the header row of a service screen. */
@Composable
fun SettingsServiceMark(content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** One overflow item: label and action. */
data class SettingsMenuItem(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** Trailing ︙ button with a dropdown; used by header and account rows. */
@Composable
fun SettingsOverflowMenu(
    items: List<SettingsMenuItem>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    enabled = item.enabled,
                    onClick = {
                        open = false
                        item.onClick()
                    },
                )
            }
        }
    }
}
