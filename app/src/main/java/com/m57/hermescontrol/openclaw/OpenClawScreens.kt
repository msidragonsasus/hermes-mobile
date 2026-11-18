package com.m57.hermescontrol.openclaw

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.agents.AgentSelector
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.HermesTextField
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SectionHeader

@Composable
private fun clawViewModel(): OpenClawViewModel {
    val application = LocalContext.current.applicationContext as Application
    val context by AuthManager.contextFlow.collectAsStateWithLifecycle()
    val owner = remember(context?.baseUrl, context?.profileId) { OpenClawViewModel.ownerScope() }
    return remember(application, owner) { OpenClawRuntime.model(application) }
}

@Composable
fun OpenClawChatScreen() {
    val vm = clawViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    var model by rememberSaveable { mutableStateOf(false) }
    var effort by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.ensureConnected() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(state.configured, state.notificationsEnabled) {
        if (state.configured && state.notificationsEnabled && Build.VERSION.SDK_INT >= 33) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    DisposableEffect(lifecycle, state.sessionKey) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    OpenClawRuntime.visibleSession = state.sessionKey
                    vm.ensureConnected()
                } else if (event == Lifecycle.Event.ON_PAUSE) {
                    OpenClawRuntime.visibleSession = null
                }
            }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) OpenClawRuntime.visibleSession = state.sessionKey
        onDispose {
            lifecycle.removeObserver(observer)
            OpenClawRuntime.visibleSession = null
        }
    }
    OpenClawConversation(
        state = state,
        owner = OpenClawViewModel.ownerScope(),
        onSend = vm::send,
        onStop = vm::abort,
        onNew = vm::newSession,
        onSelectSession = vm::selectSession,
        onRefresh = vm::refresh,
        onReconnect = vm::connect,
        onSettings = { NavigationController.navigateTo(OpenClawSettingsKey) },
        onModel = { model = true },
        onEffort = { effort = true },
        title = { AgentSelector(openClaw = true) },
    )
    if (model) {
        ClawModelPicker(state.models, "この会話のモデル", onSelect = {
            model = false
            vm.patchSession("model", it)
        }, onDismiss = { model = false }, inherit = true)
    }
    if (effort) {
        ClawEffortPicker(state.efforts, "この会話の推論強度", onSelect = {
            effort = false
            vm.patchSession("thinkingLevel", it)
        }, onDismiss = { effort = false }, inherit = true)
    }
}

@Composable
fun OpenClawSettingsScreen() {
    val vm = clawViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(vm.savedUrl()) }
    // Credentials are deliberately not put in rememberSaveable/instance-state bundles.
    var token by remember { mutableStateOf("") }
    var titleKey by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var model by rememberSaveable { mutableStateOf(false) }
    var effort by rememberSaveable { mutableStateOf(false) }
    var sessionModel by rememberSaveable { mutableStateOf(false) }
    var sessionEffort by rememberSaveable { mutableStateOf(false) }
    var editConnection by rememberSaveable { mutableStateOf(!state.configured) }
    val spacing = LocalSpacing.current
    LaunchedEffect(vm) { vm.ensureConnected() }
    LaunchedEffect(state.canAdmin) { if (state.canAdmin) vm.loadDefaults() }
    HermesScaffold(
        title = { Text("OpenClawの設定") },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        showShellMenu = false,
    ) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("openclaw_settings"),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (state.configured) {
                item { SectionHeader("この会話") }
                item {
                    ListItem(
                        headlineContent = { Text("モデル（model）") },
                        supportingContent = { Text(state.model ?: "botの既定値") },
                        modifier =
                            Modifier
                                .clickable(
                                    enabled = state.canPatch && !state.loading && !state.busy && !state.saving,
                                ) { sessionModel = true }
                                .testTag("openclaw_session_model"),
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("推論強度（effort）") },
                        supportingContent = { Text(state.effort ?: "botの既定値") },
                        modifier =
                            Modifier
                                .clickable(
                                    enabled = state.canPatch && !state.loading && !state.busy && !state.saving,
                                ) { sessionEffort = true }
                                .testTag("openclaw_session_effort"),
                    )
                }
            }
            item { SectionHeader("接続") }
            if (state.configured) {
                item {
                    ListItem(
                        headlineContent = { Text("Gateway") },
                        supportingContent = { Text(vm.savedUrl()) },
                        trailingContent = { TextButton(onClick = { editConnection = !editConnection }) { Text("変更") } },
                    )
                }
            }
            if (editConnection) {
                item {
                    HermesTextField(
                        url,
                        { url = it },
                        label = { Text("Gateway URL") },
                        placeholder = { Text("wss://サーバー名/") },
                        singleLine = true,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = spacing.md,
                                ).testTag("openclaw_gateway_url"),
                    )
                }
                item {
                    Text(
                        "OpenClawのGateway URLを指定します。ws:// はLAN・Tailscale内で利用できます。サーバーで待受しているポートが必要です。",
                        Modifier.padding(horizontal = spacing.md),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    HermesTextField(
                        token,
                        { token = it },
                        label = { Text(if (vm.hasToken()) "認証情報（保存済み・変更時だけ入力）" else "OpenClawのGatewayトークン／パスワード") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md).testTag("openclaw_token"),
                    )
                }
                item {
                    Button(
                        onClick = {
                            vm.saveConnection(url, token)
                            token = ""
                        },
                        enabled =
                            state.connection != ClawConnection.CONNECTING,
                        modifier = Modifier.padding(horizontal = spacing.md).testTag("openclaw_save_connection"),
                    ) { Text("保存して接続") }
                }
                item {
                    Text(
                        "初回はOpenClaw管理画面で端末「Hermes Mobile」の承認が必要です。既定モデルの変更には管理権限が必要です。",
                        Modifier.padding(spacing.md),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item { SectionHeader("通知と自動命名") }
            item {
                ListItem(
                    headlineContent = { Text("バックグラウンドで更新を受信") },
                    supportingContent = { Text("応答・失敗・実行の確認待ちをスレッドごとに通知します。受信中は接続の通知が表示されます。") },
                    trailingContent = {
                        Switch(
                            checked = state.notificationsEnabled,
                            onCheckedChange = {
                                vm.setNotifications(it)
                                if (it &&
                                    Build.VERSION.SDK_INT >= 33
                                ) {
                                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("会話名を自動で付ける") },
                    supportingContent = { Text("有効な間、最初のメッセージをOpenRouterの無料モデルへ送信します。") },
                    trailingContent = {
                        Switch(checked = state.automaticTitlesEnabled, onCheckedChange = vm::setAutomaticTitles)
                    },
                )
            }
            item {
                HermesTextField(
                    titleKey,
                    { titleKey = it },
                    label = { Text(if (state.titleKeySaved) "OpenRouterキー（端末に保存済み）" else "OpenRouterキー（必要なら個別指定）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md),
                )
            }
            item {
                Text(
                    "会話の最初のメッセージ（最大1,200文字）を無料モデルへ送り、会話名を付けます。既存の接続先に保存されたOpenRouterキーを自動利用します。個別指定したキーは端末内に暗号化して保存します。",
                    Modifier.padding(horizontal = spacing.md),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                TextButton(onClick = {
                    vm.saveTitleKey(titleKey)
                    titleKey = ""
                }, enabled = titleKey.isNotBlank()) {
                    Text("自動命名のキーを保存")
                }
                if (state.titleKeySaved) TextButton(onClick = { vm.saveTitleKey("") }) { Text("自動命名のキーを削除") }
            }
            if (state.loading || state.saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let {
                item {
                    Text(
                        it,
                        Modifier.padding(spacing.md),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            state.notice?.let { item { Text(it, Modifier.padding(spacing.md)) } }
            item { SectionHeader("新しい会話の既定値") }
            item {
                Text(
                    "新しい会話で使います。会話ごとの指定がある場合は、その指定を優先します。",
                    Modifier.padding(horizontal = spacing.md),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("モデル（model）") },
                    supportingContent = {
                        Text(state.defaultModel ?: "OpenClawの既定値")
                    },
                    modifier =
                        Modifier
                            .clickable(enabled = state.canAdmin && state.defaultLoaded && !state.saving) {
                                model =
                                    true
                            }.testTag("openclaw_default_model"),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("推論強度（effort）") },
                    supportingContent = {
                        Text(state.defaultEffort ?: "OpenClawの既定値")
                    },
                    modifier =
                        Modifier
                            .clickable(enabled = state.canAdmin && state.defaultLoaded && !state.saving) {
                                effort =
                                    true
                            }.testTag("openclaw_default_effort"),
                )
            }
            if (!state.canAdmin) {
                item {
                    Text("接続後、管理権限が確認できると既定値を変更できます。会話だけの変更は会話画面のmodel／effortから行えます。", Modifier.padding(spacing.md))
                }
            }
            item {
                TextButton(
                    onClick = vm::connect,
                    enabled =
                        state.configured && !state.saving && state.connection != ClawConnection.CONNECTING,
                    modifier = Modifier.padding(horizontal = spacing.md),
                ) { Text("再接続して設定を確認") }
            }
        }
    }
    if (sessionModel) {
        ClawModelPicker(state.models, "この会話のモデル", onSelect = {
            sessionModel = false
            vm.patchSession("model", it)
        }, onDismiss = { sessionModel = false }, inherit = true)
    }
    if (sessionEffort) {
        ClawEffortPicker(state.efforts, "この会話の推論強度", onSelect = {
            sessionEffort = false
            vm.patchSession("thinkingLevel", it)
        }, onDismiss = { sessionEffort = false }, inherit = true)
    }
    if (model) {
        ClawModelPicker(state.models, "OpenClawの既定モデル", onSelect = {
            model = false
            vm.saveDefaults(model = it)
        }, onDismiss = { model = false })
    }
    if (effort) {
        ClawEffortPicker(state.efforts, "OpenClawの既定の推論強度", onSelect = {
            effort = false
            vm.saveDefaults(effort = it)
        }, onDismiss = { effort = false })
    }
}

@Composable
private fun ClawModelPicker(
    models: List<ClawModel>,
    title: String,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    inherit: Boolean = false,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            HermesTextField(query, { query = it }, placeholder = { Text("モデルを検索") }, singleLine = true)
            LazyColumn {
                if (inherit) item { TextButton(onClick = { onSelect(null) }) { Text("botの既定値を使う") } }
                if (models.isEmpty()) item { Text("利用できるモデルを取得できませんでした。接続と権限を確認してください。") }
                items(
                    models.filter { it.id.contains(query, true) || it.name.contains(query, true) },
                    key = { it.id },
                ) { candidate ->
                    TextButton(onClick = { onSelect(candidate.id) }) { Text(candidate.id) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } })
}

@Composable
private fun ClawEffortPicker(
    efforts: List<String>,
    title: String,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    inherit: Boolean = false,
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            if (inherit) TextButton(onClick = { onSelect(null) }) { Text("botの既定値を使う") }
            if (efforts.isEmpty()) Text("このモデルの対応するeffortを取得できませんでした。サーバーの既定値を使います。")
            efforts.forEach { level -> TextButton(onClick = { onSelect(level) }) { Text(level) } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } })
}
