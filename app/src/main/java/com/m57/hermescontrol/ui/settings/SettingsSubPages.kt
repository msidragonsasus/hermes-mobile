package com.m57.hermescontrol.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SettingsGroup
import com.m57.hermescontrol.ui.common.SettingsRow
import com.m57.hermescontrol.ui.settings.components.AboutSection
import com.m57.hermescontrol.ui.settings.components.ConnectionCheckGroup
import com.m57.hermescontrol.ui.settings.components.ConnectionSection

/**
 * Drill-down sub-pages for Settings (connection and About; the rest is inline on SettingsScreen). Each is its own NavKey destination
 * (see Navigation.kt) so the native back stack handles navigation — no
 * manual routing. The SettingsViewModel stays the single source of truth.
 *
 * All sub-pages pass `drawerGesturesEnabled = false` to [HermesScaffold] so the
 * modal drawer's swipe gestures are disabled and the drawer auto-closes when a
 * sub-page is entered — the single source of truth introduced in issue #619.
 */

@Composable
internal fun SettingsConnectionPage(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }

    HermesScaffold(
        title = { Text(stringResource(R.string.settings_connection)) },
        navigationIcon = NavIcon.Back(onBack),
        // Non-primary drill-down: opt out of drawer gestures so the scrim can't
        // get stuck open (issue #619). DrawerGestureController handles the close.
        drawerGesturesEnabled = false,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        ) {
            ConnectionSection(
                state = state,
                viewModel = viewModel,
                passwordVisible = passwordVisible,
                onPasswordVisibilityToggle = { passwordVisible = !passwordVisible },
            )

            ConnectionCheckGroup(
                isTesting = state.isTesting,
                testResult = state.testResult,
                onTest = viewModel::testConnection,
            )

            // Destructive and final: an error-colored row in its own last group, confirmed before it runs.
            SettingsGroup(Modifier.padding(top = 24.dp)) {
                row {
                    SettingsRow(
                        title = stringResource(R.string.settings_logout),
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { confirmLogout = true },
                    )
                }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { if (!loggingOut) confirmLogout = false },
            title = { Text(stringResource(R.string.settings_logout_confirm_title)) },
            text = { Text(stringResource(R.string.settings_logout_confirm_desc)) },
            confirmButton = {
                TextButton(
                    enabled = !loggingOut,
                    onClick = {
                        loggingOut = true
                        viewModel.logout {
                            loggingOut = false
                            confirmLogout = false
                            onLogout()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.settings_logout))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !loggingOut,
                    onClick = { confirmLogout = false },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
internal fun SettingsAboutPage(
    onBack: () -> Unit,
    viewModel: AppUpdateViewModel =
        com.m57.hermescontrol.ui.common
            .sharedAppUpdateViewModel(),
) {
    val updateState by viewModel.state.collectAsStateWithLifecycle()
    val updateSourceInfo by viewModel.sourceInfo.collectAsStateWithLifecycle()
    val context = LocalContext.current
    HermesScaffold(
        title = { Text(stringResource(R.string.settings_sec_about)) },
        navigationIcon = NavIcon.Back(onBack),
        drawerGesturesEnabled = false,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AboutSection(
                updateState = updateState,
                updateSource = updateSourceInfo.source,
                serverLabel = updateSourceInfo.serverLabel,
                onSelectSource = viewModel::selectSource,
                onCheckUpdate = viewModel::checkForUpdate,
                onStartUpdate = viewModel::startUpdate,
                onCancelDownload = viewModel::cancelDownload,
                onOpenInstaller = viewModel::resumeInstallAfterPermission,
                onOpenInstallSettings = {
                    val intent =
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
            )
        }
    }
}
