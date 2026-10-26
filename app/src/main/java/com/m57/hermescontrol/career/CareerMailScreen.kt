package com.m57.hermescontrol.career

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CareerMailScreen(companyId: String) {
    CareerScreen(companyId = companyId, initialTab = 2)
}

@Composable
internal fun CareerMailContent(
    state: CareerCompanyState,
    onRefresh: () -> Unit,
    onCollect: () -> Unit,
    onSetSender: (String, Boolean) -> Unit,
) {
    val detail = state.detail
    val readKeys = rememberCareerMailReadKeys()
    val spacing = LocalSpacing.current
    var sender by rememberSaveable { mutableStateOf("") }
    var selectedMail by remember { mutableStateOf<CareerMailItem?>(null) }
    selectedMail?.let { mail ->
        CareerMailBodySheet(mail, onDismiss = { selectedMail = null }) {
            selectedMail = null
            NavigationController.navigateTo(
                CareerThreadsKey(
                    detail?.company?.id,
                    target =
                        CareerTarget(
                            "companies",
                            detail?.company?.id.orEmpty(),
                            source_refs = listOf(CareerSourceRef("mail", mail.message_id, mail.account_id)),
                        ),
                ),
            )
        }
    }
    HermesScaffold(
        title = { Text("企業メール") },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        isRefreshing = state.loading,
        onRefresh = onRefresh,
        actions = {
            TextButton(onClick = onCollect, enabled = !state.loading, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Text("取得")
            }
        },
    ) {
        if (!state.initialized || (detail == null && state.loading)) {
            LoadingState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("career_mail_list"),
                contentPadding = PaddingValues(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        Text(
                            detail?.company?.title?.removeSuffix("株式会社") ?: "企業を確認できません",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        MailStatusCard(state)
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        Text("表示する送信元", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "ここに登録した送信元のメールを表示します。同じ送信元を複数の企業に登録できます。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            OutlinedTextField(
                                value = sender,
                                onValueChange = { sender = it },
                                modifier = Modifier.weight(1f).testTag("career_mail_sender_input"),
                                label = { Text("送信元メールアドレス") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                singleLine = true,
                            )
                            Button(
                                onClick = {
                                    onSetSender(sender, true)
                                    sender = ""
                                },
                                enabled = sender.contains("@") && !state.loading,
                                modifier = Modifier.testTag("career_mail_sender_add"),
                                shapes = ButtonDefaults.shapes(),
                            ) { Text("追加") }
                        }
                    }
                }
                items(detail?.mail_senders.orEmpty(), key = { it.id }) { rule ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(spacing.md),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                                Text(rule.address, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (rule.enabled) "取得対象" else "停止中",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = { onSetSender(rule.address, !rule.enabled) },
                                enabled = !state.loading,
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(if (rule.enabled) "停止" else "再開")
                            }
                        }
                    }
                }
                item {
                    HorizontalDivider()
                    Text("取得したメール ${detail?.mail?.size ?: 0}件", style = MaterialTheme.typography.titleMedium)
                }
                if (detail?.mail_senders.orEmpty().none { it.enabled }) {
                    item { MailEmptyCard("送信元を登録すると、そのアドレスから届いたメールをここに表示します。") }
                } else if (detail?.mail.isNullOrEmpty()) {
                    item { MailEmptyCard("保存済みのメールはまだありません。「取得」で今すぐ確認できます。") }
                } else {
                    items(detail.mail, key = { it.message_id }) { mail ->
                        CareerMailRow(mail, mail.isUnread(readKeys)) { selectedMail = mail }
                    }
                }
                state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
            }
        }
    }
}

@Composable
private fun MailStatusCard(state: CareerCompanyState) {
    val spacing = LocalSpacing.current
    val status = state.detail?.mail_status
    val enabled = state.connections?.mail?.text("enabled") == "true"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(if (enabled) "メールはプログラムが自動取得" else "メールの自動取得は停止中", style = MaterialTheme.typography.titleSmall)
            Text("AIエージェントは取得せず、保存済みの本文だけを必要時に読みます。", style = MaterialTheme.typography.bodyMedium)
            Text("状態: ${collectionStateLabel(status?.text("state"))}", style = MaterialTheme.typography.bodySmall)
            status?.text("last_success_at")?.let {
                Text("最終取得 ${mailDisplayTime(it)}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = { NavigationController.navigateTo(CareerMailSettingsKey) },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("接続設定を開く")
            }
        }
    }
}

@Composable
private fun MailEmptyCard(message: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            message,
            modifier = Modifier.padding(LocalSpacing.current.md),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun mailDisplayTime(value: String): String =
    runCatching {
        OffsetDateTime
            .parse(value)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
    }.getOrElse { careerDisplayTime(value) }
