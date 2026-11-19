package com.m57.hermescontrol.miniapp.sdui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.career.CareerCompanySettingsKey
import com.m57.hermescontrol.career.CareerMyPageBrowserKey
import com.m57.hermescontrol.career.careerScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon

/**
 * MyPage status described by the server for one company. Opening the route only reads; the single
 * write (a MyPage check) happens on an explicit tap.
 */
@Composable
fun CareerMyPageSduiScreen(key: CareerMyPageSduiKey) {
    val context by AuthManager.contextFlow.collectAsState()
    val currentScope = remember(context) { careerScope() }

    // The connection this route was opened on. Kept across rotation so a switch while away is seen.
    val entryScope = rememberSaveable(key) { careerScope() }
    val appContext = LocalContext.current.applicationContext
    val model: SduiHostViewModel =
        viewModel(
            key = "sdui:$entryScope:${key.companyId}",
            factory =
                viewModelFactory {
                    initializer {
                        SduiHostViewModel(
                            DashboardSduiRepository(entryScope, companyHint = key.companyId),
                            key.companyId,
                            SharedPreferencesSduiPendingStore.forScope(appContext, entryScope, key.companyId),
                        )
                    }
                },
        )
    val state by model.state.collectAsState()

    LaunchedEffect(model) { model.load() }
    LaunchedEffect(model, currentScope, entryScope) {
        if (currentScope != entryScope) model.onConnectionChanged()
    }
    DisposableEffect(model) { onDispose { model.release() } }

    HermesScaffold(
        title = {
            Text(
                (state.phase as? SduiPhase.Ready)?.screen?.title ?: "マイページの状況",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        pinTopBar = true,
    ) {
        Column(Modifier.fillMaxSize()) {
            OutlinedButton(
                onClick = {
                    NavigationController.navigateTo(
                        com.m57.hermescontrol.miniapp.workbench
                            .CareerMyPageWorkbenchKey(key.companyId),
                    )
                },
                enabled = currentScope == entryScope,
                modifier = Modifier.fillMaxWidth().testTag("sdui_open_workbench"),
            ) { Text("画面・専用Botの改修") }
            SduiHostContent(
                state = state,
                onRefresh = model::refresh,
                onReload = model::load,
                onSendCheck = model::sendCheck,
                onRetryCheck = model::retryCheck,
                onOpenSettings = { NavigationController.navigateTo(CareerCompanySettingsKey(key.companyId)) },
                onOpenBrowser = { NavigationController.navigateTo(CareerMyPageBrowserKey(key.companyId)) },
                onBack = { NavigationController.goBack() },
            )
        }
    }
}

/** Stateless body of the screen, separate so it can be exercised without a ViewModel or network. */
@Composable
internal fun SduiHostContent(
    state: SduiUiState,
    onRefresh: () -> Unit,
    onReload: () -> Unit,
    onSendCheck: () -> Unit,
    onRetryCheck: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBrowser: () -> Unit,
    onBack: () -> Unit,
) {
    when (val phase = state.phase) {
        SduiPhase.Idle, SduiPhase.Loading -> {
            LoadingState(subtitle = "読み込んでいます…")
        }

        is SduiPhase.Ready -> {
            SduiReadyContent(
                phase = phase,
                check = state.check,
                onRefresh = onRefresh,
                onSendCheck = onSendCheck,
                onRetryCheck = onRetryCheck,
                onOpenSettings = onOpenSettings,
                onOpenBrowser = onOpenBrowser,
            )
        }

        is SduiPhase.Unavailable -> {
            SduiUnavailableContent(
                reason = phase.reason,
                onReload = onReload,
                onOpenSettings = onOpenSettings,
                onOpenBrowser = onOpenBrowser,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun SduiReadyContent(
    phase: SduiPhase.Ready,
    check: SduiCheckState,
    onRefresh: () -> Unit,
    onSendCheck: () -> Unit,
    onRetryCheck: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val sending = check is SduiCheckState.Sending
    val mayStartCheck = phase.canCheck && (check == SduiCheckState.Idle || check == SduiCheckState.Refused)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.md)
            .testTag("sdui_screen"),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SduiCheckBanner(check, onRefresh, onRetryCheck)
        SduiRenderer(
            nodes = phase.screen.components,
            actionEnabled = { action ->
                when (action) {
                    SduiAction.MYPAGE_CHECK -> mayStartCheck
                    SduiAction.REFRESH -> !sending
                    SduiAction.NATIVE_SETTINGS, SduiAction.NATIVE_BROWSER -> true
                }
            },
            onAction = { action ->
                when (action) {
                    SduiAction.REFRESH -> onRefresh()
                    SduiAction.MYPAGE_CHECK -> onSendCheck()
                    SduiAction.NATIVE_SETTINGS -> onOpenSettings()
                    SduiAction.NATIVE_BROWSER -> onOpenBrowser()
                }
            },
        )
    }
}

/** Host-owned state of the one write. Never taken from the server payload. */
@Composable
private fun SduiCheckBanner(
    check: SduiCheckState,
    onRefresh: () -> Unit,
    onRetryCheck: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val buttonModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    when (check) {
        SduiCheckState.Idle -> {
            Unit
        }

        is SduiCheckState.Sending -> {
            SduiStatusView(
                if (check.retry) "同じ確認をもう一度送信しています…" else "確認を送信しています…",
                SduiTone.INFO,
                Modifier.testTag("sdui_check_sending"),
            )
        }

        is SduiCheckState.Ambiguous -> {
            Column(
                Modifier.testTag("sdui_check_unknown"),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                SduiStatusView(
                    "確認の依頼が届いたかどうか分かりません。同じ確認としてもう一度送信できます。新しい確認は、この結果がわかってから開始できます。",
                    SduiTone.WARNING,
                )
                FilledTonalButton(
                    onClick = onRetryCheck,
                    modifier = buttonModifier.testTag("sdui_retry_check"),
                    shapes = ButtonDefaults.shapes(),
                ) { Text("同じ確認をもう一度送信") }
            }
        }

        is SduiCheckState.Accepted -> {
            Column(
                Modifier.testTag("sdui_check_accepted"),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                SduiStatusView(
                    when (check.receipt.state) {
                        SduiReceiptState.QUEUED -> "確認を受け付けました。順番に確認します。"
                        SduiReceiptState.RUNNING -> "確認を実行中です。"
                    },
                    SduiTone.SUCCESS,
                )
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = buttonModifier.testTag("sdui_refresh"),
                    shapes = ButtonDefaults.shapes(),
                ) { Text("表示を更新") }
            }
        }

        SduiCheckState.Refused -> {
            SduiStatusView(
                "確認を開始できませんでした。表示を更新して状態を確認してください。",
                SduiTone.ERROR,
                Modifier.testTag("sdui_check_refused"),
            )
        }

        SduiCheckState.Blocked -> {
            Column(
                Modifier.testTag("sdui_check_blocked"),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                SduiStatusView(
                    "前回の確認の状態を確認できません。新しい確認は開始できません。表示を更新すると、もう一度確認します。",
                    SduiTone.WARNING,
                )
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = buttonModifier.testTag("sdui_refresh"),
                    shapes = ButtonDefaults.shapes(),
                ) { Text("表示を更新") }
            }
        }

        is SduiCheckState.NeedsReload -> {
            Column(
                Modifier.testTag("sdui_check_stale"),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                SduiStatusView("表示が古くなっています。更新してからもう一度操作してください。", SduiTone.WARNING)
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = buttonModifier.testTag("sdui_refresh"),
                    shapes = ButtonDefaults.shapes(),
                ) { Text("表示を更新") }
            }
        }
    }
}

@Composable
private fun SduiUnavailableContent(
    reason: SduiUnavailable,
    onReload: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBrowser: () -> Unit,
    onBack: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val buttonModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    Column(Modifier.fillMaxSize().testTag("sdui_unavailable")) {
        Box(Modifier.weight(1f)) {
            when (reason) {
                SduiUnavailable.LOAD_FAILED -> {
                    ErrorState("状況を読み込めませんでした。接続を確認して、もう一度お試しください。", onRetry = onReload)
                }

                SduiUnavailable.RELEASE_CHANGED -> {
                    ErrorState("表示内容が更新されました。もう一度読み込んでください。", onRetry = onReload)
                }

                SduiUnavailable.HOST_UNSUPPORTED -> {
                    EmptyState(
                        title = "このサーバーでは利用できません",
                        subtitle = "従来のマイページ画面とブラウザはそのまま使えます。サーバーが更新されると、ここに状況が表示されます。",
                    )
                }

                SduiUnavailable.AUTH_REQUIRED -> {
                    EmptyState(
                        title = "ログインを確認してください",
                        subtitle = "Hermesへのログインが切れているか、この操作の権限がありません。",
                    )
                }

                SduiUnavailable.CONTRACT_UNSUPPORTED -> {
                    EmptyState(
                        title = "この表示には対応していません",
                        subtitle = "アプリまたはサーバーの更新が必要です。従来のマイページ画面とブラウザはそのまま使えます。",
                    )
                }

                SduiUnavailable.CONNECTION_CHANGED -> {
                    EmptyState(
                        title = "接続先が変わりました",
                        subtitle = "企業画面から開き直してください。",
                    )
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            // A changed connection means the company may not exist on the new host: back only.
            if (reason != SduiUnavailable.CONNECTION_CHANGED) {
                OutlinedButton(
                    onClick = onOpenBrowser,
                    modifier = buttonModifier.testTag("sdui_native_browser"),
                    shapes = ButtonDefaults.shapes(),
                ) { Text("マイページを開く") }
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = buttonModifier.testTag("sdui_native_settings"),
                    shapes = ButtonDefaults.shapes(),
                ) { Text("企業設定") }
            }
            OutlinedButton(
                onClick = onBack,
                modifier = buttonModifier.testTag("sdui_back"),
                shapes = ButtonDefaults.shapes(),
            ) { Text("戻る") }
        }
    }
}
