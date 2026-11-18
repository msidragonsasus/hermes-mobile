package com.m57.hermescontrol.ui.connections

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.career.awaitCareerResult
import com.m57.hermescontrol.career.careerScope
import com.m57.hermescontrol.career.findActivity
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SettingsAvatar
import com.m57.hermescontrol.ui.common.SettingsGroup
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.common.SettingsMenuItem
import com.m57.hermescontrol.ui.common.SettingsOverflowMenu
import com.m57.hermescontrol.ui.common.SettingsRow
import com.m57.hermescontrol.ui.common.SettingsServiceMark

/** Gmail management screen. Also opened from the external services list. */
@Composable
fun GoogleConnectionScreen() {
    val scope = careerScope()
    val model: GoogleConnectionViewModel =
        viewModel(key = "google-connection:$scope") {
            GoogleConnectionViewModel(DashboardGoogleConnectionRepository(scope))
        }
    val activity = LocalContext.current.findActivity()
    val state by model.state.collectAsStateWithLifecycle()
    val resolution =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && activity != null) {
                val code =
                    runCatching {
                        Identity
                            .getAuthorizationClient(
                                activity,
                            ).getAuthorizationResultFromIntent(result.data)
                            .serverAuthCode
                    }.getOrNull()
                model.complete(code)
            } else {
                model.cancel()
            }
        }
    LaunchedEffect(model) { model.refresh() }
    DisposableEffect(model) { onDispose { model.cancel() } }
    HermesScaffold(
        title = {},
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        isRefreshing = state.busy,
        onRefresh = model::refresh,
    ) {
        GoogleConnectionContent(
            state = state,
            onRefresh = model::refresh,
            onRename = model::rename,
            onDisconnect = model::disconnect,
            onConnect = { accountId ->
                if (activity != null) {
                    model.begin(accountId) { flow ->
                        val authorization =
                            Identity
                                .getAuthorizationClient(activity)
                                .authorize(
                                    AuthorizationRequest
                                        .builder()
                                        .setRequestedScopes(listOf(Scope(flow.scope)))
                                        .setOptOutIncludingGrantedScopes(true)
                                        .requestOfflineAccess(flow.client_id)
                                        .setPrompt(
                                            AuthorizationRequest.Prompt.CONSENT or
                                                AuthorizationRequest.Prompt.SELECT_ACCOUNT,
                                        ).build(),
                                ).awaitCareerResult()
                        if (authorization.hasResolution()) {
                            resolution.launch(
                                IntentSenderRequest.Builder(requireNotNull(authorization.pendingIntent)).build(),
                            )
                            null
                        } else {
                            requireNotNull(authorization.serverAuthCode)
                        }
                    }
                }
            },
        )
    }
}

@Composable
internal fun GoogleConnectionContent(
    state: GoogleConnectionUiState,
    onRefresh: () -> Unit,
    onConnect: (String?) -> Unit,
    onRename: (String, String) -> Unit,
    onDisconnect: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var editing by remember { mutableStateOf<GoogleConnectedAccount?>(null) }
    var disconnecting by remember { mutableStateOf<GoogleConnectedAccount?>(null) }
    var label by remember { mutableStateOf("") }
    var connectSheet by remember { mutableStateOf(false) }
    val status = state.status
    val accounts = status?.accounts.orEmpty()
    val error = MaterialTheme.colorScheme.error
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SettingsGroup {
            row {
                SettingsRow(
                    title = "Gmail",
                    subtitle = "受信トレイのメールを探して読む",
                    leading = { SettingsServiceMark { Icon(Icons.Default.Email, null, Modifier.size(28.dp)) } },
                    trailing = {
                        SettingsOverflowMenu(
                            listOf(
                                SettingsMenuItem("再読込", enabled = !state.busy, onClick = onRefresh),
                            ),
                            contentDescription = "Gmailの操作",
                        )
                    },
                )
            }
        }
        state.error?.let {
            Text(
                it,
                color = error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = spacing.lg, top = spacing.sm).testTag("google_connection_error"),
            )
        }

        SettingsGroupLabel("接続済みアカウント")
        SettingsGroup(Modifier.testTag("google_connection_status")) {
            accounts.forEach { account ->
                row {
                    val needsReconnect = account.state != "connected"
                    SettingsRow(
                        title = account.email,
                        subtitle =
                            listOfNotNull(
                                account.label.ifBlank { "用途名なし" },
                                "再接続が必要です".takeIf { needsReconnect },
                            ).joinToString(" · "),
                        subtitleColor = if (needsReconnect) error else androidx.compose.ui.graphics.Color.Unspecified,
                        modifier = Modifier.testTag("google_account_${account.id}"),
                        leading = { SettingsAvatar(account.label.ifBlank { account.email }.take(2), account.email) },
                        trailing = {
                            SettingsOverflowMenu(
                                listOf(
                                    SettingsMenuItem("用途名を変更") {
                                        editing = account
                                        label = account.label
                                    },
                                    SettingsMenuItem("接続し直す") { onConnect(account.id) },
                                    SettingsMenuItem("切断") { disconnecting = account },
                                ),
                                contentDescription = "${account.email}の接続操作",
                                enabled = !state.busy,
                                testTag = "google_account_menu_${account.id}",
                            )
                        },
                    )
                }
            }
            if (accounts.isEmpty()) {
                row {
                    SettingsRow(
                        title =
                            when (status?.state) {
                                "connected" -> "接続済み · ${status.email.orEmpty()}"
                                "disconnected" -> "まだアカウントが接続されていません。"
                                "setup_required" -> "Hermes側でGoogle連携の初期設定が必要です。"
                                "reauth_required" -> "以前の接続を確認できません。接続し直してください。"
                                else -> if (state.busy) "接続を確認中…" else "接続状態を確認できません。"
                            },
                    )
                }
            }
            row {
                val canAdd = !state.busy && (status?.can_add == true || status?.state == "disconnected")
                SettingsRow(
                    title = if (state.busy) "接続を確認中…" else "別のアカウントをつなげる",
                    enabled = canAdd,
                    onClick = { connectSheet = true },
                    modifier = Modifier.testTag("google_connect"),
                    leading = { Icon(Icons.Default.Add, null, Modifier.size(24.dp)) },
                )
            }
        }
        if (status != null && !status.multi_account && status.state == "connected") {
            Footnote("複数アカウントを使うには、Hermesサーバーの更新が必要です。")
        }

        Spacer(Modifier.height(spacing.xl))
        SettingsGroup {
            // The app only ever requests gmail.readonly (GoogleConnectionViewModel.GMAIL_READONLY).
            row { SettingsRow(title = "権限", value = "メールの読み取りのみ") }
        }

        SettingsGroupLabel("この接続を使っている機能")
        SettingsGroup {
            row {
                SettingsRow(
                    title = "就活 · 企業メールの自動同期",
                    subtitle = "同期するアカウントはメール設定で選べます",
                    leading = { Icon(Icons.Default.Work, null, Modifier.size(24.dp)) },
                    chevron = true,
                    onClick = { NavigationController.navigateTo(com.m57.hermescontrol.career.CareerMailSettingsKey) },
                )
            }
        }
        Footnote(
            "接続情報はHermesサーバーに保存します。接続しただけではメールを取得しません。" +
                "Gmailの許可だけではGoogle カレンダー・Driveにはアクセスできません。",
        )
        Spacer(Modifier.height(spacing.xl))
    }
    if (connectSheet) {
        // Explain once in a sheet, then go straight to Google's account picker.
        ServiceConnectSheet(
            service = requireNotNull(externalService("gmail")),
            plan = ServiceConnectPlan.GoogleAccount,
            onProceed = {
                connectSheet = false
                onConnect(null)
            },
            onDismiss = { connectSheet = false },
        )
    }
    editing?.let { account ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("用途名を設定") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(account.email)
                    OutlinedTextField(
                        value = label,
                        onValueChange = { if (it.length <= 40) label = it },
                        label = { Text("例：就活、個人、学校") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.testTag("google_account_label"),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = null
                    onRename(account.id, label)
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("キャンセル") } },
        )
    }
    disconnecting?.let { account ->
        AlertDialog(
            onDismissRequest = { disconnecting = null },
            title = { Text("このアカウントを切断しますか？") },
            text = { Text("${account.email}\nこのアカウントでの取得ができなくなります。保存済みメールとGoogle側の許可は保持します。") },
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

@Composable
internal fun Footnote(text: String) {
    com.m57.hermescontrol.ui.common.SettingsFootnote(text)
}
