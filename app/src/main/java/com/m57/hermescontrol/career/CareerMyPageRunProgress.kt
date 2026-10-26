package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.m57.hermescontrol.theme.LocalSpacing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun myPagePhaseLabel(phase: String?): String? =
    when (phase) {
        "queued", "waiting" -> "順番待ち"
        "startup", "starting" -> "ブラウザを準備中"
        "auth", "authenticating" -> "ログイン状態を確認中"
        "learning", "discovering" -> "確認する一覧を調べています"
        "collection", "collecting", "fetch", "fetching" -> "本文を取得中"
        "analysis", "analyzing", "interpretation" -> "取得した本文を整理中"
        "saving" -> "結果を保存中"
        "completed", "complete" -> "今回の範囲を確認済み"
        "partial", "paused" -> "未確認または解析待ちの箇所があります"
        "analysis_pending", "pending_analysis" -> "本文は保存済み・解析待ち"
        else -> null
    }

internal fun myPageProgressCounts(status: JsonObject): String? =
    listOf(
        "confirmed_count" to "本文確認",
        "listing_only_count" to "一覧のみ",
        "unconfirmed_count" to "未確認",
        "pending_analysis_count" to "解析待ち",
    ).mapNotNull { (key, label) ->
        status
            .text(key)
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
            ?.let { "$label $it 件" }
    }.takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")

internal fun myPageScopeLines(mypage: CareerMyPage): List<String> =
    mypage.scopes.map { scope ->
        val label = scope.text("label").orEmpty().ifBlank { "確認範囲" }
        val state =
            when (scope.text("state")) {
                "complete" -> "確認済み"
                "empty" -> "案内がないことを確認済み"
                "partial", "failed" -> "未確認の箇所があります"
                "queued", "running" -> "確認中"
                else -> "確認状況は未確認です"
            }
        "$label：$state"
    }

internal fun myPageUnconfirmedReason(reason: String?): String =
    when (reason) {
        "user_action_required", "ambiguous_action", "unsafe_action", "view_operation_unverified" -> {
            "本人の操作が必要です"
        }

        "unsupported_attachment", "attachment_unsupported" -> {
            "添付内容は未確認です"
        }

        "pagination_incomplete", "pagination_unresolved", "pagination_cycle", "pagination_limit" -> {
            "続きのページは未確認です"
        }

        "structure_changed", "plan_stale" -> {
            "一覧の構造が変わり、手順の再確認が必要です"
        }

        "listing_identity_ambiguous" -> {
            "同名の案内を区別できませんでした"
        }

        "page_limit", "collection_limit", "time_limit" -> {
            "取得上限に達しました"
        }

        "detail_loading", "detail_unread", "detail_unconfirmed", "detail_not_loaded", "detail_placeholder",
        "detail_incomplete", "detail_not_ready",
        -> {
            "詳細本文の表示完了を確認できませんでした"
        }

        "detail_empty", "detail_missing", "detail_not_found" -> {
            "詳細本文を取得できませんでした"
        }

        "detail_scope_ambiguous" -> {
            "詳細本文の確認範囲を区別できませんでした"
        }

        else -> {
            "詳細を確認できていません"
        }
    }

@Composable
internal fun MyPageRunProgress(mypage: CareerMyPage?) {
    val connection = mypage ?: return
    var scopesOpen by remember(connection.portal_url) { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
        modifier = Modifier.testTag("mypage_run_progress"),
    ) {
        myPagePhaseLabel(connection.status.text("phase"))?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        myPageProgressCounts(connection.status)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (connection.scopes.isNotEmpty() || connection.unconfirmed.isNotEmpty()) {
            TextButton(
                onClick = { scopesOpen = !scopesOpen },
                modifier = Modifier.testTag("mypage_scope_details"),
            ) { Text(if (scopesOpen) "確認範囲を閉じる" else "確認範囲・未確認の箇所を見る") }
            if (scopesOpen) {
                myPageScopeLines(connection).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                connection.unconfirmed.forEach { candidate ->
                    val label = candidate.text("label").orEmpty().ifBlank { "案内" }
                    Text(
                        "未確認：$label · ${myPageUnconfirmedReason(candidate.text("reason"))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

internal fun myPageHistoryProgress(run: JsonObject): JsonObject? =
    when (val result = run["result"]) {
        is JsonObject -> {
            result
        }

        is JsonPrimitive -> {
            try {
                if (result.content.length <= 128000) Json.parseToJsonElement(result.content) as? JsonObject else null
            } catch (_: Exception) {
                null
            }
        }

        else -> {
            null
        }
    }

internal fun myPageHistoryStateLabel(state: String?): String =
    when (state) {
        "completed", "updated", "unchanged" -> "確認済み"
        "partial" -> "一部確認"
        "queued" -> "順番待ち"
        "running" -> "確認中"
        "auth_required" -> "認証が必要"
        "cancelled" -> "中止"
        "failed" -> "確認失敗"
        else -> "状態未確認"
    }

@Composable
internal fun MyPageRunHistory(mypage: CareerMyPage?) {
    val connection = mypage ?: return
    if (connection.fetch_history.isEmpty()) return
    var open by remember(connection.portal_url) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.testTag("mypage_run_history")) {
        Text(if (open) "確認履歴を閉じる" else "確認履歴を見る")
    }
    if (open) {
        Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
            connection.fetch_history.take(5).forEach { run ->
                val result = myPageHistoryProgress(run)
                Text(
                    "${careerDisplayTime(
                        run.text("created_at").orEmpty(),
                    )} · ${myPageHistoryStateLabel(run.text("state"))}",
                    style = MaterialTheme.typography.titleSmall,
                )
                result?.let {
                    myPagePhaseLabel(it.text("phase"))?.let { phase ->
                        Text(phase, style = MaterialTheme.typography.bodySmall)
                    }
                    myPageProgressCounts(it)?.let { counts -> Text(counts, style = MaterialTheme.typography.bodySmall) }
                }
                companyCollectionReason(result?.text("reason") ?: run.text("reason"))?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
