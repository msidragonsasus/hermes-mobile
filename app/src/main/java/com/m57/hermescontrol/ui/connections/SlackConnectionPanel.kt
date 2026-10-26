package com.m57.hermescontrol.ui.connections

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.ui.common.SettingsAvatar
import com.m57.hermescontrol.ui.common.SettingsGroupScope
import com.m57.hermescontrol.ui.common.SettingsRow

/**
 * Rows of the Slack "接続済みアカウント" group. The status API does not return the
 * workspace or user name, so the authorized row names the service only.
 */
internal fun SettingsGroupScope.slackAccountRows(
    state: SlackConnectionUiState,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
) {
    val status = state.status?.state
    when {
        state.loading -> {
            row { SettingsRow(title = "Slackの接続を確認中…") }
        }

        state.connecting -> {
            row {
                SettingsRow(
                    title = "Slackでログイン・ワークスペース選択・許可を進めてください。",
                    leading = { CircularProgressIndicator(Modifier.size(20.dp)) },
                )
            }
            row {
                SettingsRow(
                    title = "認証を中止",
                    titleColor = MaterialTheme.colorScheme.primary,
                    onClick = onCancel,
                )
            }
        }

        status == "setup_required" -> {
            row { SettingsRow(title = "管理者によるSlack連携の初回設定が必要です。") }
        }

        status == "manual_connection" -> {
            row { SettingsRow(title = "既存の接続先は個別設定です。", subtitle = "下の接続先から管理できます。") }
        }

        status == "authorized" -> {
            row {
                SettingsRow(
                    title = "Slack",
                    subtitle = if (state.status.enabled) "認証済み" else "認証済み · 接続先の利用は停止中",
                    leading = { SettingsAvatar("S", "slack") },
                )
            }
        }

        status == "ready" -> {
            row { SettingsRow(title = "まだ接続されていません", subtitle = "Slackにログインして、ワークスペースを選んで許可します。") }
        }
    }
    state.error?.let { message ->
        row {
            SettingsRow(
                title = message,
                titleColor = MaterialTheme.colorScheme.error,
                trailing = { TextButton(onClick = onRefresh) { Text("再読込") } },
            )
        }
    }
    state.message?.let { message -> row { SettingsRow(title = message) } }
    if (status in setOf("ready", "authorized") && !state.connecting) {
        row {
            val reconnect = status == "authorized"
            SettingsRow(
                title = if (reconnect) "Slackに接続し直す" else "Slackに接続",
                enabled = !state.loading,
                onClick = onConnect,
                modifier = Modifier.testTag("slack_login"),
                leading = {
                    Icon(if (reconnect) Icons.Default.Refresh else Icons.Default.Add, null, Modifier.size(24.dp))
                },
            )
        }
    }
}
