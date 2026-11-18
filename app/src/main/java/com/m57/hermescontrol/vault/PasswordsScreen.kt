@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.m57.hermescontrol.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.config.resolvedBaseUrl
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.HermesTextField
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SettingsFootnote
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.common.SettingsListItem
import com.m57.hermescontrol.ui.common.SettingsMenuItem
import com.m57.hermescontrol.ui.common.SettingsRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun PasswordsScreen() {
    val scope by HermesWsClient.connectionScope.collectAsStateWithLifecycle()
    val connection by HermesWsClient.connectionStatus.collectAsStateWithLifecycle()
    val settings by AuthManager.serverStore.stateFlow.collectAsStateWithLifecycle()
    val profile by AuthManager.activeProfileId.collectAsStateWithLifecycle()
    // A new owner/connection disposes forms, jobs and metadata, including late replies.
    key(scope, settings.selectedProfileId, settings.resolvedBaseUrl, profile, connection) {
        val owner = scope?.takeIf { connection == ConnectionStatus.CONNECTED && HermesWsClient.matchesCurrentScope(it) }
        if (owner == null) {
            VaultScaffold {
                ErrorState(stringResource(R.string.vault_disconnected))
            }
        } else {
            val repository = remember(owner) { VaultRepository(owner) }
            VaultContent(repository, owner.serverProfileId ?: "default")
        }
    }
}

@Composable
private fun VaultScaffold(
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    HermesScaffold(
        title = { Text(stringResource(R.string.vault_title)) },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        onRefresh = onRefresh,
        isRefreshing = isRefreshing,
        actions = actions,
    ) { content() }
}

@Composable
internal fun VaultContent(
    repository: VaultRepository,
    profile: String,
) {
    val spacing = LocalSpacing.current
    val jobs = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<VaultItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var add by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<VaultItem?>(null) }
    var busy by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<Int?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        add = false
        deleting = null
        formError = null
    }
    LaunchedEffect(repository, refresh) {
        loading = true
        error = null
        try {
            items = repository.list()
            loaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error =
                if ((failure as? HermesWsClient.HermesRpcException)?.code == -32601) {
                    R.string.vault_unsupported
                } else {
                    R.string.vault_load_failed
                }
        } finally {
            loading = false
        }
    }

    val deleteLabel = stringResource(R.string.vault_delete)
    VaultScaffold(onRefresh = { if (!loading && !busy) refresh++ }, isRefreshing = loading) {
        Column(Modifier.fillMaxSize()) {
            notice?.let {
                SettingsFootnote(
                    stringResource(it),
                    Modifier.padding(horizontal = 16.dp).testTag("vault_notice"),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            when {
                loading && !loaded -> {
                    LoadingState()
                }

                error != null -> {
                    ErrorState(stringResource(error!!), onRetry = { refresh++ })
                }

                items.isEmpty() -> {
                    EmptyState(
                        title = stringResource(R.string.vault_empty),
                        subtitle = stringResource(R.string.vault_empty_description),
                        icon = Icons.Default.Key,
                        actionLabel = stringResource(R.string.vault_add),
                        onAction = {
                            formError = null
                            add = true
                        },
                    )
                }

                else -> {
                    SearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = stringResource(R.string.vault_search),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = spacing.sm),
                    )
                    val visible =
                        items.filter {
                            listOfNotNull(it.label, it.origin, it.identifier).any { value ->
                                value.contains(query.trim(), true)
                            }
                        }
                    if (visible.isEmpty()) {
                        EmptyState(title = stringResource(R.string.vault_no_results))
                    } else {
                        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                            item("logins_label") {
                                SettingsGroupLabel(stringResource(R.string.settings_group_logins, visible.size))
                            }
                            itemsIndexed(visible, key = { _, item -> "${item.backend}:${item.id}" }) { index, item ->
                                SettingsListItem(first = index == 0, last = false) {
                                    SettingsRow(
                                        title = item.label,
                                        subtitle =
                                            listOfNotNull(
                                                item.identifier,
                                                item.origin,
                                                stringResource(
                                                    R.string.vault_otp_badge,
                                                ).takeIf { item.has_otp == true },
                                                item.backend.takeIf { it != "local" },
                                            ).joinToString(" · "),
                                        leading = {
                                            Icon(Icons.Default.Key, contentDescription = null, Modifier.size(24.dp))
                                        },
                                        actions =
                                            if (item.backend == "local") {
                                                listOf(SettingsMenuItem(deleteLabel) { deleting = item })
                                            } else {
                                                emptyList()
                                            },
                                        actionsEnabled = !busy,
                                    )
                                }
                            }
                            item("logins_add") {
                                SettingsListItem(first = false, last = true) {
                                    SettingsRow(
                                        title = stringResource(R.string.vault_add),
                                        leading = {
                                            Icon(
                                                Icons.Default.Add,
                                                contentDescription = null,
                                                Modifier.size(24.dp),
                                            )
                                        },
                                        enabled = loaded && error == null && !busy,
                                        onClick = {
                                            formError = null
                                            add = true
                                        },
                                    )
                                }
                            }
                            item("logins_footnote") {
                                SettingsFootnote(
                                    stringResource(
                                        R.string.vault_footnote,
                                        profile,
                                        stringResource(R.string.vault_description),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (add) {
        AddLoginDialog(
            busy = busy,
            error = formError,
            onDismiss = {
                add = false
                formError = null
            },
            onSave = { label, origin, identifier, password, otp ->
                busy = true
                formError = null
                notice = null
                jobs.launch {
                    try {
                        repository.addLogin(label, origin, identifier, password, otp)
                        add = false
                        notice = R.string.vault_saved
                        refresh++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        // No exception text: a backend/transport error might contain a secret.
                        formError =
                            if ((failure as? HermesWsClient.HermesRpcException)?.code == 5095) {
                                R.string.vault_save_rejected
                            } else {
                                R.string.vault_save_failed
                            }
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text(stringResource(R.string.vault_delete_title)) },
            text = { Text(stringResource(R.string.vault_delete_message, item.label, item.origin.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    busy = true
                    notice = null
                    jobs.launch {
                        try {
                            repository.remove(item)
                            notice = R.string.vault_deleted
                            refresh++
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            notice = R.string.vault_delete_failed
                        } finally {
                            busy = false
                            deleting = null
                        }
                    }
                }, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.vault_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.vault_cancel))
                }
            },
        )
    }
}

@Composable
internal fun AddLoginDialog(
    busy: Boolean,
    error: Int?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit,
) {
    // Never rememberSaveable: passwords must not enter Bundles, navigation, or process restoration.
    var label by remember { mutableStateOf("") }
    var origin by remember { mutableStateOf("") }
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    val valid = label.isNotBlank() && vaultOrigin(origin) != null && identifier.isNotBlank() && password.isNotEmpty()
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.vault_add)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
            ) {
                LoginField(label, { label = it }, R.string.vault_label, "vault_label", busy)
                LoginField(origin, { origin = it }, R.string.vault_origin, "vault_origin", busy, KeyboardType.Uri)
                if (origin.isNotEmpty() && vaultOrigin(origin) == null) {
                    Text(stringResource(R.string.vault_invalid_origin), color = MaterialTheme.colorScheme.error)
                }
                LoginField(identifier, {
                    identifier = it
                }, R.string.vault_identifier, "vault_identifier", busy, contentType = ContentType.Username)
                LoginField(
                    password,
                    { password = it },
                    R.string.vault_password,
                    "vault_password",
                    busy,
                    KeyboardType.Password,
                    contentType = ContentType.Password,
                )
                LoginField(otp, { otp = it }, R.string.vault_otp, "vault_otp", busy, KeyboardType.Password)
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            FilledTonalButton(
                enabled = valid && !busy,
                modifier = Modifier.testTag("vault_save"),
                shapes = ButtonDefaults.shapes(),
                onClick = {
                    onSave(label, origin, identifier, password, otp)
                    password = ""
                    otp = ""
                },
            ) { Text(stringResource(if (busy) R.string.vault_saving else R.string.vault_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.vault_cancel))
            }
        },
    )
}

@Composable
internal fun LoginField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    tag: String,
    busy: Boolean,
    keyboard: KeyboardType = KeyboardType.Text,
    contentType: ContentType? = null,
) {
    HermesTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        visualTransformation =
            if (keyboard ==
                KeyboardType.Password
            ) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentType?.let { this.contentType = it } },
    )
}
