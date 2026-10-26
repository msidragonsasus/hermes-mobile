package com.m57.hermescontrol.career

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.SectionHeader
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
    var disableReview by remember { mutableStateOf(false) }
    var discardModelChange by remember { mutableStateOf(false) }
    // One scrolling list with headings instead of chips that hid two thirds of the settings.
    LazyColumn(
        Modifier.fillMaxSize().testTag("career_settings"),
        contentPadding = PaddingValues(vertical = spacing.sm),
    ) {
        if (state.pending != null || state.error != null) {
            item("save_status") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text("保存状況の確認", style = MaterialTheme.typography.titleMedium)
                    state.error?.let { Text(it, color = colors.error) }
                    state.pending?.let {
                        Text("保存済みか不明な場合も、同じ操作として確認してから再試行します。", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            Button(
                                onClick = onRetry,
                                enabled = !state.busy,
                                shapes = ButtonDefaults.shapes(),
                            ) { Text(if (state.busy) "確認中…" else "同じ操作を再試行") }
                            TextButton(
                                onClick = onDiscard,
                                enabled = !state.busy,
                                shapes = ButtonDefaults.shapes(),
                            ) { Text("再試行を取りやめる") }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        state.message?.let { message ->
            item("message") {
                Text(
                    message,
                    color = colors.success,
                    modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
                )
            }
        }

        item("h_data") { SectionHeader("データ", Modifier.padding(horizontal = spacing.sm)) }
        item("mail") {
            CareerLinkRow("Gmail接続とメール通知", "企業メールの取り込みと、通知の受け取り方") {
                com.m57.hermescontrol.NavigationController
                    .navigateTo(CareerMailSettingsKey)
            }
        }
        item("sync") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text("データの同期", style = MaterialTheme.typography.titleMedium)
                Text(
                    "本人情報・経験・ESを接続済みのGitHubと同期します。企業のメールや認証情報は同期しません。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("同期状況：${careerStatus(snapshot?.sync?.text("state"))}")
                if (state.stale) Text("保存済みの表示です。接続して最新の状態を確認してください。", color = colors.warning)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    OutlinedButton(
                        onClick = { onSend("sync", JsonObject(emptyMap())) },
                        enabled = canWrite,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("今すぐ同期") }
                    TextButton(
                        onClick = onRefresh,
                        enabled = !state.loading,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(if (state.loading) "更新中…" else "状態を更新") }
                }
            }
        }
        if (snapshot != null) {
            items(snapshot.conflicts, key = { "conflict:${it.text("id")}" }) { conflict ->
                Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.xs)) {
                    CareerConflictCard(conflict, snapshot, canWrite, onSend)
                }
            }
            items(
                snapshot.entities.filter {
                    it.kind == "proposals" && it.data.text("status") == "pending"
                },
                key = { "proposal:${it.id}" },
            ) { proposal ->
                CareerLinkRow("保存案を確認", proposal.title) { onSelect(proposal) }
            }
        }

        item("h_ai") {
            HorizontalDivider(Modifier.padding(top = spacing.sm))
            SectionHeader("AI", Modifier.padding(horizontal = spacing.sm))
        }
        item("mypage_execution") {
            Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm)) { CareerMyPageExecutionPanel() }
        }
        item("coach_model") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text("相談で使うモデル", style = MaterialTheme.typography.titleMedium)
                Text(
                    "新しく始める相談の初期設定です。既存の会話と自動調査の選択は変わりません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CareerExecutionControls(
                    coachState.execution,
                    coachState.capabilities,
                    coachState.capabilitiesChecked && !coachState.busy && coachState.pending == null,
                    coachActions.selectExecution,
                    coachActions.refreshCapabilities,
                )
            }
        }
        item("daily_model") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text("自動調査の設定", style = MaterialTheme.typography.titleMedium)
                Text(
                    "保存済みのマイページ案内・企業メールを整理します。公開サイトの巡回は行いません。変更は次回の自動調査から反映されます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("自動調査で使うモデル", style = MaterialTheme.typography.bodyMedium)
                CareerExecutionControls(
                    selected = coachState.dailySettings?.execution,
                    capabilities = coachState.capabilities,
                    enabled = coachState.capabilitiesChecked && !coachState.busy && coachState.pending == null,
                    onSelect = coachActions.saveDailyExecution,
                    onRefresh = coachActions.refreshCapabilities,
                    label = "自動調査",
                )
                coachState.error?.let { Text(it, color = colors.error) }
                if (coachState.pending?.action == "daily-settings") {
                    Text("モデル設定の保存結果を確認できていません。同じ変更として再確認できます。")
                    FlowRow {
                        TextButton(
                            onClick = coachActions.retry,
                            enabled = !coachState.busy,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("モデル設定を再確認") }
                        TextButton(
                            onClick = { discardModelChange = true },
                            enabled = !coachState.busy,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("モデル設定の再試行をやめる") }
                    }
                }
            }
        }

        item("h_usage") {
            HorizontalDivider(Modifier.padding(top = spacing.sm))
            SectionHeader("利用状態", Modifier.padding(horizontal = spacing.sm))
        }
        item("home_display") {
            CareerLinkRow("ホームへの表示", "全体ホームの「表示するApp」で切り替えます。取得・通知とは別の設定です。") {
                com.m57.hermescontrol.NavigationController
                    .navigateTo(com.m57.hermescontrol.navigation.TodayScreen)
            }
        }
        item("disable") {
            // A real button node so it reads (and tests) as enabled/disabled.
            TextButton(
                onClick = { disableReview = true },
                enabled = canDisable,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.padding(horizontal = spacing.sm),
            ) { Text("就活Appを無効にする") }
            Text(
                "この接続先での利用を止めます。保存データは残り、サーバーの定期取得は別の設定です。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.lg),
            )
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

/** Flat drill-down row: reads as navigation, unlike the bare text buttons it replaces. */
@Composable
private fun CareerLinkRow(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
