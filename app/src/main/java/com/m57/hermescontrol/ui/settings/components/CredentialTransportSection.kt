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
import com.m57.hermescontrol.ui.settings.SectionCard
import com.m57.hermescontrol.ui.settings.SettingsUiState
import com.m57.hermescontrol.ui.settings.SettingsViewModel

@Composable
internal fun CredentialTransportSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    LaunchedEffect(viewModel) { viewModel.observeCredentialTransport() }
    val title = stringResource(R.string.settings_credential_http_title)
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                modifier = Modifier.semantics { contentDescription = title },
                checked = state.credentialTransportAllowed,
                enabled = state.credentialTransportEligible,
                onCheckedChange = viewModel::onCredentialTransportChange,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
            )
        }
        Text(state.credentialTransportBaseUrl, style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(R.string.settings_credential_http_profile, state.credentialTransportProfile),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(
                when {
                    state.credentialTransportBaseUrl.startsWith("https://") -> R.string.settings_credential_http_https
                    state.credentialTransportEligible -> R.string.settings_credential_http_description
                    else -> R.string.settings_credential_http_ineligible
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.credentialTransportError) {
            Text(
                stringResource(R.string.settings_credential_http_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
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
