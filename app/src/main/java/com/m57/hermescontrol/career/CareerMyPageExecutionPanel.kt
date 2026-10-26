package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing

@Composable
internal fun CareerMyPageExecutionPanel() {
    val context by AuthManager.contextFlow.collectAsState()
    val profile by AuthManager.activeProfileId.collectAsState()
    val scope = remember(context, profile) { careerScope() }
    val model: CareerMyPageExecutionViewModel =
        viewModel(
            key = "career-mypage-execution:$scope",
            factory =
                viewModelFactory {
                    initializer { CareerMyPageExecutionViewModel(DashboardCareerMyPageExecutionRepository(scope)) }
                },
        )
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.refresh() }
    CareerMyPageExecutionContent(state, model::refresh, model::save, model::retry)
}

@Composable
internal fun CareerMyPageExecutionContent(
    state: CareerMyPageExecutionUiState,
    onRefresh: () -> Unit,
    onSave: (CareerMyPageExecutionSettings) -> Unit,
    onRetry: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val colors = LocalHermesStatusColors.current
    var draft by remember(state.settings) { mutableStateOf(state.settings ?: CareerMyPageExecutionSettings()) }
    var runBudget by remember(state.settings) { mutableStateOf(draft.budget.per_run_usd.toString()) }
    var dayBudget by remember(state.settings) { mutableStateOf(draft.budget.per_day_usd.toString()) }
    val enabled = state.supported && state.settings != null && !state.loading && !state.saving && state.pending == null
    Column(Modifier.testTag("career_mypage_execution"), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        HorizontalDivider()
        Text("マイページ確認", style = MaterialTheme.typography.titleMedium)
        Text("マイページの案内を整理するAIを選びます。モデルは自動選択しません。", style = MaterialTheme.typography.bodySmall)
        Text("外部モデルを選ぶと、氏名や個別案内を含む本文をその接続先へ送ります。ID・パスワード・Cookieは送りません。", style = MaterialTheme.typography.bodySmall)
        if (state.loading) Text("設定・接続を確認中…")
        myPageConnectionLines(state.capabilities).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.supported && state.settings == null && !state.loading) Text("接続して設定を読み込んでください。")
        MyPageModelControl("本文の整理", draft.analysis, state.capabilities, enabled) { draft = draft.copy(analysis = it) }
        Row {
            Checkbox(
                checked = draft.use_analysis_for_learning,
                onCheckedChange = { draft = draft.copy(use_analysis_for_learning = it) },
                enabled = enabled,
            )
            Text("手順学習も同じモデルを使う", modifier = Modifier.padding(top = spacing.sm))
        }
        if (!draft.use_analysis_for_learning) {
            MyPageModelControl(
                "手順学習",
                draft.learning,
                state.capabilities,
                enabled,
            ) { draft = draft.copy(learning = it) }
        }
        TextButton(
            onClick = {
                com.m57.hermescontrol.NavigationController
                    .navigateTo(com.m57.hermescontrol.ProvidersScreen)
            },
            shapes = ButtonDefaults.shapes(),
        ) {
            Text("Hermesのモデル接続を管理")
        }
        Row {
            Checkbox(
                checked = draft.paid_enabled,
                onCheckedChange = { draft = draft.copy(paid_enabled = it) },
                enabled = enabled,
                modifier = Modifier.testTag("career_mypage_paid_enabled"),
            )
            Text("有料APIの利用を有効にする", modifier = Modifier.padding(top = spacing.sm))
        }
        if (draft.paid_enabled) {
            OutlinedTextField(
                value = runBudget,
                onValueChange = { runBudget = it },
                label = { Text("1実行の料金上限（USD）") },
                enabled = enabled,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = dayBudget,
                onValueChange = { dayBudget = it },
                label = { Text("1日の料金上限（USD）") },
                enabled = enabled,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text("料金上限0では課金しません。単価未確認の接続は選択できません。上限や認証の問題で別モデルには切り替えません。", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Button(
                onClick = {
                    onSave(
                        draft.copy(
                            budget =
                                CareerMyPageBudget(
                                    runBudget.toDoubleOrNull() ?: -1.0,
                                    dayBudget.toDoubleOrNull() ?: -1.0,
                                ),
                        ),
                    )
                },
                enabled = enabled,
                modifier = Modifier.testTag("career_mypage_execution_save"),
                shapes = ButtonDefaults.shapes(),
            ) { Text("設定を保存") }
            TextButton(
                onClick = onRefresh,
                enabled = !state.loading && !state.saving,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("設定・候補を再読込")
            }
            if (state.pending != null) {
                TextButton(onClick = onRetry, enabled = !state.saving, shapes = ButtonDefaults.shapes()) {
                    Text("同じ保存を再確認")
                }
            }
        }
        state.error?.let { Text(it, color = colors.error) }
        state.message?.let { Text(it, color = colors.success) }
        Text("変更は次に受け付ける実行から反映されます。実行中のモデルは変わりません。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MyPageModelControl(
    label: String,
    selected: CareerExecution?,
    capabilities: CareerMyPageCapabilities?,
    enabled: Boolean,
    onSelect: (CareerExecution) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val option = capabilities?.models?.firstOrNull { it.provider == selected?.provider && it.id == selected.model }
    Text(label, style = MaterialTheme.typography.titleSmall)
    TextButton(
        onClick = { open = true },
        enabled = enabled,
        modifier = Modifier.testTag("career_mypage_${label}_model"),
        shapes = ButtonDefaults.shapes(),
    ) {
        Text(selected?.let { "${it.model} · ${it.provider} · ${it.reasoning_effort}" } ?: "モデルを選択（未設定）")
    }
    option?.let {
        Text(
            "認証：${myPageAuthLabel(
                it.auth_kind,
            )} · 接続：${myPageConnectionLabel(it.connection_state)} · ${myPageBillingLabel(it.billing)}",
        )
        it.endpoint?.let { endpoint -> Text("接続先：$endpoint", style = MaterialTheme.typography.bodySmall) }
        if (!it.selectable) Text("現在は利用できません：${myPageModelReason(it.reason)}")
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("$label のモデル") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
                ) {
                    if (capabilities?.models.isNullOrEmpty()) Text("利用できるモデルがありません。Hermesのモデル接続を確認してください。")
                    capabilities?.models.orEmpty().forEach { model ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(LocalSpacing.current.sm)) {
                                Text("${model.label} · ${model.provider}")
                                Text(
                                    "${myPageAuthLabel(
                                        model.auth_kind,
                                    )} · ${myPageConnectionLabel(
                                        model.connection_state,
                                    )} · ${myPageBillingLabel(model.billing)}",
                                )
                                if (!model.selectable || !model.private_content_allowed) {
                                    Text(myPageModelReason(model.reason), style = MaterialTheme.typography.bodySmall)
                                }
                                FlowRow {
                                    model.reasoning_efforts.forEach { effort ->
                                        TextButton(
                                            onClick = {
                                                onSelect(CareerExecution(model.provider, model.id, effort))
                                                open =
                                                    false
                                            },
                                            enabled =
                                                model.selectable && model.private_content_allowed &&
                                                    model.connection_state in setOf("available", "connected"),
                                            shapes = ButtonDefaults.shapes(),
                                        ) { Text(if (effort == "default") "標準" else effort) }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { open = false },
                    shapes = ButtonDefaults.shapes(),
                ) { Text("閉じる") }
            },
        )
    }
}

private fun myPageAuthLabel(value: String): String =
    when (value) {
        "oauth", "existing_auth", "codex_oauth" -> "既存ログイン"
        "api_key" -> "APIキー"
        "local", "none" -> "ローカル"
        else -> "未確認"
    }

private fun myPageConnectionLabel(value: String): String =
    when (value) {
        "available", "connected" -> "接続済み"
        "auth_required", "authentication_required" -> "再認証が必要"
        else -> "未確認"
    }

private fun myPageBillingLabel(value: String): String =
    when (value) {
        "subscription" -> "契約の利用枠"
        "local" -> "ローカル"
        "free" -> "無料API"
        "paid" -> "有料API"
        else -> "料金未確認"
    }

private fun myPageModelReason(value: String?): String =
    when (value) {
        "private_content_not_allowed", "private_content_policy", "private_content_forbidden" -> "個別案内の送信が許可されていません。"
        "pricing_unverified", "price_unknown", "unknown_pricing" -> "単価を確認できないため定期解析に使えません。"
        "auth_required", "authentication_required" -> "Hermesの接続設定で再認証してください。"
        else -> "認証・利用条件・単価を確認できません。Hermesの接続設定を確認してください。"
    }

internal fun myPageConnectionLines(capabilities: CareerMyPageCapabilities?): List<String> {
    val failures = capabilities?.provider_errors.orEmpty().associate { it.provider to it.reason }
    val connections = capabilities?.connections.orEmpty().associateBy { it.provider }
    return (connections.keys + failures.keys).map { provider ->
        val connection = connections[provider]
        val status = connection?.state ?: "authentication_required"
        val label = "$provider：${myPageAuthLabel(
            connection?.auth_kind ?: "unknown",
        )} · ${myPageConnectionLabel(status)}"
        val reason = failures[provider] ?: connection?.reason
        if (status !in setOf("available", "connected")) "$label — ${myPageModelReason(reason)}" else label
    }
}
