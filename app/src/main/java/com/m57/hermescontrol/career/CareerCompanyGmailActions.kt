package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
internal fun CareerCompanyGmailActions(
    scope: String,
    companyId: String,
    onSaved: () -> Unit,
) {
    val repository = remember(scope) { CareerCompanyRepository(scope) }
    val progress by rememberMailProgress(scope)
    val status = progress.view?.get("status") as? JsonObject
    val callback by rememberUpdatedState(onSaved)
    val coroutineScope = rememberCoroutineScope()
    var busy by remember(scope) { mutableStateOf(false) }
    var message by remember(scope) { mutableStateOf<String?>(null) }
    var error by remember(scope) { mutableStateOf<String?>(null) }
    LaunchedEffect(companyId, status) {
        if (status != null) callback()
    }
    CareerCompanyMailButtons(
        enabled = progress.view?.text("enabled") == "true",
        busy = busy,
        onRefresh = {
            busy = true
            coroutineScope.launch {
                try {
                    repository.mutate("check", buildJsonObject { put("channel", "mail") })
                    message = "全企業の更新を受け付けました。画面を閉じても続行します。"
                    error = null
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = "更新の受付を確認できません。保存済みメールは閲覧できます。"
                } finally {
                    busy = false
                }
            }
        },
        onSettings = { NavigationController.navigateTo(CareerMailSettingsKey) },
    )
    message?.let { Text(it) }
    (error ?: progress.error)?.let { Text(it, color = LocalHermesStatusColors.current.error) }
}

@Composable
internal fun CareerCompanyMailButtons(
    enabled: Boolean,
    busy: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    Column {
        FlowRow {
            TextButton(
                onClick = onRefresh,
                enabled = enabled && !busy,
                shapes = ButtonDefaults.shapes(),
            ) { Text("全企業を更新") }
            TextButton(onClick = onSettings, shapes = ButtonDefaults.shapes()) { Text("自動同期の設定") }
        }
        if (!enabled) Text("自動同期を有効にすると、企業ごとの取得操作は不要です。")
    }
}
