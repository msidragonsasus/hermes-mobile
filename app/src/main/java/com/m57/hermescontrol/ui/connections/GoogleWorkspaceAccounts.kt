package com.m57.hermescontrol.ui.connections

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.ui.common.SettingsAvatar
import com.m57.hermescontrol.ui.common.SettingsGroup
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.common.SettingsMenuItem
import com.m57.hermescontrol.ui.common.SettingsOverflowMenu
import com.m57.hermescontrol.ui.common.SettingsRow

/** Google accounts used by Calendar/Drive (one shared Workspace MCP connection). */
@Composable
internal fun GoogleWorkspaceAccountsSection(
    state: GoogleWorkspaceUiState,
    onAdd: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: (String) -> Unit,
) {
    var disconnecting by remember { mutableStateOf<GoogleConnectedAccount?>(null) }
    val accounts = state.status?.accounts.orEmpty()
    val error = MaterialTheme.colorScheme.error
    SettingsGroupLabel("接続済みアカウント")
    SettingsGroup(Modifier.testTag("workspace_accounts")) {
        accounts.forEach { account ->
            row {
                val needsReconnect = account.state != "connected"
                SettingsRow(
                    title = account.email,
                    subtitle = if (needsReconnect) "再接続が必要です" else "カレンダー · ドライブ · ドキュメント",
                    subtitleColor = if (needsReconnect) error else Color.Unspecified,
                    modifier = Modifier.testTag("workspace_account_${account.id}"),
                    leading = { SettingsAvatar(account.email.take(2), account.email) },
                    trailing = {
                        SettingsOverflowMenu(
                            listOf(
                                SettingsMenuItem("接続し直す", onClick = onReconnect),
                                SettingsMenuItem("切断") { disconnecting = account },
                            ),
                            contentDescription = "${account.email}の接続操作",
                            enabled = !state.busy,
                            testTag = "workspace_account_menu_${account.id}",
                        )
                    },
                )
            }
        }
        if (accounts.isEmpty()) {
            row { SettingsRow(title = if (state.busy) "接続を確認中…" else "まだアカウントが接続されていません。") }
        }
        row {
            SettingsRow(
                title = if (accounts.isEmpty()) "Googleアカウントをつなげる" else "別のアカウントをつなげる",
                enabled = !state.busy,
                onClick = onAdd,
                modifier = Modifier.testTag("workspace_connect"),
                leading = { Icon(Icons.Default.Add, null, Modifier.size(24.dp)) },
            )
        }
    }
    state.error?.let { Footnote(it) }
    Footnote("カレンダーとドライブは同じGoogle接続を使います。Gmailの接続とは別です。")
    disconnecting?.let { account ->
        AlertDialog(
            onDismissRequest = { disconnecting = null },
            title = { Text("${account.email}を切断しますか？") },
            text = { Text("Hermesはこのアカウントのカレンダー・ドライブを使えなくなります。") },
            confirmButton = {
                TextButton(onClick = {
                    disconnecting = null
                    onDisconnect(account.id)
                }) { Text("切断") }
            },
            dismissButton = { TextButton(onClick = { disconnecting = null }) { Text("キャンセル") } },
        )
    }
}
