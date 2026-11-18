package com.m57.hermescontrol.ui.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.SettingsFootnote
import com.m57.hermescontrol.ui.common.SettingsGroup
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.common.SettingsRow
import com.m57.hermescontrol.ui.settings.SettingsUiState
import com.m57.hermescontrol.ui.settings.SettingsViewModel

@Composable
internal fun CredentialTransportSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    LaunchedEffect(viewModel) { viewModel.observeCredentialTransport() }
    val title = stringResource(R.string.settings_credential_http_title)
    SettingsGroupLabel(stringResource(R.string.settings_group_credential_http))
    SettingsGroup {
        row {
            // The switch keeps its own callback: turning it on opens the confirmation dialog below.
            SettingsRow(
                title = title,
                subtitle =
                    listOf(
                        state.credentialTransportBaseUrl,
                        stringResource(R.string.settings_credential_http_profile, state.credentialTransportProfile),
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                checked = state.credentialTransportAllowed,
                onCheckedChange = viewModel::onCredentialTransportChange,
                enabled = state.credentialTransportEligible,
            )
        }
    }
    SettingsFootnote(
        stringResource(
            when {
                state.credentialTransportBaseUrl.startsWith("https://") -> R.string.settings_credential_http_https
                state.credentialTransportEligible -> R.string.settings_credential_http_description
                else -> R.string.settings_credential_http_ineligible
            },
        ),
    )
    if (state.credentialTransportError) {
        SettingsFootnote(
            stringResource(R.string.settings_credential_http_error),
            color = MaterialTheme.colorScheme.error,
        )
    }
    state.credentialTransportConfirmEndpoint?.let { endpoint ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCredentialTransportConfirmation,
            title = { Text(stringResource(R.string.settings_credential_http_confirm_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
                ) {
                    Text(endpoint)
                    Text(
                        stringResource(
                            R.string.settings_credential_http_profile,
                            state.credentialTransportConfirmProfile.orEmpty(),
                        ),
                    )
                    Text(stringResource(R.string.settings_credential_http_risk))
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmCredentialTransport, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.settings_credential_http_allow))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissCredentialTransportConfirmation,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
