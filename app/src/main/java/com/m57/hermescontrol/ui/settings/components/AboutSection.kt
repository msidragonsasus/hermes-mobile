package com.m57.hermescontrol.ui.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.update.AppUpdateState
import com.m57.hermescontrol.data.update.UpdateSource
import com.m57.hermescontrol.data.update.releaseTag
import com.m57.hermescontrol.ui.common.PrimaryPillButton
import com.m57.hermescontrol.ui.common.updateFailureText
import com.m57.hermescontrol.ui.common.updateSourceLabel
import com.m57.hermescontrol.ui.settings.InfoRow
import com.m57.hermescontrol.ui.settings.SectionCard

@Composable
internal fun AboutSection(
    updateState: AppUpdateState = AppUpdateState.Idle,
    updateSource: UpdateSource = UpdateSource.CONNECTED_SERVER,
    serverLabel: String = "",
    onSelectSource: (UpdateSource) -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onStartUpdate: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onOpenInstaller: () -> Unit = {},
    onOpenInstallSettings: () -> Unit = {},
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_about_app_name),
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
            )
        }

        Spacer(modifier = Modifier.padding(top = 8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.padding(top = 8.dp))

        InfoRow(
            label = stringResource(R.string.settings_about_version),
            value = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
        UpdateSourceRow(
            source = updateSource,
            serverLabel = serverLabel,
            enabled = updateState !is AppUpdateState.Downloading && updateState !is AppUpdateState.Verifying,
            onSelectSource = onSelectSource,
        )
        UpdateRow(
            state = updateState,
            onCheckUpdate = onCheckUpdate,
            onStartUpdate = onStartUpdate,
            onCancelDownload = onCancelDownload,
            onOpenInstaller = onOpenInstaller,
            onOpenInstallSettings = onOpenInstallSettings,
        )
        InfoRow(
            label = stringResource(R.string.settings_about_build),
            value =
                if (BuildConfig.DEBUG) {
                    stringResource(R.string.settings_about_debug)
                } else {
                    stringResource(R.string.settings_about_release)
                },
        )
        if (BuildConfig.GIT_SHA.isNotBlank()) {
            InfoRow(
                label = stringResource(R.string.settings_about_commit),
                value = BuildConfig.GIT_SHA,
            )
        }

        Spacer(modifier = Modifier.padding(top = 12.dp))
        Text(
            text = "https://github.com/Hy4ri/hermes-mobile",
            style =
                MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.primary,
                ),
        )
    }
}

/**
 * Update source as a label/value row with a dropdown picker (DESIGN.md:
 * dropdown pickers, no cycle buttons, no boxed fields). The value always names
 * where an update comes from, including the bound server.
 */
@Composable
private fun UpdateSourceRow(
    source: UpdateSource,
    serverLabel: String,
    enabled: Boolean,
    onSelectSource: (UpdateSource) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(vertical = 2.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_about_update_source),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = updateSourceLabel(source, serverLabel),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                UpdateSource.CONNECTED_SERVER to R.string.settings_about_update_source_server_desc,
                UpdateSource.UPSTREAM_GITHUB to R.string.settings_about_update_source_upstream_desc,
            ).forEach { (option, description) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(updateSourceLabel(option))
                            Text(
                                text = stringResource(description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelectSource(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun UpdateRow(
    state: AppUpdateState,
    onCheckUpdate: () -> Unit,
    onStartUpdate: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenInstaller: () -> Unit,
    onOpenInstallSettings: () -> Unit,
) {
    // GitHub release tags carry a leading "v" (e.g. "v1.21.1"); the string
    // templates below already prepend one, so strip it here to avoid "vv".
    val displayTag = state.releaseTag()?.trimStart('v').orEmpty()
    val tappable =
        state is AppUpdateState.Idle ||
            state is AppUpdateState.UpToDate ||
            state is AppUpdateState.Error
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable(enabled = tappable) { onCheckUpdate() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_about_update),
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
            Spacer(modifier = Modifier.weight(1f))
            ValueText(
                when (state) {
                    is AppUpdateState.Idle -> {
                        stringResource(R.string.settings_about_update_check)
                    }

                    is AppUpdateState.Checking -> {
                        stringResource(R.string.settings_about_update_checking)
                    }

                    is AppUpdateState.UpToDate -> {
                        stringResource(R.string.settings_about_update_uptodate, displayTag)
                    }

                    is AppUpdateState.UpdateAvailable -> {
                        stringResource(R.string.settings_about_update_available, displayTag)
                    }

                    is AppUpdateState.Downloading -> {
                        stringResource(R.string.settings_about_update_downloading, (state.progress * 100).toInt())
                    }

                    is AppUpdateState.Verifying -> {
                        stringResource(R.string.settings_about_update_verifying)
                    }

                    is AppUpdateState.InstallerOpened -> {
                        stringResource(R.string.settings_about_update_installer_opened, displayTag)
                    }

                    is AppUpdateState.NeedsUnknownSourcesPermission -> {
                        stringResource(R.string.settings_about_update_allow_sources)
                    }

                    is AppUpdateState.Error -> {
                        updateFailureText(state.failure)
                    }
                },
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        // Action line: one primary pill at most (DESIGN.md), secondary actions as text buttons.
        when (state) {
            is AppUpdateState.UpdateAvailable -> {
                PrimaryPillButton(onClick = onStartUpdate, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.settings_about_update_action))
                }
            }

            is AppUpdateState.Downloading -> {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                TextButton(onClick = onCancelDownload) { Text(stringResource(R.string.action_cancel)) }
            }

            is AppUpdateState.Verifying -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }

            is AppUpdateState.InstallerOpened -> {
                TextButton(onClick = onOpenInstaller) {
                    Text(stringResource(R.string.settings_about_update_open_installer))
                }
            }

            is AppUpdateState.NeedsUnknownSourcesPermission -> {
                TextButton(onClick = onOpenInstallSettings) {
                    Text(stringResource(R.string.settings_about_update_open_settings))
                }
            }

            is AppUpdateState.Error -> {
                TextButton(onClick = onCheckUpdate) { Text(stringResource(R.string.settings_about_update_retry)) }
            }

            else -> {}
        }
    }
}

@Composable
private fun ValueText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style =
            MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
    )
}
