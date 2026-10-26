package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.theme.LocalSpacing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Saved-state visibility, not a claim that the model has read every source body. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CareerConsultationContextButton(
    context: JsonObject,
    stale: Boolean,
) {
    if (context.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val json = remember { Json { ignoreUnknownKeys = true } }
    val companies =
        remember(context, json) {
            runCatching {
                context["companies"]?.let {
                    json.decodeFromJsonElement<List<CareerCompanyRow>>(it)
                }
            }.getOrNull()
        }
    TextButton(
        onClick = { open = true },
        modifier = Modifier.testTag("career_consultation_context"),
        shapes = ButtonDefaults.shapes(),
    ) { Text("企業の参照状況", style = MaterialTheme.typography.labelMedium) }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LocalSpacing.current.md),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
            ) {
                Text("相談に使える企業の保存情報", style = MaterialTheme.typography.titleMedium)
                Text("本文は相談の内容に応じて参照します。接続済みでも、取得成功やサイト登録完了を保証しません。")
                if (stale) Text("接続が未確認のため、最終確認時の状態です。")
                context.text("checked_at")?.let { Text("確認日時 $it", style = MaterialTheme.typography.bodySmall) }
                if (companies == null) {
                    Text("企業の状態を読み取れません。接続後に更新してください。")
                } else if (companies.isEmpty()) {
                    Text("この相談の範囲では、保存済みの企業情報がありません。")
                } else {
                    Text("${companies.size}件を表示 · 対象 ${context.text("total") ?: "未確認"}件")
                    companies.forEach { company ->
                        HorizontalDivider()
                        Text(company.name, style = MaterialTheme.typography.titleSmall)
                        val readiness = company.mypage_readiness
                        Text(readiness?.label ?: "ログイン設定の状態は未確認")
                        readiness?.last_success_at?.let {
                            Text(
                                "マイページ最終取得成功 $it",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "メール：${collectionStateLabel(company.mail_status.text("state"))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        company.mail_status
                            .text(
                                "last_success_at",
                            )?.let { Text("メール最終取得成功 $it", style = MaterialTheme.typography.bodySmall) }
                        Text("次の対応：${company.next_action}")
                        if (readiness?.next_action != null) {
                            TextButton(
                                onClick = {
                                    open = false
                                    NavigationController.navigateTo(CareerCompanySettingsKey(company.id))
                                },
                                modifier = Modifier.testTag("consultation_company_settings_${company.id}"),
                                shapes = ButtonDefaults.shapes(),
                            ) { Text("ログイン設定を開く") }
                        }
                    }
                    if (context.text("next_offset") != null) {
                        Text("全件は表示していません。企業一覧で残りの企業を確認できます。")
                    }
                }
                TextButton(onClick = { open = false }, shapes = ButtonDefaults.shapes()) { Text("相談へ戻る") }
            }
        }
    }
}
