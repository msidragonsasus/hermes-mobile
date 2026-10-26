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
import com.m57.hermescontrol.ui.settings.components.AboutSection
import com.m57.hermescontrol.ui.settings.components.ConnectionSection
import com.m57.hermescontrol.ui.settings.components.TestConnectionButton
import com.m57.hermescontrol.ui.settings.components.TestResultCard

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

    HermesScaffold(
        title = { Text(stringResource(R.string.settings_sec_connection)) },
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionSection(
                state = state,
                viewModel = viewModel,
                passwordVisible = passwordVisible,
                onPasswordVisibilityToggle = { passwordVisible = !passwordVisible },
            )

            TestResultCard(testResult = state.testResult)
            TestConnectionButton(
                isTesting = state.isTesting,
                onTest = viewModel::testConnection,
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Destructive but not primary: outlined in the error color, confirmed before it runs.
            OutlinedButton(
                onClick = { confirmLogout = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.settings_logout))
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.settings_logout_confirm_title)) },
            text = { Text(stringResource(R.string.settings_logout_confirm_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        viewModel.logout()
                        onLogout()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.settings_logout))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }, shapes = ButtonDefaults.shapes()) {
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
        viewModel {
            val app =
                this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("Application not available")
            // Explicit factory lambda: the default AndroidViewModelFactory
            // resolves ctors via reflection, which cannot see Kotlin
            // default-arg synthetic constructors — opening About crashed with
            // NoSuchMethodException AppUpdateViewModel.<init>(Application).
            AppUpdateViewModel(app)
        },
) {
    val updateState by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    if (updateState is com.m57.hermescontrol.data.update.AppUpdateState.NeedsUnknownSourcesPermission) {
                        viewModel.resumeInstallAfterPermission()
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
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
                onCheckUpdate = viewModel::checkForUpdate,
                onStartUpdate = viewModel::startUpdate,
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
