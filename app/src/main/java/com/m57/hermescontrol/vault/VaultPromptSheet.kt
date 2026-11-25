@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.m57.hermescontrol.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.ws.VaultPromptKind
import com.m57.hermescontrol.data.ws.VaultPromptRequest
import com.m57.hermescontrol.data.ws.toJsonElement
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.components.ConversationActionCard
import com.m57.hermescontrol.ui.common.PrimaryPillButton

@Composable
internal fun VaultPromptCard(
    prompt: VaultPromptUi,
    onOpen: () -> Unit,
    onDecline: () -> Unit,
) {
    val spacing = LocalSpacing.current
    ConversationActionCard(Modifier.testTag("vault_prompt_card")) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VaultKeyTile()
                VaultPromptHeading(prompt.request)
            }
            PrimaryPillButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vault_prompt_open"),
            ) { Text(stringResource(R.string.vault_prompt_next)) }
            TextButton(
                onClick = onDecline,
                modifier = Modifier.heightIn(min = 48.dp).testTag("vault_prompt_decline"),
            ) { Text(stringResource(R.string.vault_prompt_decline)) }
        }
    }
}

@Composable
internal fun VaultPromptSheet(
    prompt: VaultPromptUi,
    onDismiss: () -> Unit,
    onRespond: (String) -> Boolean,
) {
    val request = prompt.request
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    val login = request.kind == VaultPromptKind.LOGIN
    val valid =
        password.isNotEmpty() && (!login || (identifier.isNotBlank() && request.origin?.let(::vaultOrigin) != null))
    val spacing = LocalSpacing.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.lg, vertical = spacing.md)
                    .testTag("vault_prompt_sheet"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            VaultKeyTile()
            VaultPromptHeading(request, centered = true)
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(spacing.sm)) {
                    if (login) {
                        LoginField(
                            identifier,
                            { identifier = it },
                            R.string.vault_identifier,
                            "vault_prompt_identifier",
                            false,
                            contentType = ContentType.Username,
                        )
                        HorizontalDivider(Modifier.padding(vertical = spacing.sm))
                    }
                    LoginField(
                        password,
                        { password = it },
                        if (request.kind ==
                            VaultPromptKind.CODE
                        ) {
                            R.string.vault_prompt_code
                        } else {
                            R.string.vault_password
                        },
                        "vault_prompt_password",
                        false,
                        KeyboardType.Password,
                        contentType = if (login) ContentType.Password else null,
                        revealed = revealed,
                        trailingIcon =
                            if (login) {
                                { VaultPasswordVisibility(revealed) { revealed = !revealed } }
                            } else {
                                null
                            },
                    )
                }
            }
            if (prompt.sendFailed) {
                Text(stringResource(R.string.vault_prompt_send_failed), color = MaterialTheme.colorScheme.error)
            }
            PrimaryPillButton(
                enabled = valid,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vault_prompt_submit"),
                onClick = {
                    if (onRespond(vaultPromptAnswer(request.kind, identifier, password))) {
                        identifier = ""
                        password = ""
                        revealed = false
                        onDismiss()
                    }
                },
            ) { Text(stringResource(if (login) R.string.vault_save else R.string.chat_send)) }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag("vault_prompt_cancel"),
            ) { Text(stringResource(R.string.vault_cancel)) }
        }
    }
}

internal fun vaultPromptAnswer(
    kind: VaultPromptKind,
    identifier: String,
    password: String,
): String =
    if (kind == VaultPromptKind.LOGIN) {
        mapOf("identifier" to identifier.trim(), "password" to password).toJsonElement().toString()
    } else {
        password
    }

@Composable
private fun VaultPasswordVisibility(
    revealed: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
        Icon(
            if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            stringResource(if (revealed) R.string.vault_prompt_hide_password else R.string.vault_prompt_show_password),
        )
    }
}

@Composable
private fun VaultKeyTile() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Key, stringResource(R.string.vault_password))
        }
    }
}

@Composable
private fun VaultPromptHeading(
    request: VaultPromptRequest,
    centered: Boolean = false,
) {
    val title =
        when (request.kind) {
            VaultPromptKind.LOGIN -> R.string.vault_prompt_login_title
            VaultPromptKind.CODE -> R.string.vault_prompt_code_title
            VaultPromptKind.UNLOCK -> R.string.vault_prompt_unlock_title
        }
    val site =
        if (request.kind == VaultPromptKind.LOGIN) {
            request.origin?.let(::vaultOrigin)?.substringAfter("://")
        } else {
            request.site
        }
    Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        val textAlign = if (centered) TextAlign.Center else TextAlign.Start
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = textAlign)
        if (!site.isNullOrBlank()) {
            Text(
                stringResource(R.string.vault_prompt_site_for, site),
                modifier = Modifier.testTag("vault_prompt_site"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = textAlign,
            )
        }
    }
}
