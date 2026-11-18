package com.m57.hermescontrol.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.McpCatalogEnvVar
import com.m57.hermescontrol.ui.common.HermesTextField
import com.m57.hermescontrol.ui.common.PrimaryPillButton
import com.m57.hermescontrol.ui.common.SettingsGroup
import com.m57.hermescontrol.ui.common.SettingsRow
import com.m57.hermescontrol.ui.common.SettingsServiceMark

/** Progress shown inside the connect sheet. */
internal data class ConnectSheetStatus(
    val text: String? = null,
    val busy: Boolean = false,
    val error: Boolean = false,
    val cancellable: Boolean = false,
    val done: Boolean = false,
)

/**
 * Bottom sheet for connecting one service (user decision 2026-10-24, ChatGPT style):
 * marks, one title, a short explanation and one "〇〇に進む" button that goes straight
 * to the provider's account choice. Adding never navigates to another page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServiceConnectSheet(
    service: ExternalService,
    plan: ServiceConnectPlan?,
    onProceed: () -> Unit,
    onDismiss: () -> Unit,
    status: ConnectSheetStatus = ConnectSheetStatus(),
    onCancel: () -> Unit = {},
    envFields: List<McpCatalogEnvVar> = emptyList(),
    envValues: Map<String, String> = emptyMap(),
    onEnv: (String, String) -> Unit = { _, _ -> },
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .testTag("connect_sheet"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsServiceMark {
                    Icon(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Hermes", Modifier.size(44.dp))
                }
                Icon(Icons.Default.MoreHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingsServiceMark { ServiceIcon(service.id, 28.dp) }
            }
            Text(
                "${service.name}を接続する",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            SettingsGroup {
                connectNotes(service, plan).forEach { note ->
                    row { SettingsRow(title = note, maxLines = Int.MAX_VALUE) }
                }
            }
            if (plan is ServiceConnectPlan.Install && envFields.isNotEmpty() && !status.busy) {
                envFields.forEach { field ->
                    HermesTextField(
                        value = envValues[field.key].orEmpty(),
                        onValueChange = { onEnv(field.key, it) },
                        label = { Text("${field.label ?: field.key}${if (field.required) "（必須）" else ""}") },
                        supportingText = field.description?.let { { Text(it) } },
                        singleLine = true,
                        visualTransformation =
                            if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions =
                            KeyboardOptions(
                                autoCorrectEnabled = false,
                                keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Text,
                            ),
                        modifier = Modifier.fillMaxWidth().testTag("connect_field_${field.key}"),
                    )
                }
            }
            status.text?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("connect_sheet_status"),
                    )
                }
            }
            val label = proceedLabel(service, plan)
            when {
                status.cancellable -> {
                    TextButton(onClick = onCancel, modifier = Modifier.testTag("connect_sheet_cancel")) { Text("認証を中止") }
                }

                status.done || label == null -> {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("connect_sheet_close")) { Text("閉じる") }
                }

                else -> {
                    val required = envFields.filter { it.required }.all { !envValues[it.key].isNullOrBlank() }
                    PrimaryPillButton(
                        onClick = onProceed,
                        enabled = plan != null && !status.busy && (plan !is ServiceConnectPlan.Install || required),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("connect_sheet_proceed"),
                    ) { Text(label, style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}

/** null means there is nothing to start: the sheet only explains and closes. */
internal fun proceedLabel(
    service: ExternalService,
    plan: ServiceConnectPlan?,
): String? =
    when (plan) {
        null -> "確認中…"
        ServiceConnectPlan.GoogleAccount, ServiceConnectPlan.GoogleWorkspace -> "Googleに進む"
        ServiceConnectPlan.SlackLogin -> "Slackに進む"
        is ServiceConnectPlan.SignIn, is ServiceConnectPlan.Install -> "${service.name}に進む"
        is ServiceConnectPlan.ServerManaged, is ServiceConnectPlan.Unavailable -> null
    }

internal fun connectNotes(
    service: ExternalService,
    plan: ServiceConnectPlan?,
): List<String> =
    listOfNotNull(
        "Hermesが${service.summary}ために使います。",
        when (plan) {
            null -> "接続方法を確認しています。"
            ServiceConnectPlan.GoogleAccount -> "Googleでアカウントを選び、メールの読み取りだけを許可します。接続しただけではメールを取得しません。"
            ServiceConnectPlan.GoogleWorkspace ->
                "Googleでアカウントを選び、カレンダー・ドライブ・ドキュメントの利用を許可します。カレンダーとドライブは同じ接続を使います。"
            ServiceConnectPlan.SlackLogin -> "ブラウザでSlackにログインし、ワークスペースを選んで許可します。"
            is ServiceConnectPlan.SignIn -> "ブラウザで${service.name}のログイン画面が開きます。許可した範囲だけをHermesが使います。"
            is ServiceConnectPlan.Install ->
                if (plan.signInAfter) {
                    "Hermesサーバーに接続先を準備し、そのまま${service.name}のログイン画面を開きます。"
                } else {
                    "Hermesサーバーに接続先を準備します。"
                }
            is ServiceConnectPlan.ServerManaged ->
                "Hermesサーバー上の接続（${plan.server.name}）で使えるようになっています。アプリからのログインはありません。"
            is ServiceConnectPlan.Unavailable -> plan.reason
        },
        "接続情報はHermesサーバーに保存します。接続はあとから解除できます。"
            .takeIf { plan !is ServiceConnectPlan.Unavailable && plan !is ServiceConnectPlan.ServerManaged },
    )
