package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.ProvidersScreen
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesTextField
import com.m57.hermescontrol.ui.common.SettingsDivider
import com.m57.hermescontrol.ui.common.SettingsFootnote
import com.m57.hermescontrol.ui.common.SettingsFormSubmit
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.common.SettingsPanel
import com.m57.hermescontrol.ui.common.SettingsRow
import java.net.URI

@Composable
internal fun CareerMyPageExecutionPanel() {
    val context by AuthManager.contextFlow.collectAsState()
    val scope = remember(context) { careerScope() }
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
    var showTechnical by rememberSaveable { mutableStateOf(false) }
    val enabled = state.supported && state.settings != null && !state.loading && !state.saving && state.pending == null
    val models = state.capabilities?.models.orEmpty()
    val unknownLabel =
        when {
            state.settings != null -> null
            state.loading -> "確認中"
            else -> "読み込めません"
        }
    val choices =
        models.map { model ->
            CareerSettingsModel(
                provider = model.provider,
                id = model.id,
                label = model.label,
                efforts = model.reasoning_efforts,
                billing = myPageBillingLabel(model.billing),
                selectable = model.canSelect,
                unavailableReason = if (model.canSelect) null else myPageModelReason(model.reason),
            )
        }
    val technical =
        buildList {
            addAll(myPageConnectionLines(state.capabilities))
            listOfNotNull(
                draft.analysis,
                draft.learning.takeUnless { draft.use_analysis_for_learning },
            ).distinct().forEach { execution ->
                val option = models.firstOrNull { it.provider == execution.provider && it.id == execution.model }
                add("モデル：${execution.model} · ${execution.provider}")
                if (option != null) {
                    add(
                        "認証：${myPageAuthLabel(
                            option.auth_kind,
                        )} · 接続：${myPageConnectionLabel(option.connection_state)}",
                    )
                    option.endpoint?.let(::displayableEndpoint)?.let { add("接続先：$it") }
                }
            }
        }
    Column(Modifier.testTag("career_mypage_execution")) {
        SettingsGroupLabel("マイページ確認")
        SettingsPanel {
            CareerSettingsExecutionRows(
                modelTitle = "本文の整理",
                tagPrefix = "career_mypage_本文の整理",
                selected = draft.analysis,
                models = choices,
                enabled = enabled,
                onSelect = { draft = draft.copy(analysis = it) },
                emptyLabel = "利用できるモデルがありません。Hermesのモデル接続を確認してください。",
                unknownLabel = unknownLabel,
            )
            SettingsDivider()
            SettingsRow(
                "手順学習も同じモデルを使う",
                maxLines = Int.MAX_VALUE,
                checked = draft.use_analysis_for_learning,
                enabled = enabled,
                onCheckedChange = { draft = draft.copy(use_analysis_for_learning = it) },
            )
            if (!draft.use_analysis_for_learning) {
                SettingsDivider()
                CareerSettingsExecutionRows(
                    modelTitle = "手順学習",
                    tagPrefix = "career_mypage_手順学習",
                    selected = draft.learning,
                    models = choices,
                    enabled = enabled,
                    onSelect = { draft = draft.copy(learning = it) },
                    reasoningTitle = "手順学習の推論",
                    emptyLabel = "利用できるモデルがありません。Hermesのモデル接続を確認してください。",
                    unknownLabel = unknownLabel,
                )
            }
            SettingsDivider()
            SettingsRow(
                "有料APIの利用を有効にする",
                modifier = Modifier.testTag("career_mypage_paid_enabled"),
                maxLines = Int.MAX_VALUE,
                checked = draft.paid_enabled,
                enabled = enabled,
                onCheckedChange = { draft = draft.copy(paid_enabled = it) },
            )
            if (draft.paid_enabled) {
                SettingsDivider()
                HermesTextField(
                    value = runBudget,
                    onValueChange = { runBudget = it },
                    label = { Text("1実行の料金上限（USD）") },
                    enabled = enabled,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(spacing.lg),
                )
                SettingsDivider()
                HermesTextField(
                    value = dayBudget,
                    onValueChange = { dayBudget = it },
                    label = { Text("1日の料金上限（USD）") },
                    enabled = enabled,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(spacing.lg),
                )
            }
            SettingsDivider()
            SettingsRow(
                "Hermesのモデル接続を管理",
                maxLines = Int.MAX_VALUE,
                chevron = true,
                onClick = { NavigationController.navigateTo(ProvidersScreen) },
            )
            if (technical.isNotEmpty()) {
                SettingsDivider()
                SettingsRow(
                    "技術情報",
                    value = if (showTechnical) "表示中" else null,
                    chevron = true,
                    onClick = { showTechnical = !showTechnical },
                )
            }
            SettingsDivider()
            SettingsRow(
                if (state.loading) "設定・接続を確認中…" else "設定・候補を再読込",
                maxLines = Int.MAX_VALUE,
                enabled = !state.loading && !state.saving,
                onClick = onRefresh,
            )
            if (state.pending != null) {
                SettingsDivider()
                SettingsRow("同じ保存を再確認", maxLines = Int.MAX_VALUE, enabled = !state.saving, onClick = onRetry)
            }
            SettingsDivider()
            SettingsFormSubmit(
                label = "設定を保存",
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
                busy = state.saving,
                buttonModifier = Modifier.testTag("career_mypage_execution_save"),
            )
        }
        state.error?.let { SettingsFootnote(it, color = colors.error) }
        state.message?.let { SettingsFootnote(it, color = colors.success) }
        if (state.supported && state.settings == null && !state.loading) {
            SettingsFootnote("接続して設定を読み込んでください。")
        }
        SettingsFootnote(
            "モデルは自動選択しません。外部モデルには、氏名や個別案内を含む本文を送ります。ID・パスワード・Cookieは送りません。" +
                "料金上限0では課金せず、単価未確認の接続は選べません。認証や上限の問題があっても別モデルに切り替えません。" +
                "変更は次に受け付ける実行から反映されます。",
        )
        if (showTechnical && technical.isNotEmpty()) {
            SelectionContainer { SettingsFootnote(technical.joinToString("\n")) }
        }
    }
}

/** Endpoints are diagnostic text. Strip credentials, query parameters and fragments. */
private fun displayableEndpoint(endpoint: String): String? =
    runCatching { URI(endpoint) }.getOrNull()?.let { uri ->
        val host = uri.host ?: return null
        val scheme = uri.scheme?.takeIf { it == "https" || it == "http" } ?: return null
        URI(scheme, null, host, uri.port, uri.path, null, null).toString()
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
