package com.m57.hermescontrol.career

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.serialization.json.JsonObject

@Composable
fun CareerMailSettingsScreen() {
    val scope = careerScope()
    val context = LocalContext.current.applicationContext
    key(scope) {
        val local = remember(scope) { CareerLocalStore(context, scope) }
        val model: CareerCompanyViewModel =
            viewModel(
                key = "career-mail-settings:$scope",
                factory =
                    viewModelFactory {
                        initializer { CareerCompanyViewModel(CareerCompanyRepository(scope), local, companyId = null) }
                    },
            )
        val state by model.state.collectAsState()
        val progress by rememberMailProgress(scope)
        val permission =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) model.notifications(true) { CareerNotifications.schedule(context, true) }
            }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, model) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) model.refresh() }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        CareerMailSettingsContent(
            state = state,
            progress = progress,
            onRefresh = model::refresh,
            onCollect = model::collectMail,
            onAutomatic = { enabled ->
                model.configure(
                    enabled,
                    state.connections
                        ?.account_details
                        ?.firstOrNull { it.id == state.connections?.mail?.text("account_id") }
                        ?.id
                        ?: state.connections?.accounts?.singleOrNull(),
                )
            },
            onSelectAccount = { model.configure(false, it) },
            onNotifications = { enabled ->
                if (enabled && Build.VERSION.SDK_INT >= 33) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    model.notifications(enabled) { CareerNotifications.schedule(context, enabled) }
                }
            },
        )
    }
}

@Composable
internal fun CareerMailSettingsContent(
    state: CareerCompanyState,
    progress: CareerMailProgress,
    onRefresh: () -> Unit,
    onCollect: () -> Unit,
    onAutomatic: (Boolean) -> Unit,
    onNotifications: (Boolean) -> Unit,
    onSelectAccount: (String) -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val connections = state.connections
    var choosingAccount by remember { mutableStateOf(false) }
    val selectedId = connections?.mail?.text("account_id") ?: connections?.accounts?.singleOrNull()
    val account = connections?.account_details?.firstOrNull { it.id == selectedId }
    val connected = connections?.mail_status == "ready" && selectedId in connections.accounts
    val enabled = (progress.view?.text("enabled") ?: connections?.mail?.text("enabled")) == "true"
    val status = progress.view?.get("status") as? JsonObject
    val protocolReady = progress.view?.text("version") == "2"
    HermesScaffold(
        title = { Text("企業メールの自動同期") },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        isRefreshing = state.loading,
        onRefresh = onRefresh,
    ) {
        if (!state.initialized) {
            LoadingState()
            return@HermesScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().testTag("career_mail_settings"),
            contentPadding = PaddingValues(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                Card(
                    Modifier.fillMaxWidth().testTag(
                        if (connected) "workspace_connected_card" else "workspace_missing_card",
                    ),
                ) {
                    Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        Text("サーバーのGmail", style = MaterialTheme.typography.titleMedium)
                        if (!connections?.account_details.isNullOrEmpty()) {
                            OutlinedButton(
                                onClick = { choosingAccount = true },
                                enabled = !state.loading,
                                modifier = Modifier.testTag("mail_account_picker"),
                            ) {
                                Text(account?.let { it.label.ifBlank { it.email } } ?: "同期するアカウントを選ぶ")
                            }
                            Text("切り替えると自動同期は停止します。確認してから有効にしてください。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (connected) {
                            if (account?.label?.isNotBlank() == true) {
                                Text(account.email)
                            } else if (account == null) {
                                Text("Google Workspace 接続済み")
                            }
                            Text("登録済み企業の送信元をまとめて同期します。スマホを閉じても継続します。")
                        } else {
                            Text("Googleアカウントを選んで、Gmailの読み取りを許可してください。")
                            Button(
                                onClick = {
                                    NavigationController.navigateTo(
                                        com.m57.hermescontrol.SettingsGoogleConnection,
                                    )
                                },
                            ) {
                                Text("Googleに接続")
                            }
                        }
                        TextButton(
                            onClick = {
                                NavigationController.navigateTo(
                                    com.m57.hermescontrol.SettingsGoogleConnection,
                                )
                            },
                        ) {
                            Text("接続アカウントを管理")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("自動同期", style = MaterialTheme.typography.titleMedium)
                                Text("5分ごとに新着を確認", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = onAutomatic,
                                enabled = !state.loading && (enabled || (connected && protocolReady)),
                                modifier = Modifier.testTag("mail_collection_switch"),
                            )
                        }
                        Button(
                            onClick = onCollect,
                            enabled = enabled && protocolReady && !state.loading,
                            modifier = Modifier.fillMaxWidth().testTag("gmail_collect_now"),
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("全企業を更新") }
                        Text("初回は直近30日を優先し、それ以前のメールも順次取得します。")
                    }
                }
            }
            item { CareerMailSyncStatus(status) }
            progress.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("この端末への新着通知", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "端末への通知は約15分以上の間隔で確認します。初回・過去分の取り込みは通知しません。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = state.notifications,
                            onCheckedChange = onNotifications,
                            enabled = !state.loading,
                            modifier = Modifier.testTag("mail_notification_switch"),
                        )
                    }
                }
            }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            state.connectionError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        }
    }
    if (choosingAccount) {
        AlertDialog(
            onDismissRequest = { choosingAccount = false },
            title = { Text("同期するGmail") },
            text = {
                Column {
                    connections?.account_details.orEmpty().forEach { candidate ->
                        TextButton(
                            onClick = {
                                choosingAccount = false
                                onSelectAccount(candidate.id)
                            },
                            enabled = candidate.state == "connected",
                        ) {
                            Text(
                                candidate.email +
                                    candidate.label
                                        .takeIf { it.isNotBlank() }
                                        ?.let { " · $it" }
                                        .orEmpty() +
                                    if (candidate.state == "connected") "" else " · 再接続が必要です",
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingAccount = false }) { Text("閉じる") } },
        )
    }
}

internal tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
