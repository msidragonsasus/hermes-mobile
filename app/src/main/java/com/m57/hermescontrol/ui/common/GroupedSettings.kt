package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R

/*
 * Grouped settings for the settings directory, its destinations and
 * connected-service details (DESIGN.md "Settings destinations"). Each section
 * is one rounded group with 16dp-inset hairlines inside; its label sits
 * outside and an explanation is one footnote below it. Rows put selection on
 * the leading side, a switch, status or chevron on the trailing side, and
 * per-item actions in one ︙ menu. These groups never wrap feeds.
 */

/** Hairline inset shared by every group, so dividers line up across screens. */
val SettingsDividerInset: Dp = 16.dp

/** Label above a [SettingsGroup]. */
@Composable
fun SettingsGroupLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One short explanation under a group, aligned with the row text. */
@Composable
fun SettingsFootnote(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    Text(
        text,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Collects the rows of one group; dividers are drawn between rows only. */
class SettingsGroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/**
 * One rounded group. Renders nothing when no row was added. In a lazy list a
 * group may be split into consecutive segments: only the [first] segment has
 * top corners, only the [last] has bottom corners, and segments are joined
 * by the same hairline used between rows.
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    dividerInset: Dp = SettingsDividerInset,
    first: Boolean = true,
    last: Boolean = true,
    content: SettingsGroupScope.() -> Unit,
) {
    val scope = SettingsGroupScope().apply(content)
    if (scope.rows.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = settingsSegmentShape(first, last),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            scope.rows.forEachIndexed { index, row ->
                if (index > 0) SettingsDivider(dividerInset)
                row()
            }
            if (!last) SettingsDivider(dividerInset)
        }
    }
}

@Composable
fun SettingsDivider(inset: Dp = SettingsDividerInset) {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = inset),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun settingsSegmentShape(
    first: Boolean,
    last: Boolean,
): androidx.compose.ui.graphics.Shape {
    val corners = MaterialTheme.shapes.extraLarge
    val square = CornerSize(0.dp)
    return corners.copy(
        topStart = if (first) corners.topStart else square,
        topEnd = if (first) corners.topEnd else square,
        bottomStart = if (last) corners.bottomStart else square,
        bottomEnd = if (last) corners.bottomEnd else square,
    )
}

/** Compound settings form, sharing the same surface as simple grouped rows. */
@Composable
fun SettingsPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsGroup(modifier) {
        row {
            Column(
                Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
                content = content,
            )
        }
    }
}

/** A lazy-list row in a continuous group; only the group's outer corners are rounded. */
@Composable
fun SettingsListItem(
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsGroup(modifier, first = first, last = last) {
        row {
            Column(
                Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
                content = content,
            )
        }
    }
}

/**
 * A row inside [SettingsGroup]: optional leading radio/avatar/icon, title, one
 * meta line, then on the trailing side in this order: value text, [status]
 * badge, chevron, a [checked] switch, the [actions] ︙ menu, or a custom
 * [trailing] control. Icon-button trailing content keeps a 4dp end inset so
 * its glyph lines up with a chevron or switch at 16dp.
 *
 * With [checked] and no [onClick], the whole row toggles the switch.
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
    status: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
    maxLines: Int = 2,
    subtitleMaxLines: Int = 2,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    actions: List<SettingsMenuItem> = emptyList(),
    actionsEnabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    wrapValue: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val rowToggles = checked != null && onClick == null && onCheckedChange != null
    val iconTrailing = trailing != null || actions.isNotEmpty()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(
                when {
                    rowToggles -> {
                        Modifier.toggleable(
                            value = checked == true,
                            enabled = enabled,
                            role = Role.Switch,
                            onValueChange = { onCheckedChange?.invoke(it) },
                        )
                    }

                    onClick != null -> {
                        Modifier.clickable(enabled = enabled, onClick = onClick)
                    }

                    else -> {
                        Modifier
                    }
                },
            ).padding(start = 16.dp, end = if (iconTrailing) 4.dp else 16.dp, top = 10.dp, bottom = 10.dp),
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
                    color =
                        if (subtitleColor != Color.Unspecified) {
                            subtitleColor
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        value?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (wrapValue) Modifier.weight(1f, fill = false) else Modifier,
                maxLines = if (wrapValue) Int.MAX_VALUE else 1,
                textAlign = TextAlign.End,
                overflow = TextOverflow.Ellipsis,
            )
        }
        status?.invoke()
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (checked != null) {
            Switch(
                checked = checked,
                onCheckedChange = if (rowToggles) null else onCheckedChange,
                enabled = enabled && onCheckedChange != null,
                modifier = Modifier.semantics { contentDescription = title },
            )
        }
        if (actions.isNotEmpty()) {
            SettingsOverflowMenu(
                items = actions,
                contentDescription = stringResource(R.string.settings_actions_for, title),
                enabled = actionsEnabled,
            )
        }
        trailing?.invoke(this)
    }
}

/** A scrollable picker for settings destinations, with the same row grammar as the page. */
@Composable
fun SettingsPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), content = content) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
fun SettingsChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        modifier = modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        subtitle = subtitle,
        enabled = enabled,
        maxLines = Int.MAX_VALUE,
        subtitleMaxLines = Int.MAX_VALUE,
        leading = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
    )
}

/** Last row of a compound form group: one right-aligned primary pill that submits the form. */
@Composable
fun SettingsFormSubmit(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    buttonModifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        PrimaryPillButton(onClick = onClick, modifier = buttonModifier, enabled = enabled) {
            if (busy) {
                CircularProgressIndicator(
                    Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(label)
        }
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
