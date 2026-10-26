package com.m57.hermescontrol.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.miniapp.BundledMiniAppExamples
import com.m57.hermescontrol.miniapp.contract.CoreMiniAppPermission
import com.m57.hermescontrol.miniapp.contract.MiniAppContractException
import com.m57.hermescontrol.miniapp.contract.MiniAppManifest
import com.m57.hermescontrol.miniapp.contract.MiniAppPermission
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

private sealed interface ExamplesState {
    data object Loading : ExamplesState

    data object Failed : ExamplesState

    data class Ready(
        val examples: List<MiniAppManifest>,
    ) : ExamplesState
}

@Composable
fun MiniAppsScreen(showExamples: Boolean = false) {
    if (!showExamples) {
        HermesScaffold(title = { Text(stringResource(R.string.shell_apps)) }, drawerGesturesEnabled = false) {
            val spacing = LocalSpacing.current
            LazyVerticalGrid(
                columns = GridCells.Adaptive(88.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                modifier = Modifier.fillMaxSize().testTag("miniapps_list"),
                contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.xl),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                item(key = "pc-power") {
                    AppLauncherIcon(
                        label = stringResource(R.string.apps_pc_power),
                        icon = Icons.Outlined.PowerSettingsNew,
                        tag = "pc_power",
                    ) {
                        com.m57.hermescontrol.NavigationController
                            .navigateTo(com.m57.hermescontrol.pcpower.PcPowerScreenKey)
                    }
                }
                item(key = "career") {
                    AppLauncherIcon(
                        label = stringResource(R.string.apps_career),
                        icon = Icons.Outlined.WorkOutline,
                        tag = "career",
                    ) {
                        com.m57.hermescontrol.NavigationController
                            .navigateTo(com.m57.hermescontrol.career.CareerScreenKey)
                    }
                }
                item(key = "terminal") {
                    AppLauncherIcon(
                        label = "ターミナル",
                        icon = Icons.Outlined.Terminal,
                        tag = "terminal",
                    ) {
                        com.m57.hermescontrol.NavigationController
                            .navigateTo(com.m57.hermescontrol.terminal.TerminalScreenKey)
                    }
                }
            }
        }
        return
    }
    val context = LocalContext.current.applicationContext
    val state by produceState<ExamplesState>(ExamplesState.Loading, context) {
        value =
            withContext(Dispatchers.IO) {
                try {
                    ExamplesState.Ready(BundledMiniAppExamples.load(context.assets))
                } catch (
                    _: IOException,
                ) {
                    ExamplesState.Failed
                } catch (_: MiniAppContractException) {
                    ExamplesState.Failed
                }
            }
    }
    HermesScaffold(
        title = {
            Text(stringResource(R.string.management_examples))
        },
        navigationIcon =
            com.m57.hermescontrol.ui.common.NavIcon.Back {
                com.m57.hermescontrol.NavigationController
                    .goBack()
            },
        drawerGesturesEnabled = false,
    ) {
        when (val current = state) {
            ExamplesState.Loading -> LoadingState()
            ExamplesState.Failed -> ErrorState(message = stringResource(R.string.miniapps_read_error))
            is ExamplesState.Ready -> MiniAppsList(current.examples)
        }
    }
}

@Composable
private fun MiniAppsList(examples: List<MiniAppManifest>) {
    val spacing = LocalSpacing.current
    val locale = LocalConfiguration.current.locales[0]
    LazyColumn(
        Modifier.padding(horizontal = spacing.md).testTag("miniapps_list"),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            Text(stringResource(R.string.miniapps_preparing), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.miniapps_examples_explanation), style = MaterialTheme.typography.bodyMedium)
        }
        items(examples, key = { it.id }) { manifest ->
            var expanded by rememberSaveable(manifest.id) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth().testTag("miniapp_${manifest.id}")) {
                Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                        Icon(
                            when (manifest.id) {
                                "career" -> Icons.Default.Work
                                "university" -> Icons.Default.School
                                "development" -> Icons.Default.Code
                                else -> Icons.Default.Apps
                            },
                            contentDescription = null,
                        )
                        Text(localized(manifest.name, locale), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(stringResource(R.string.miniapps_unavailable), style = MaterialTheme.typography.labelLarge)
                    manifest.description?.let { Text(localized(it, locale)) }
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.testTag("miniapp_details_${manifest.id}"),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(
                            stringResource(
                                if (expanded) R.string.miniapps_close_details else R.string.miniapps_review_details,
                            ),
                        )
                    }
                    if (expanded) {
                        Text(
                            stringResource(
                                R.string.miniapps_publisher_version,
                                manifest.publisher.name,
                                manifest.version,
                            ),
                        )
                        Text(
                            stringResource(R.string.miniapps_permissions_not_granted),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        manifest.permissions.forEach { permission ->
                            Text(permissionLabel(requireNotNull(MiniAppPermission.parse(permission))))
                        }
                        Text(
                            stringResource(R.string.miniapps_no_activation),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppLauncherIcon(
    label: String,
    icon: ImageVector,
    tag: String,
    onClick: () -> Unit,
) {
    Box(Modifier.testTag("${tag}_app")) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(vertical = LocalSpacing.current.sm)
                    .testTag("${tag}_open"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(30.dp),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun localized(
    values: Map<String, String>,
    locale: Locale,
): String =
    values[locale.toLanguageTag()] ?: values[locale.language] ?: values["en"] ?: values["ja"] ?: values.values.first()

@Composable
private fun permissionLabel(permission: MiniAppPermission): String =
    when (permission) {
        is MiniAppPermission.PluginApi -> {
            stringResource(R.string.miniapps_plugin_api, permission.pluginId, permission.scope.orEmpty())
        }

        is MiniAppPermission.Core -> {
            stringResource(
                when (permission.permission) {
                    CoreMiniAppPermission.SESSIONS_READ -> R.string.miniapps_permission_sessions_read
                    CoreMiniAppPermission.SESSIONS_WRITE -> R.string.miniapps_permission_sessions_write
                    CoreMiniAppPermission.RUNS_CREATE -> R.string.miniapps_permission_runs_create
                    CoreMiniAppPermission.RUNS_READ -> R.string.miniapps_permission_runs_read
                    CoreMiniAppPermission.RUNS_STEER -> R.string.miniapps_permission_runs_steer
                    CoreMiniAppPermission.RUNS_INTERRUPT -> R.string.miniapps_permission_runs_interrupt
                    CoreMiniAppPermission.APPROVALS_RESPOND -> R.string.miniapps_permission_approvals
                    CoreMiniAppPermission.JOBS_READ -> R.string.miniapps_permission_jobs_read
                    CoreMiniAppPermission.JOBS_MANAGE -> R.string.miniapps_permission_jobs_manage
                    CoreMiniAppPermission.MEMORY_READ -> R.string.miniapps_permission_memory_read
                    CoreMiniAppPermission.MEMORY_WRITE -> R.string.miniapps_permission_memory_write
                    CoreMiniAppPermission.FILES_READ -> R.string.miniapps_permission_files_read
                    CoreMiniAppPermission.FILES_WRITE -> R.string.miniapps_permission_files_write
                    CoreMiniAppPermission.CAMERA -> R.string.miniapps_permission_camera
                    CoreMiniAppPermission.PHOTOS -> R.string.miniapps_permission_photos
                    CoreMiniAppPermission.DEVICE_FILES -> R.string.miniapps_permission_device_files
                    CoreMiniAppPermission.MICROPHONE -> R.string.miniapps_permission_microphone
                    CoreMiniAppPermission.NOTIFICATIONS -> R.string.miniapps_permission_notifications
                    CoreMiniAppPermission.SHARE_RECEIVE -> R.string.miniapps_permission_share
                    CoreMiniAppPermission.OPEN_URL -> R.string.miniapps_permission_open_url
                },
            )
        }
    }
