package com.m57.hermescontrol.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R

/**
 * Home top-bar entry into the shared update dialog. [latestVersion] must come from the
 * updater's authoritative candidate; null means no confirmed newer release, so nothing renders.
 */
@Composable
internal fun HomeUpdateAction(
    latestVersion: String?,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (latestVersion == null) return
    val description = stringResource(R.string.home_update_content_desc, latestVersion.trimStart('v'))
    TextButton(
        onClick = onUpdate,
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .testTag("home_update_button")
                .semantics { contentDescription = description },
        shapes = ButtonDefaults.shapes(),
    ) {
        Icon(
            imageVector = Icons.Default.SystemUpdate,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.home_update_action), maxLines = 1)
    }
}
