package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.m57.hermescontrol.theme.LocalSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject

internal data class CareerMailProgress(
    val view: JsonObject? = null,
    val error: String? = null,
)

/** Poll saved progress only; this endpoint never calls Gmail. */
@Composable
internal fun rememberMailProgress(scope: String): State<CareerMailProgress> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(CareerMailProgress(), scope, lifecycle) {
        val repository = CareerCompanyRepository(scope)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value =
                    try {
                        CareerMailProgress(repository.mailStatus())
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        value.copy(error = "同期状態を確認できません。表示は前回確認時のものです。")
                    }
                delay(5_000)
            }
        }
    }
}

@Composable
internal fun CareerMailSyncStatus(
    status: JsonObject?,
    cachedCount: Int? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(LocalSpacing.current.md),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
        ) {
            Text(
                when {
                    status == null -> "同期状態を確認しています…"
                    status.text("state") == "disabled" -> "自動同期は停止中です。保存済みメールは閲覧できます。"
                    status.text("state") == "no_senders" -> "企業の設定で送信元を登録すると、自動取得を開始します。"
                    status.text("fresh") == "true" -> "直近分の取得完了・最新の差分を確認済み"
                    status.text("recent_complete") == "true" -> "直近分の取得完了・最新の差分は未確認"
                    else -> "直近分を取得中です。保存できたメールから表示します。"
                },
            )
            cachedCount?.let { Text("保存済み $it 件") }
            status?.text("synced_through_at")?.let {
                Text("差分の確認時刻 ${careerDisplayTime(it)}", style = MaterialTheme.typography.bodySmall)
            }
            if (status?.text("state") !in setOf(null, "disabled", "no_senders")) {
                Text(
                    if (status?.text("backfill_complete") == "true") {
                        "過去分の取得完了"
                    } else {
                        "過去分は自動で継続取得します（${status?.text("backfill_groups_done") ?: "0"} / " +
                            "${status?.text("backfill_groups_total") ?: "0"} グループ完了）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            status?.text("reason")?.let { reason ->
                Text(
                    when (reason) {
                        in
                        setOf(
                            "authentication_required",
                            "mail_scope_unsupported",
                            "mail_domain_policy",
                            "workspace_permission_denied",
                        ),
                        -> {
                            "サーバーのGoogle接続・読み取り権限を確認してください。"
                        }

                        "mail_rate_limited", "mail_daily_limit" -> {
                            "Gmailの取得制限に合わせて待機しています。"
                        }

                        else -> {
                            "同期を完了できませんでした。保存済みメールを表示し、自動で再試行します。"
                        }
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            }
            status?.text("retry_at")?.let { Text("再試行予定 ${careerDisplayTime(it)}") }
        }
    }
}
