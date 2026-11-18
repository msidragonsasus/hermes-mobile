package com.m57.hermescontrol.ui.settings.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.config.resolveBaseUrl
import com.m57.hermescontrol.ui.common.SettingsFootnote
import com.m57.hermescontrol.ui.common.SettingsGroup
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.common.SettingsMenuItem
import com.m57.hermescontrol.ui.common.SettingsPanel
import com.m57.hermescontrol.ui.common.SettingsRow
import com.m57.hermescontrol.ui.settings.SectionCard
import com.m57.hermescontrol.ui.settings.SettingsUiState
import com.m57.hermescontrol.ui.settings.SettingsViewModel

@Composable
internal fun ConnectionSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    passwordVisible: Boolean,
    onPasswordVisibilityToggle: () -> Unit,
) {
    ConnectionProfilesGroup(
        profiles = state.profiles,
        selectedProfileId = state.selectedProfileId,
        baseUrl = state.baseUrl,
        onSelect = viewModel::selectProfile,
        onEdit = viewModel::openEditProfile,
        onDelete = viewModel::requestDeleteProfile,
        onAdd = viewModel::openAddProfile,
    )

    CredentialTransportSection(state, viewModel)

    // ── Dialogs ──────────────────────────────────────────────────────────

    if (state.showProfileDialog) {
        ProfileEditorDialog(
            isEditing = state.editingProfileId != null,
            name = state.dialogProfileName,
            baseUrl = state.dialogProfileBaseUrl,
            token = state.dialogProfileToken,
            error = state.dialogProfileError,
            onNameChange = viewModel::onDialogProfileNameChange,
            onBaseUrlChange = viewModel::onDialogProfileBaseUrlChange,
            onTokenChange = viewModel::onDialogProfileTokenChange,
            onSave = viewModel::saveProfileFromDialog,
            onDismiss = viewModel::closeProfileDialog,
        )
    }

    if (state.showDeleteConfirm) {
        DeleteProfileConfirmDialog(
            profileName = state.profileToDeleteName,
            onConfirm = viewModel::confirmDeleteProfile,
            onDismiss = viewModel::cancelDeleteProfile,
        )
    }
}

/** "接続を確認" as a row; its result is one footnote under the group, colored by outcome. */
@Composable
internal fun ConnectionCheckGroup(
    isTesting: Boolean,
    testResult: String?,
    onTest: () -> Unit,
) {
    SettingsGroupLabel(stringResource(R.string.settings_group_connection_check))
    SettingsGroup {
        row {
            SettingsRow(
                title = stringResource(R.string.settings_action_test_connection),
                enabled = !isTesting,
                onClick = onTest,
                trailing =
                    if (isTesting) {
                        { CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp) }
                    } else {
                        null
                    },
            )
        }
    }
    AnimatedVisibility(visible = testResult != null, enter = fadeIn(), exit = fadeOut()) {
        testResult?.let { result ->
            SettingsFootnote(
                result,
                color =
                    if (result.startsWith("✅")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
        }
    }
}

@Composable
internal fun ConnectionProfilesGroup(
    profiles: List<ConnectionProfile>,
    selectedProfileId: String?,
    baseUrl: String,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val editLabel = stringResource(R.string.settings_action_edit)
    val deleteLabel = stringResource(R.string.action_delete)
    SettingsGroupLabel(stringResource(R.string.settings_saved_profiles, profiles.size))
    SettingsGroup {
        profiles.forEach { profile ->
            row {
                SettingsRow(
                    title = profile.name,
                    modifier = Modifier.testTag("connection_profile_${profile.id}"),
                    subtitle = profile.resolveBaseUrl(baseUrl),
                    leading = { RadioButton(selected = profile.id == selectedProfileId, onClick = null) },
                    onClick = { onSelect(profile.id) },
                    actions =
                        listOf(
                            SettingsMenuItem(editLabel) { onEdit(profile.id) },
                            SettingsMenuItem(deleteLabel) { onDelete(profile.id) },
                        ),
                )
            }
        }
        row {
            SettingsRow(
                title = stringResource(R.string.settings_action_add_profile),
                leading = { Icon(Icons.Default.Add, null, Modifier.size(24.dp)) },
                onClick = onAdd,
            )
        }
    }
}
