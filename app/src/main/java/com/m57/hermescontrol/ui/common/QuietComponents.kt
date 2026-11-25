package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.LocalHermesStatusColors

/**
 * The single high-emphasis action of a screen (DESIGN.md "Buttons and actions"):
 * a solid Mono Expressive `primary` pill, sized to its label.
 */
@Composable
fun PrimaryPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 40.dp),
        shapes = ButtonDefaults.shapes(),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        content = content,
    )
}

/**
 * Dense list row: no surface, 10dp vertical padding, hairline divider below.
 * Use it instead of a card per item (DESIGN.md "Layout").
 */
@Composable
fun CompactListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    rowModifier: Modifier = Modifier,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickModifier =
        if (onClick != null) {
            Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick)
        } else {
            Modifier
        }
    Column(modifier.fillMaxWidth()) {
        Row(
            rowModifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(clickModifier)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leading?.invoke(this)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
            trailing?.invoke(this)
        }
        footer?.invoke(this)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

enum class NoticeTone { Info, Warning, Error }

/**
 * One-line status notice (DESIGN.md "Notices and status"): 20dp icon + bodySmall text +
 * optional trailing text button on surfaceContainerLow. Never a tall banner.
 */
@Composable
fun InlineNotice(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val status = LocalHermesStatusColors.current
    val (icon, tint) =
        when (tone) {
            NoticeTone.Info -> Icons.Outlined.Info to MaterialTheme.colorScheme.onSurfaceVariant
            NoticeTone.Warning -> Icons.Outlined.WarningAmber to status.warning
            NoticeTone.Error -> Icons.Outlined.ErrorOutline to status.error
        }
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .padding(start = 12.dp, end = if (actionLabel != null) 4.dp else 12.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
