@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.m57.hermescontrol.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.ws.VaultPromptKind
import com.m57.hermescontrol.data.ws.toJsonElement
import com.m57.hermescontrol.theme.LocalSpacing

/** The same rounded fields as Settings. All input is disposable, never rememberSaveable. */
@Composable
fun VaultPromptDialog(
    prompt: VaultPromptUi,
    onRespond: (String) -> Boolean,
) {
    val request = prompt.request
    var identifier by remember(request.id) { mutableStateOf("") }
    var password by remember(request.id) { mutableStateOf("") }
    val login = request.kind == VaultPromptKind.LOGIN
    val origin = request.origin?.let(::vaultOrigin)
    val site = if (login) origin.orEmpty() else request.site.orEmpty()
    val title =
        when (request.kind) {
            VaultPromptKind.LOGIN -> R.string.vault_prompt_login_title
            VaultPromptKind.CODE -> R.string.vault_prompt_code_title
            VaultPromptKind.UNLOCK -> R.string.vault_prompt_unlock_title
        }
    val valid = password.isNotEmpty() && (!login || (identifier.isNotBlank() && origin != null))

    AlertDialog(
        modifier = Modifier.testTag("vault_prompt"),
        // Only the explicit decline button sends an empty answer. Recomposition/navigation never does.
        onDismissRequest = {},
        properties =
            DialogProperties(
                securePolicy = SecureFlagPolicy.SecureOn,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        title = { Text(stringResource(title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
            ) {
                Text(
                    site,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("vault_prompt_site"),
                )
                if (login) {
                    Text(stringResource(R.string.vault_prompt_login_body))
                    LoginField(identifier, {
                        identifier = it
                    }, R.string.vault_identifier, "vault_prompt_identifier", false, contentType = ContentType.Username)
                }
                LoginField(
                    password,
                    { password = it },
                    if (request.kind == VaultPromptKind.CODE) R.string.vault_prompt_code else R.string.vault_password,
                    "vault_prompt_password",
                    false,
                    KeyboardType.Password,
                    contentType = if (login) ContentType.Password else null,
                )
                if (prompt.sendFailed) {
                    Text(
                        stringResource(R.string.vault_prompt_send_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                enabled = valid,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.testTag("vault_prompt_submit"),
                onClick = {
                    val value =
                        if (login) {
                            mapOf("identifier" to identifier.trim(), "password" to password).toJsonElement().toString()
                        } else {
                            password
                        }
                    if (onRespond(value)) {
                        identifier = ""
                        password = ""
                    }
                },
            ) {
                Text(stringResource(if (login) R.string.vault_prompt_save_continue else R.string.chat_send))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (onRespond("")) {
                        identifier = ""
                        password = ""
                    }
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.testTag("vault_prompt_decline"),
            ) { Text(stringResource(R.string.vault_prompt_decline)) }
        },
    )
}
