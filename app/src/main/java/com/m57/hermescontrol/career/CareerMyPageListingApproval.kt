package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.m57.hermescontrol.theme.LocalSpacing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One listing the Bot proposes to check. The server never sends selectors to the app. */
internal data class MyPageProposedListing(
    val label: String,
    val count: Int?,
    val samples: List<String>,
    val emptyText: String?,
    val paginated: Boolean,
    val trialState: String? = null,
    val trialReason: String? = null,
)

internal fun myPageProposedListings(proposal: JsonObject?): List<MyPageProposedListing> =
    (proposal?.get("listings") as? JsonArray).orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        MyPageProposedListing(
            label = item.text("label") ?: return@mapNotNull null,
            count = item.text("count")?.toIntOrNull(),
            samples =
                (item["samples"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                    .take(3),
            emptyText = item.text("empty_text"),
            paginated = item.text("paginated") == "true",
            trialState = item.text("trial_state"),
            trialReason = item.text("trial_reason"),
        )
    }

internal fun myPageListingSummary(listing: MyPageProposedListing): String =
    when {
        listing.trialState == "partial" -> {
            "${listing.count?.let { "${it}件取得・" }.orEmpty()}試験は途中まで（${myPageTrialReason(listing.trialReason)}）"
        }

        listing.count == 0 && listing.emptyText != null -> {
            "現在は空（「${listing.emptyText}」と表示）"
        }

        listing.count != null -> {
            "${listing.count}件" +
                if (listing.trialState !in setOf("complete", "empty")) {
                    "（試験の完了は未確認）"
                } else if (listing.paginated) {
                    "・次のページ以降も確認"
                } else {
                    ""
                }
        }

        else -> {
            "件数は未確認"
        }
    }

internal fun myPageTrialReason(reason: String?): String =
    when (reason) {
        "pagination_limit" -> "確認できるページ数の上限"
        "collection_limit" -> "取得時間の上限"
        "detail_unread" -> "詳細が未確認"
        else -> "最後まで確認できませんでした"
    }

internal fun myPageCanRelearn(connection: CareerMyPage): Boolean =
    connection.listing_relearn_allowed ?: !connection.listing_approved

internal fun myPageCanApproveProposal(connection: CareerMyPage): Boolean =
    connection.listing_proposal_approval_allowed ?: !connection.listing_approved

internal fun myPageRelearnPendingNotice(connection: CareerMyPage): String? =
    if (!connection.listing_relearn || !myPageCanRelearn(connection) ||
        connection.listing_retry_state in setOf("blocked", "stopped")
    ) {
        null
    } else if (connection.listing_retry_state == "backoff") {
        "一覧の探索が一時的に失敗し、再試行を待っています。" +
            (connection.listing_retry_after?.let { "${careerDisplayTime(it)}以降に再試行します。" } ?: "待機期間の後に再試行します。")
    } else {
        "次回の確認で一覧を学習し直します。新しい提案を承認するまで、今の一覧で確認します。"
    }

internal fun myPageRelearnBlockedNotice(connection: CareerMyPage): String? =
    if (!myPageCanRelearn(connection) && connection.listing_approved) {
        "承認済み一覧の学習し直しは現在利用できません。今の一覧の確認は続けます。" +
            "構造が変わった一覧は、設定の修正が必要です。"
    } else {
        null
    }

internal fun myPageEntranceNotice(
    count: Int,
    canRelearn: Boolean = true,
): String? =
    if (count > 0) {
        "マイページに新しい入口が${count}件見つかりました。" +
            if (canRelearn) {
                "確認対象に加える場合は一覧を学習し直してください。承認済みの一覧の確認は完了扱いのままです。"
            } else {
                "確認対象の追加は現在利用できません。今の一覧の確認は続けます。"
            }
    } else {
        null
    }

/**
 * Candidates the Bot could not confirm and pages it did not review. They are not checked,
 * so the count stays visible, also after approval, until a relearn resolves them.
 */
internal fun myPageUnresolvedNotice(
    unresolved: JsonObject?,
    pending: Boolean,
): String? {
    val candidates = unresolved?.text("candidates")?.toIntOrNull() ?: 0
    val pages = unresolved?.text("unreviewed_pages")?.toIntOrNull() ?: 0
    val parts =
        listOfNotNull(
            "確認できなかった候補 ${candidates}件".takeIf { candidates > 0 },
            "未確認のページ ${pages}件".takeIf { pages > 0 },
        )
    if (parts.isEmpty()) return null
    return parts.joinToString("・") + if (pending) "（承認しても、これらは確認しません）" else "（これらは確認していません）"
}

/** A pending proposal, a scheduled relearn, unresolved candidates, or something the approved listings no longer cover. */
internal fun myPageListingSectionVisible(connection: CareerMyPage): Boolean {
    val reasons = setOf("structure_changed", "rows_appeared", "list_unsettled", "listing_controls_changed")
    val needsRelearn =
        connection.new_entrance_count > 0 || connection.status.text("reason") in reasons ||
            connection.scopes.any { it.text("reason") in reasons }
    return connection.listing_proposal != null || connection.listing_relearn ||
        myPageUnresolvedNotice(connection.listing_unresolved, false) != null ||
        (connection.listing_approved && needsRelearn)
}

/**
 * Approval section for the listings Hermes Bot will read (decision D1), and the
 * relearn entry for new entrances (D2). Approve once / reject only; no standing permission.
 */
@Composable
internal fun CompanyMyPageListingApproval(
    connection: CareerMyPage,
    companyName: String,
    busy: Boolean,
    onDecision: (decision: String, proposalId: String?) -> Unit,
) {
    if (!myPageListingSectionVisible(connection)) return
    val proposal = connection.listing_proposal
    val canRelearn = myPageCanRelearn(connection)
    val canApprove = myPageCanApproveProposal(connection)
    val spacing = LocalSpacing.current
    val secondaryText = MaterialTheme.colorScheme.onSurfaceVariant
    // A section of the MyPage card, not a card of its own; the caller places the divider.
    Column(
        Modifier.fillMaxWidth().testTag("mypage_listing_approval"),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (proposal != null) {
            Text("確認する一覧の承認", style = MaterialTheme.typography.titleSmall)
            Text(
                "Hermes Botが毎日確認する、${companyName}のマイページの一覧です。" +
                    if (connection.listing_approved) "今の承認済み一覧は引き続き確認します。" else "承認するまで案内は取得しません。",
            )
            if (!canApprove) {
                Text("この提案で承認済み一覧を変更する操作は現在利用できません。", color = secondaryText)
            }
            myPageProposedListings(proposal).forEach { listing ->
                Column(Modifier.testTag("mypage_proposed_listing")) {
                    Text("${listing.label}　${myPageListingSummary(listing)}")
                    listing.samples.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = secondaryText, maxLines = 1)
                    }
                }
            }
            myPageUnresolvedNotice(connection.listing_unresolved, true)?.let {
                Text(it, modifier = Modifier.testTag("mypage_listing_unresolved"))
            }
            Column {
                listOf(
                    "閲覧のみです。応募・予約・送信はしません",
                    "権限：保存済みのログインで閲覧",
                    if (connection.listing_approved) {
                        "却下しても、承認済み一覧の設定と履歴は保持します"
                    } else {
                        "却下すると初回提案をやり直します。承認後の学習し直しは現在利用できません"
                    },
                    "個別の修正はできません",
                    "提案：${proposal.text("proposed_at")?.let { careerDisplayTime(it) } ?: "日時不明"}　期限：なし",
                ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = secondaryText) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End)) {
                TextButton(
                    enabled = !busy,
                    onClick = { onDecision("reject", proposal.text("id")) },
                    modifier = Modifier.testTag("mypage_listing_reject"),
                    shapes = ButtonDefaults.shapes(),
                ) { Text("却下") }
                Button(
                    enabled = !busy && canApprove,
                    onClick = { onDecision("approve", proposal.text("id")) },
                    modifier = Modifier.testTag("mypage_listing_approve"),
                    shapes = ButtonDefaults.shapes(),
                ) { Text("承認") }
            }
        } else if (myPageRelearnPendingNotice(connection) != null) {
            Text(
                myPageRelearnPendingNotice(connection)!!,
                color = secondaryText,
            )
        } else {
            myPageUnresolvedNotice(connection.listing_unresolved, false)?.let {
                Text(it, modifier = Modifier.testTag("mypage_listing_unresolved"))
            }
            myPageEntranceNotice(connection.new_entrance_count, canRelearn)?.let {
                Text(it, modifier = Modifier.testTag("mypage_new_entrance"))
            }
            myPageRelearnBlockedNotice(connection)?.let {
                Text(it, modifier = Modifier.testTag("mypage_relearn_blocked"), color = secondaryText)
            }
            if (canRelearn) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FilledTonalButton(
                        enabled = !busy,
                        onClick = { onDecision("relearn", null) },
                        modifier = Modifier.testTag("mypage_listing_relearn"),
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("一覧を学習し直す") }
                }
            }
        }
    }
}
