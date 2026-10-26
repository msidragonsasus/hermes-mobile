package com.m57.hermescontrol.ui.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.m57.hermescontrol.data.model.McpCatalogEntry
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.CompactListRow
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.connections.connectionHost
import com.m57.hermescontrol.ui.connections.externalServices
import com.m57.hermescontrol.ui.connections.matches

/** One catalog/install form for both the service directory and advanced MCP management. */
@Composable
internal fun McpCatalogContent(
    state: McpServersUiState,
    entries: List<McpCatalogEntry>,
    onRetry: () -> Unit,
    onBeginSetup: (McpCatalogEntry) -> Unit,
    onInstall: (McpCatalogEntry) -> Unit,
    onUpdateEnv: (String, String) -> Unit,
) {
    when {
        state.catalogLoading -> {
            CircularProgressIndicator(modifier = Modifier.testTag("connection_catalog_loading"))
        }

        state.catalogError != null -> {
            ErrorState(message = state.catalogError, onRetry = onRetry)
        }

        entries.isEmpty() -> {
            Text(
                "利用できる接続方法がありません。手動設定を開けます。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        else -> {
            Column {
                entries.forEach { entry ->
                    McpCatalogEntryRow(entry, state, onBeginSetup, onInstall, onUpdateEnv)
                }
            }
        }
    }
}

@Composable
private fun McpCatalogEntryRow(
    entry: McpCatalogEntry,
    state: McpServersUiState,
    onBeginSetup: (McpCatalogEntry) -> Unit,
    onInstall: (McpCatalogEntry) -> Unit,
    onUpdateEnv: (String, String) -> Unit,
) {
    val spacing = LocalSpacing.current
    val selected = state.catalogSetupEntry == entry.name
    val values = if (selected) state.catalogInstallEnv else emptyMap()
    val fields = entry.env.orEmpty()
    val installed = entry.installed || state.servers.any { it.name == entry.name }
    val uncertain = entry.name in state.unconfirmedCatalogEntries
    val preparing = state.installingCatalogEntry == entry.name
    val ready = fields.none { it.required && values[it.key].isNullOrBlank() }
    var showDetails by remember(entry.name) { mutableStateOf(false) }
    CompactListRow(
        modifier = Modifier.fillMaxWidth().testTag("connection_method_${entry.name}"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall)
            entry.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val services = externalServices.filter { it.matches(entry) }
            if (services.size > 1) {
                val names = services.joinToString("・") { it.name }
                Text("$names で共用する接続先です。変更は各サービスに影響します。", style = MaterialTheme.typography.bodySmall)
            }
            if (selected && !installed) {
                fields.forEach { field ->
                    TextField(
                        value = values[field.key].orEmpty(),
                        onValueChange = { onUpdateEnv(field.key, it) },
                        label = { Text("${field.label ?: field.key}${if (field.required) "（必須）" else ""}") },
                        supportingText = field.description?.let { { Text(it) } },
                        enabled = state.installingCatalogEntry == null && !uncertain,
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        visualTransformation =
                            if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions =
                            KeyboardOptions(
                                autoCorrectEnabled = false,
                                keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Text,
                            ),
                        modifier = Modifier.fillMaxWidth().testTag("connection_field_${field.key}"),
                    )
                }
            }
            TextButton(
                onClick = {
                    if (fields.isNotEmpty() && !selected) onBeginSetup(entry) else onInstall(entry)
                },
                enabled =
                    !state.isLoading && !installed && !uncertain &&
                        state.installingCatalogEntry == null && (!selected || ready),
                modifier = Modifier.testTag("prepare_connection_${entry.name}"),
            ) {
                Text(
                    when {
                        installed -> "接続先は登録済み"
                        preparing -> "接続先を準備中…"
                        uncertain -> "準備状況の確認が必要"
                        fields.isNotEmpty() && !selected -> "必要な設定を入力"
                        else -> "接続先を準備"
                    },
                )
            }
            if (installed) Text("登録済みの接続先から、認証・有効化・接続テストを開けます。", style = MaterialTheme.typography.bodySmall)
            if (uncertain && !preparing &&
                !installed
            ) {
                Text("再読込して状態を確認してください。", style = MaterialTheme.typography.bodySmall)
            }
            androidx.compose.material3.TextButton(onClick = { showDetails = !showDetails }) {
                Text(if (showDetails) "接続方法の説明を閉じる" else "接続方法の説明")
            }
            if (showDetails) {
                connectionHost(entry.source)?.let { Text("提供元: $it") }
                entry.postInstall?.takeIf { it.isNotBlank() }?.let { Text(it) }
                Text("準備後にアカウント認証や提供元でのアプリ登録が必要な場合があります。")
            }
        }
    }
}
