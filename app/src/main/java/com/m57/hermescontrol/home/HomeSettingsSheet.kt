package com.m57.hermescontrol.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.SettingsGoogleConnection
import com.m57.hermescontrol.ui.common.HermesTextField
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.SettingsFootnote
import com.m57.hermescontrol.ui.common.SettingsFormSubmit
import com.m57.hermescontrol.ui.common.SettingsGroup
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.common.SettingsPanel
import com.m57.hermescontrol.ui.common.SettingsRow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeSettingsSheet(
    scope: String,
    today: TodayPlanState,
    chatVisible: Boolean,
    careerVisible: Boolean,
    careerEnabled: Boolean,
    metadata: HomePage?,
    onProviderChange: (String, Boolean) -> Unit,
    saveSettings: suspend (TodaySettings) -> TodaySaveResult = today::save,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("home_settings_sheet"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text("ホームの設定", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            SettingsGroupLabel("ホームに表示するApp")
            SettingsGroup {
                row {
                    SettingsRow(
                        "チャット",
                        checked = chatVisible,
                        onCheckedChange = { onProviderChange("chat", it) },
                        modifier = Modifier.testTag("home_show_chat"),
                    )
                }
                row {
                    val checkedAt = metadata?.checked_at?.takeIf(String::isNotBlank)
                    SettingsRow(
                        "就活",
                        subtitle = checkedAt?.let { "保存情報を確認済み · " + todayShortTime(it) },
                        checked = careerVisible,
                        onCheckedChange = { onProviderChange("career", it) },
                    )
                }
            }
            SettingsFootnote("取得・通知設定は変更しません。承認・回答待ちは表示を続けます。チャットは端末が受信した回答が対象です。")
            SettingsGroupLabel("今日やることの情報源")
            val settings = today.settings
            if (settings == null) {
                SettingsPanel {
                    val message =
                        when {
                            today.failure == HomeFailure.AuthRequired -> "ログイン後に確認先を読み込めます。"
                            today.failure == HomeFailure.NotGated -> "この接続方式では確認先を読み込めません。"
                            today.failure == HomeFailure.Offline -> "オフラインのため確認先を読み込めません。"
                            today.failure != null -> "確認先の設定を読み込めませんでした。"
                            else -> "確認先の設定を読み込んでいます。"
                        }
                    InlineNotice(
                        text = message,
                        modifier = Modifier.padding(12.dp),
                        actionLabel = "再試行",
                        onAction = onRetry,
                    )
                }
            } else {
                TodayPlanSettings(scope, settings, today.busy, saveSettings) {
                    sheetState.hide()
                    if (!sheetState.isVisible) onDismiss()
                }
            }
            SettingsFootnote(
                "1日1回、LLMがメール・課題と前回のキャッシュを見て整理します。完了チェックはこの一覧だけに保存します。日時は日本時間で表示しています。",
            )
            if (careerEnabled && metadata != null && metadata.coverage.isNotEmpty()) {
                SettingsGroupLabel("就活の確認状況")
                SettingsGroup {
                    metadata.coverage.forEach { coverage ->
                        row {
                            val details =
                                listOfNotNull(
                                    coverageLabel(coverage.state),
                                    coverage.last_success_at
                                        ?.takeIf(
                                            String::isNotBlank,
                                        )?.let { "最終取得 " + todayShortTime(it) },
                                )
                            SettingsRow(
                                coverage.label,
                                subtitle = details.joinToString(" · "),
                                subtitleMaxLines = Int.MAX_VALUE,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayPlanSettings(
    scope: String,
    settings: TodaySettings,
    actionBusy: Boolean,
    saveSettings: suspend (TodaySettings) -> TodaySaveResult,
    onSaved: suspend () -> Unit,
) {
    val coroutine = rememberCoroutineScope()
    var email by rememberSaveable(scope) { mutableStateOf(settings.email) }
    var manaba by rememberSaveable(scope) { mutableStateOf(settings.manaba_url) }
    var enabled by rememberSaveable(scope) { mutableStateOf(settings.enabled) }
    var result by remember(scope) { mutableStateOf<TodaySaveResult?>(null) }
    var saving by remember(scope) { mutableStateOf(false) }
    val busy = actionBusy || saving
    val code = (result as? TodaySaveResult.InvalidInput)?.code
    val emailError =
        when {
            code == "source_required" -> "メールまたはmanabaの確認先を入力してください。"
            code == "invalid_settings" && email.isNotBlank() -> "メールアドレスの形式を確認してください。"
            else -> null
        }
    val manabaError =
        when {
            code == "source_required" -> "メールまたはmanabaの確認先を入力してください。"
            code == "invalid_settings" && manaba.isNotBlank() -> "manabaのホームURLを確認してください。"
            else -> null
        }
    SettingsPanel {
        HermesTextField(
            value = email,
            onValueChange = {
                email = it
                result = null
            },
            label = { Text("確認するメールアドレス") },
            supportingText = { Text(emailError ?: "Google接続に登録済みのアドレス") },
            isError = emailError != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled = !busy,
        )
        SettingsRow(
            "Google接続を確認する",
            chevron = true,
            enabled = !busy,
            onClick = { NavigationController.navigateTo(SettingsGoogleConnection) },
        )
        HermesTextField(
            value = manaba,
            onValueChange = {
                manaba = it
                result = null
            },
            label = { Text("manabaのホームURL") },
            supportingText = { Text(manabaError ?: "ログイン情報を含まない https:// で始まるURL") },
            isError = manabaError != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            enabled = !busy,
        )
        TextButton(
            onClick = {
                manaba = "https://manaba.hit-u.ac.jp/ct/home"
                result = null
            },
            enabled = !busy,
            modifier = Modifier.padding(horizontal = 8.dp),
            shapes = ButtonDefaults.shapes(),
        ) { Text("一橋大学のmanabaを使う") }
        SettingsFootnote("manabaの初回ログインはサーバー側の準備が必要です。未接続の間は未確認と表示します。")
        SettingsRow(
            "ホームを開いた日に確認する",
            checked = enabled,
            onCheckedChange = {
                enabled = it
                result = null
            },
            enabled = !busy,
        )
        val notice =
            when (val current = result) {
                TodaySaveResult.RunInProgress -> {
                    "確認中のため保存できません。終了後にもう一度保存してください。"
                }

                is TodaySaveResult.Failed -> {
                    when (current.failure) {
                        HomeFailure.AuthRequired -> "ログインしてから、もう一度保存してください。"
                        HomeFailure.NotGated -> "この接続方式では設定を保存できません。"
                        HomeFailure.Offline -> "オフラインのため保存できません。接続後にもう一度お試しください。"
                        else -> "設定を保存できませんでした。もう一度お試しください。"
                    }
                }

                else -> {
                    null
                }
            }
        notice?.let { InlineNotice(it, Modifier.padding(horizontal = 16.dp), tone = NoticeTone.Warning) }
        SettingsFormSubmit(
            label = "保存する",
            enabled = !busy,
            busy = saving,
            onClick = {
                saving = true
                result = null
                val value = TodaySettings(email.trim(), manaba.trim(), enabled)
                coroutine.launch {
                    try {
                        val saved = saveSettings(value)
                        if (saved == TodaySaveResult.Ok) onSaved() else result = saved
                    } finally {
                        saving = false
                    }
                }
            },
        )
    }
}

private fun coverageLabel(state: String): String =
    when (state) {
        "updated", "unchanged" -> "取得済み"
        "running" -> "取得中"
        "partial" -> "一部取得・未確認の情報があります"
        "failed" -> "最新情報を取得できていません"
        "auth_required" -> "接続の確認が必要"
        "disabled" -> "取得停止中"
        else -> "まだ確認できていません"
    }
