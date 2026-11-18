package com.m57.hermescontrol.ui.settings

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.ui.common.SettingsRow

/** Settings navigation uses the same row as connected-service details. */
@Composable
internal fun SettingsLinkRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
) {
    SettingsRow(
        title = label,
        subtitle = description,
        modifier = modifier.heightIn(min = 60.dp),
        leading = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(24.dp)) } },
        chevron = true,
        onClick = onClick,
    )
}
