package com.m57.hermescontrol.pcpower

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.PrimaryPillButton
import kotlinx.coroutines.awaitCancellation

@Composable
fun PcPowerScreen() {
    val context by AuthManager.contextFlow.collectAsState()
    val scope = remember(context) { pcPowerScope() }
    key(scope) {
        val model: PcPowerViewModel =
            viewModel(
                key = "pc-power:$scope",
                factory = viewModelFactory { initializer { PcPowerViewModel(DashboardPcPowerRepository(scope)) } },
            )
        PcPowerRefreshEffect(model)
        val state by model.state.collectAsState()
        PcPowerScaffold(state, model::refresh, model::wake, model::sleep)
    }
}

@Composable
internal fun PcPowerRefreshEffect(model: PcPowerViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.onScreenResumed()
            try {
                awaitCancellation()
            } finally {
                model.onScreenPaused()
            }
        }
    }
}

@Composable
internal fun PcPowerScaffold(
    state: PcPowerUiState,
    onRefresh: () -> Unit,
    onWake: () -> Unit,
    onSleep: () -> Unit,
) {
    var confirmingSleep by rememberSaveable { mutableStateOf(false) }
    HermesScaffold(
        title = { Text("PC電源") },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        actions = {
            IconButton(
                onClick = onRefresh,
                enabled = state.canRefresh,
                modifier = Modifier.testTag("pc_power_refresh"),
            ) { Icon(Icons.Default.Refresh, contentDescription = "状態を更新") }
        },
    ) {
        PcPowerContent(state, onRefresh, onWake, { confirmingSleep = true })
    }
    if (confirmingSleep) {
        AlertDialog(
            onDismissRequest = { if (!state.busy) confirmingSleep = false },
            icon = { Icon(Icons.Default.Bedtime, contentDescription = null) },
            title = { Text("PCをスリープしますか？") },
            text = { Text("開いている作業はそのまま残りますが、未保存の内容がないことを確認してください。") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmingSleep = false
                        onSleep()
                    },
                    enabled = state.canSleep,
                    modifier = Modifier.testTag("pc_power_sleep_confirm"),
                    shapes = ButtonDefaults.shapes(),
                ) { Text("スリープする") }
            },
            dismissButton = { TextButton(onClick = { confirmingSleep = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
internal fun PcPowerContent(
    state: PcPowerUiState,
    onRefresh: () -> Unit,
    onWake: () -> Unit,
    onRequestSleep: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val statusColors = LocalHermesStatusColors.current
    val working = state.busy || state.loading || state.checkingTransition
    val awake = !state.statusStale && !state.loading && state.status?.power_state == "awake"
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.md, vertical = spacing.lg)
            .testTag("pc_power_screen"),
    ) {
        // Header: the machine and its state, on the page background (no card).
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Computer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    state.status?.target_name ?: "自宅デスクトップPC",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (working) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    } else {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (awake) statusColors.success else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                ),
                        )
                    }
                    Text(
                        when {
                            state.loading -> "状態を確認中"
                            state.statusStale -> "現在の状態は未確認です"
                            state.status?.power_state == "awake" -> "起動中"
                            state.status?.power_state == "asleep_or_unreachable" -> "スリープ中、または到達できません"
                            else -> "現在の状態は未確認です"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("pc_power_status"),
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.xl))

        // One loud action (wake); sleep is a quiet text button behind a confirmation.
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryPillButton(
                onClick = onWake,
                enabled = state.canWake,
                modifier = Modifier.testTag("pc_power_wake"),
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(spacing.sm))
                Text("起動する")
            }
            TextButton(
                onClick = onRequestSleep,
                enabled = state.canSleep,
                modifier = Modifier.testTag("pc_power_sleep"),
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(Icons.Default.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(spacing.sm))
                Text("スリープする")
            }
        }
        if (!state.canSleep) {
            Text(
                when {
                    state.busy -> "電源操作の要求を送信中です。"
                    state.checkingTransition -> "PCの状態を自動で確認しています。"
                    state.loading -> "最新の状態を確認しています。"
                    state.statusStale || state.status == null -> "「状態を更新」で最新の状態を確認してください。"
                    !state.status.sleep_available -> "対象PCのスリープ受信設定を確認してください。"
                    else -> "PCの起動を確認できると、スリープ操作が使えます。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.sm).testTag("pc_power_sleep_unavailable"),
            )
        }

        // At most one notice: the most important error, else the latest note.
        val receiverError =
            if (!state.statusStale && !state.loading) {
                when (state.status?.sleep_error) {
                    "shutdown_privilege_missing" -> "対象PCのスリープ実行権限がありません。受信処理を修復してください。"
                    "sleep_failed" -> "対象PCでスリープの実行に失敗しました。受信処理の診断記録を確認してください。"
                    else -> null
                }
            } else {
                null
            }
        val notice: Triple<String, NoticeTone, String>? =
            state.error?.let { Triple(it, NoticeTone.Error, "pc_power_error") }
                ?: state.statusError?.let { Triple(it, NoticeTone.Error, "pc_power_refresh_error") }
                ?: receiverError?.let { Triple(it, NoticeTone.Warning, "pc_power_receiver_error") }
                ?: state.refreshNote?.let { Triple(it, NoticeTone.Info, "pc_power_refresh_note") }
                ?: state.message?.let { Triple(it, NoticeTone.Info, "pc_power_message") }
        notice?.let { (text, tone, tag) ->
            InlineNotice(
                text = text,
                tone = tone,
                modifier = Modifier.padding(top = spacing.lg).testTag(tag),
            )
        }

        Spacer(Modifier.height(spacing.xl))
        Text(
            "操作はHermesサーバーから固定したPCに直接送られます。AIエージェントは使用しません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
