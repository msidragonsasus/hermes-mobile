package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.navigation.TodayScreen
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.SettingsDivider
import com.m57.hermescontrol.ui.common.SettingsFootnote
import com.m57.hermescontrol.ui.common.SettingsGroup
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.common.SettingsListItem
import com.m57.hermescontrol.ui.common.SettingsPanel
import com.m57.hermescontrol.ui.common.SettingsPickerDialog
import com.m57.hermescontrol.ui.common.SettingsRow
import kotlinx.serialization.json.JsonObject

@Composable
internal fun CareerSettingsContent(
    state: CareerUiState,
    coachState: CareerCoachUiState,
    coachActions: CareerCoachActions,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onEnable: (Boolean) -> Unit,
    onSend: (String, JsonObject) -> Unit,
    onSelect: (CareerEntity) -> Unit,
) {
    val spacing = LocalSpacing.current
    val colors = LocalHermesStatusColors.current
    val snapshot = state.snapshot
    val canDisable =
        state.initialized && state.initializationError == null && !state.loading && !state.busy && state.pending == null
    val canWrite = !state.busy && state.pending == null && !state.stale && snapshot != null
    val proposals =
        snapshot?.entities.orEmpty().filter {
            it.kind == "proposals" && it.data.text(
                "status",
            ) == "pending"
        }
    var showProposals by rememberSaveable { mutableStateOf(false) }
    var disableReview by rememberSaveable { mutableStateOf(false) }
    var discardModelChange by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("career_settings"),
        contentPadding = PaddingValues(start = spacing.lg, end = spacing.lg, bottom = spacing.xl),
    ) {
        if (state.pending != null || state.error != null) {
            item("save_status") {
                SettingsGroupLabel("保存状況の確認")
                if (state.pending != null) {
                    SettingsGroup {
                        row {
                            SettingsRow(
                                if (state.busy) "確認中…" else "同じ操作を再試行",
                                enabled = !state.busy,
                                maxLines = Int.MAX_VALUE,
                                onClick = onRetry,
                            )
                        }
                        row {
                            SettingsRow(
                                "再試行を取りやめる",
                                enabled = !state.busy,
                                maxLines = Int.MAX_VALUE,
                                onClick = onDiscard,
                            )
                        }
                    }
                }
                state.error?.let { SettingsFootnote(it, color = colors.error) }
                if (state.pending != null) {
                    SettingsFootnote("保存済みか不明な場合も、同じ操作として確認してから再試行します。")
                }
            }
        }
        state.message?.let { message -> item("message") { SettingsFootnote(message, color = colors.success) } }

        item("data") {
            SettingsGroupLabel("データ")
            SettingsGroup {
                row {
                    SettingsRow(
                        "Gmail接続とメール通知",
                        subtitle = "企業メールの取り込みと、通知の受け取り方",
                        maxLines = Int.MAX_VALUE,
                        subtitleMaxLines = Int.MAX_VALUE,
                        chevron = true,
                        onClick = { NavigationController.navigateTo(CareerMailSettingsKey) },
                    )
                }
                row {
                    SettingsRow(
                        "データの同期",
                        value = careerStatus(snapshot?.sync?.text("state")),
                        wrapValue = true,
                        maxLines = Int.MAX_VALUE,
                        subtitle = "保存済みの表示です。接続して最新の状態を確認してください。".takeIf { state.stale },
                        subtitleColor = colors.warning,
                        subtitleMaxLines = Int.MAX_VALUE,
                    )
                }
                row {
                    SettingsRow(
                        "今すぐ同期",
                        enabled = canWrite,
                        maxLines = Int.MAX_VALUE,
                        onClick = { onSend("sync", JsonObject(emptyMap())) },
                    )
                }
                row {
                    SettingsRow(
                        if (state.loading) "更新中…" else "状態を更新",
                        enabled = !state.loading,
                        maxLines = Int.MAX_VALUE,
                        onClick = onRefresh,
                    )
                }
                if (proposals.isNotEmpty()) {
                    row {
                        SettingsRow(
                            "保存案",
                            value = "${proposals.size}件",
                            chevron = true,
                            wrapValue = true,
                            onClick = { showProposals = true },
                        )
                    }
                }
            }
            SettingsFootnote("本人情報・経験・ESを接続済みのGitHubと同期します。企業メールや認証情報は同期しません。")
        }
        if (snapshot != null && snapshot.conflicts.isNotEmpty()) {
            item("conflicts") { SettingsGroupLabel("同期の競合") }
            itemsIndexed(snapshot.conflicts, key = {
                _,
                conflict,
                ->
                "conflict:${conflict.text("id")}"
            }) { index, conflict ->
                SettingsListItem(first = index == 0, last = index == snapshot.conflicts.lastIndex) {
                    CareerConflictSettingsRow(conflict, snapshot, canWrite, onSend)
                }
            }
        }

        item("mypage_execution") { CareerMyPageExecutionPanel() }
        item("coach_model") {
            CareerCoachSettingsGroup(
                title = "相談で使うモデル",
                label = "会話",
                footnote = "新しい相談の初期設定です。既存の会話と自動調査の選択は変わりません。",
                selected = coachState.execution,
                state = coachState,
                onSelect = coachActions.selectExecution,
                onRefresh = coachActions.refreshCapabilities,
            )
        }
        item("daily_model") {
            val pending = coachState.pending?.action == "daily-settings"
            CareerCoachSettingsGroup(
                title = "自動調査の設定",
                label = "自動調査",
                footnote = "保存済みのマイページ案内・企業メールを整理します。公開サイトは巡回しません。変更は次回の自動調査から反映されます。",
                selected = coachState.dailySettings?.execution,
                state = coachState,
                onSelect = coachActions.saveDailyExecution,
                onRefresh = coachActions.refreshCapabilities,
                pending = pending,
                onRetry = coachActions.retry,
                onDiscard = { discardModelChange = true },
            )
            coachState.error?.let { SettingsFootnote(it, color = colors.error) }
            if (pending) SettingsFootnote("モデル設定の保存結果を確認できていません。同じ変更として再確認できます。")
        }

        item("usage") {
            SettingsGroupLabel("利用状態")
            SettingsGroup {
                row {
                    SettingsRow(
                        "ホームへの表示",
                        subtitle = "全体ホームの「表示するApp」で切り替えます。取得・通知とは別の設定です。",
                        maxLines = Int.MAX_VALUE,
                        subtitleMaxLines = Int.MAX_VALUE,
                        chevron = true,
                        onClick = { NavigationController.navigateTo(TodayScreen) },
                    )
                }
                row {
                    SettingsRow(
                        "就活Appを無効にする",
                        titleColor = colors.error,
                        maxLines = Int.MAX_VALUE,
                        enabled = canDisable,
                        onClick = { disableReview = true },
                    )
                }
            }
            SettingsFootnote("この接続先での利用を止めます。保存データは残り、サーバーの定期取得は別の設定です。")
        }
    }
    if (showProposals) {
        SettingsPickerDialog(title = "保存案", onDismiss = { showProposals = false }) {
            if (proposals.isEmpty()) Text("確認待ちの保存案はありません。")
            proposals.forEach { proposal ->
                SettingsRow(
                    title = proposal.title,
                    chevron = true,
                    maxLines = Int.MAX_VALUE,
                    onClick = {
                        showProposals = false
                        onSelect(proposal)
                    },
                )
            }
        }
    }
    if (disableReview) {
        AlertDialog(
            onDismissRequest = { disableReview = false },
            title = { Text("この接続先で就活Appを無効にしますか") },
            text = { Text("保存済みの企業・ES・設定は残り、再度有効にすると利用できます。サーバーの定期取得は停止しません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        disableReview = false
                        onEnable(false)
                    },
                    enabled = canDisable,
                ) { Text("無効にする") }
            },
            dismissButton = { TextButton(onClick = { disableReview = false }) { Text("戻る") } },
        )
    }
    if (discardModelChange) {
        AlertDialog(
            onDismissRequest = { discardModelChange = false },
            title = { Text("モデル設定の再試行をやめますか") },
            text = { Text("サーバーに保存済みの場合は変更が残ります。候補を再確認して保存状態を確認してください。") },
            confirmButton = {
                TextButton(onClick = {
                    coachActions.discard()
                    discardModelChange = false
                }) { Text("再試行をやめる") }
            },
            dismissButton = { TextButton(onClick = { discardModelChange = false }) { Text("戻る") } },
        )
    }
}

@Composable
private fun CareerCoachSettingsGroup(
    title: String,
    label: String,
    footnote: String,
    selected: CareerExecution?,
    state: CareerCoachUiState,
    onSelect: (CareerExecution) -> Unit,
    onRefresh: () -> Unit,
    pending: Boolean = false,
    onRetry: () -> Unit = {},
    onDiscard: () -> Unit = {},
) {
    val capabilities = state.capabilities
    val enabled =
        state.capabilitiesChecked && !state.busy && state.pending == null &&
            capabilities?.state == "available" && "execution_config" in capabilities.features
    val errors = capabilities?.provider_errors.orEmpty()
    var showTechnical by rememberSaveable { mutableStateOf(false) }
    SettingsGroupLabel(title)
    SettingsPanel {
        CareerSettingsExecutionRows(
            modelTitle = "モデル",
            tagPrefix = "career_$label",
            selected = selected,
            models =
                capabilities?.models.orEmpty().map {
                    CareerSettingsModel(
                        it.provider,
                        it.id,
                        it.label,
                        it.reasoning_efforts,
                    )
                },
            enabled = enabled,
            onSelect = onSelect,
            catalogSheet = true,
        )
        SettingsDivider()
        SettingsRow("候補を再確認", onClick = onRefresh)
        if (pending) {
            SettingsDivider()
            SettingsRow("モデル設定を再確認", maxLines = Int.MAX_VALUE, enabled = !state.busy, onClick = onRetry)
            SettingsDivider()
            SettingsRow("モデル設定の再試行をやめる", maxLines = Int.MAX_VALUE, enabled = !state.busy, onClick = onDiscard)
        }
        if (errors.isNotEmpty()) {
            SettingsDivider()
            SettingsRow(
                "技術情報",
                subtitle = "一部のモデル候補を取得できませんでした。",
                subtitleMaxLines = Int.MAX_VALUE,
                value = if (showTechnical) "表示中" else null,
                wrapValue = true,
                chevron = true,
                onClick = { showTechnical = !showTechnical },
            )
        }
    }
    SettingsFootnote(footnote)
    if (showTechnical && errors.isNotEmpty()) {
        SettingsFootnote(
            errors.joinToString("\n") { failure ->
                "${failure.provider}：${careerPreparationReason(failure.reason) ?: "候補を取得できませんでした。"}"
            },
        )
    }
}
