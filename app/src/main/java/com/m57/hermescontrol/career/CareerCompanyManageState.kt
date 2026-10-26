package com.m57.hermescontrol.career

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

enum class CompanyWritePhase { IDLE, SAVING, SAVED, FAILED, CONFLICT, UNKNOWN }

data class CompanyWriteState(
    val phase: CompanyWritePhase = CompanyWritePhase.IDLE,
    val message: String? = null,
    val requestVersion: Int? = null,
    val address: String? = null,
    val requestId: String = "",
)

/** Owned above the pager so an off-screen page cannot discard an edit. */
@Stable
internal class CompanyManageDraft {
    var priority by mutableStateOf("unknown")
    var stage by mutableStateOf("unknown")
    var originalPriority by mutableStateOf("unknown")
    var originalStage by mutableStateOf("unknown")
    var version by mutableStateOf(-1)
    var sender by mutableStateOf("")
    var consumedSenderRequest by mutableStateOf("")
    val dirty: Boolean get() = version >= 0 && (priority != originalPriority || stage != originalStage)

    fun select(
        company: CareerEntity,
        newPriority: String? = null,
        newStage: String? = null,
    ) {
        if (!dirty) {
            originalPriority = company.data.text("user_priority") ?: "unknown"
            originalStage = company.data.text("user_stage") ?: "unknown"
            priority = originalPriority
            stage = originalStage
            version = company.version
        }
        newPriority?.let { priority = it }
        newStage?.let { stage = it }
    }

    fun reset() {
        version = -1
    }

    companion object {
        val Saver =
            listSaver<CompanyManageDraft, Any>(
                save = {
                    listOf(
                        it.priority,
                        it.stage,
                        it.originalPriority,
                        it.originalStage,
                        it.version,
                        it.sender,
                        it.consumedSenderRequest,
                    )
                },
                restore = {
                    CompanyManageDraft().apply {
                        priority = it[0] as String
                        stage = it[1] as String
                        originalPriority = it[2] as String
                        originalStage = it[3] as String
                        version = it[4] as Int
                        sender = it[5] as String
                        consumedSenderRequest = it[6] as String
                    }
                },
            )
    }
}

internal fun validCompanySender(value: String): Boolean {
    val address = value.trim()
    return address.length <= 320 && Regex("[^\\s@<>;,\"]{1,64}@[^\\s@<>;,\"]{1,253}").matches(address)
}

internal fun companyMailConnectionLabel(state: CareerCompanyState): String =
    when {
        state.connectionError != null -> {
            "サーバーの接続状態を確認できません。"
        }

        state.connections == null -> {
            if (state.loading) "接続状態を確認中…" else "接続状態は未確認です。"
        }

        state.connections.mail_status == "ready" -> {
            if (state.connections.mail.text("enabled") == "true") "自動取得は有効です。" else "自動取得は停止中です。"
        }

        state.connections.mail_status in setOf("authentication_required", "auth_required") -> {
            "サーバーの自動取得にはGoogle認証の確認が必要です。"
        }

        state.connections.mail_status == "mail_scope_unsupported" -> {
            "サーバーにGmailの読み取り許可が必要です。"
        }

        state.connections.mail_status == "mail_account_unavailable" -> {
            "サーバーのGoogleアカウントを確認してください。"
        }

        state.connections.mail_status in
            setOf(
                "workspace_runtime_unavailable",
                "workspace_skill_unavailable",
                "workspace_dependency_unavailable",
            ) -> {
            "サーバーのメール取得機能を確認できません。"
        }

        else -> {
            "サーバーの接続状態を確認できません。接続設定を確認してください。"
        }
    }

internal fun companyCollectionReason(reason: String?): String? =
    when (reason) {
        null, "" -> {
            null
        }

        "authentication_required", "auth_required", "authentication_unverified", "mypage_login_form_ambiguous" -> {
            "認証の確認が必要です。"
        }

        "collection_interrupted" -> {
            "取得が中断されました。"
        }

        "mail_scope_unsupported" -> {
            "Gmailの読み取り許可が必要です。"
        }

        "mail_collection_disabled" -> {
            "自動取得は停止中です。"
        }

        "no_senders" -> {
            "取得する送信元が登録されていません。"
        }

        "mypage_not_connected" -> {
            "この企業の案内取得は未設定、または未対応です。"
        }

        "mypage_bot_not_ready", "hermes_runtime_missing", "hermes_contract_unsupported" -> {
            "サーバーのマイページ確認Botの準備が必要です。"
        }

        "mypage_execution_failed" -> {
            "確認処理でエラーが発生しました。サーバーの実行履歴を確認してください。保存済み情報は保持しています。"
        }

        "authentication_challenge", "additional_authentication_required" -> {
            "本人による追加認証が必要なため、自動確認を停止しています。"
        }

        "portal_structure_changed", "portal_origin_changed" -> {
            "ログイン先またはページの構造を確認できませんでした。前回の情報は保持しています。"
        }

        "browser_runtime_missing" -> {
            "Hermesのブラウザ実行環境が不足しています。サーバーの設定を確認してください。"
        }

        "browser_unavailable", "browser_operation_failed", "browser_session_ended" -> {
            "ブラウザでの確認が中断しました。取得済み情報は保持しています。"
        }

        "mypage_timezone_mismatch" -> {
            "サーバーのBotを日本時間に設定してください。"
        }

        "company_closed" -> {
            "終了した企業の巡回は停止しています。管理から選考状況を確認してください。"
        }

        "quota_paused" -> {
            "AIの利用上限で確認が中断しました。保存済み情報は保持しています。"
        }

        "collection_incomplete", "collection_limit" -> {
            "確認できていないページがあります。取得できた情報と前回の情報を表示しています。"
        }

        "paid_provider_blocked", "paid_not_enabled", "paid_fallback_blocked",
        "paid_budget_required", "paid_browser_blocked",
        -> {
            "AIの利用条件・料金上限を確認してください。有料への自動切替は行いません。"
        }

        "mypage_execution_unset", "model_required", "mypage_model_unset", "model_not_configured" -> {
            "マイページ確認用のAIが未設定です。AIの接続・モデル設定を確認してください。"
        }

        "budget_exceeded", "daily_budget_exceeded", "run_budget_exceeded", "cost_limit" -> {
            "料金上限に達したため解析が停止しました。取得状況と保存済みの案内は確認履歴で確認できます。"
        }

        "pricing_unverified", "price_unknown", "unknown_pricing" -> {
            "API単価を確認できず解析を開始できませんでした。料金を確認済みのAI接続を選択してください。"
        }

        "model_auth_required", "model_auth_failed", "model_authentication_required", "provider_unavailable",
        "model_catalog_unavailable", "model_catalog_empty", "oauth_unavailable",
        -> {
            "AIの接続・認証を確認できませんでした。保存済みの案内は保持しています。"
        }

        "private_content_policy_unverified", "private_content_forbidden", "mypage_private_model_required" -> {
            "このAI接続へマイページの個別情報を送信できないため、解析を停止しました。AIの利用条件を確認してください。"
        }

        "invalid_execution", "model_unsupported", "reasoning_unsupported", "output_limit_required" -> {
            "選択されたAIの実行条件を確認できませんでした。モデル設定を確認してください。"
        }

        "invalid_budget" -> {
            "AIの料金上限の設定を確認してください。"
        }

        "analysis_failed", "analysis_pending", "analysis_paused", "analysis_invalid", "analysis_incomplete",
        "analysis_unresolved", "request_accounting_unavailable",
        -> {
            "取得した本文の解析が完了していません。保存済みの案内と未確認の箇所は確認履歴で確認できます。"
        }

        "user_action_required", "ambiguous_action", "unsafe_action", "view_operation_unverified" -> {
            "本人の操作または確認が必要なページがあります。保存済みの案内と確認範囲を確認してください。"
        }

        "pagination_incomplete", "pagination_unresolved", "pagination_cycle", "pagination_limit" -> {
            "続きのページを確認できませんでした。取得できた案内は保持しています。"
        }

        "listing_identity_ambiguous" -> {
            "同名の案内を区別できず、一部の案内が未確認です。確認範囲を確認してください。"
        }

        "detail_unread", "detail_unconfirmed", "detail_incomplete", "detail_not_ready", "detail_scope_ambiguous",
        "listing_unread", "listing_incomplete",
        -> {
            "一部の案内の本文を確認できませんでした。保存済みの案内と確認範囲を確認してください。"
        }

        "unsupported_attachment", "attachment_unsupported" -> {
            "添付内容は未確認です。マイページの原文を確認してください。"
        }

        "page_limit", "time_limit" -> {
            "取得上限に達しました。取得できた案内は保持しています。"
        }

        "connection_changed", "version_conflict", "listing_proposal_changed", "analysis_changed" -> {
            "接続設定または確認内容が変更されています。保存情報を読み直して確認してください。"
        }

        "collection_running", "collection_already_started", "cron_configuration_busy" -> {
            "確認は既に進行中です。確認履歴で最新の状態を確認できます。"
        }

        "collection_cancelled", "run_interrupted" -> {
            "今回の確認は中止されました。保存済みの案内は保持しています。"
        }

        "company_missing", "run_missing" -> {
            "対象の企業または確認履歴が見つかりません。保存情報を読み直してください。"
        }

        "credentials_required_for_new_url", "invalid_portal_url", "unsafe_url" -> {
            "マイページのURLと取得用のログイン情報を確認してください。"
        }

        "portal_not_open", "portal_unverified", "invalid_portal_link", "mypage_http_failure" -> {
            "取得先のページを確認できませんでした。保存済みの案内は保持しています。"
        }

        "mypage_bot_required", "mypage_profile_required", "mypage_skill_missing", "cron_binding_mismatch",
        "native_browser_policy_missing",
        -> {
            "サーバーのマイページ確認の設定を確認してください。保存済みの案内は保持しています。"
        }

        "evidence_mismatch", "empty_not_verified", "execution_mismatch", "execution_snapshot_missing",
        "invalid_collection_route", "invalid_browser_action", "invalid_request", "unknown_tool", "scan_scope_missing",
        "mypage_target_unsafe", "mypage_view_unverified", "portal_action_blocked",
        -> {
            "確認処理の整合性を確認できず停止しました。保存済みの案内と確認履歴を確認してください。"
        }

        "mypage_session_invalid" -> {
            "マイページのログイン状態を確認できませんでした。再度確認してください。"
        }

        "analysis_not_pending" -> {
            "解析待ちの本文はありません。確認履歴で最新の状態を確認できます。"
        }

        "collection_finished" -> {
            "今回の確認は終了しています。確認履歴で結果を確認できます。"
        }

        "collection_observation_failed" -> {
            "確認途中の取得状況を保存できず停止しました。保存済みの案内は保持しています。"
        }

        "credential_cleanup_failed" -> {
            "確認処理の後始末を確認できず停止しました。サーバーの確認環境を確認してください。"
        }

        "listing_approval_required" -> {
            "確認する一覧の承認待ちです。Botの提案を確認してください。"
        }

        "mypage_recipe_invalid", "mypage_recipe_unobserved", "scan_plan_invalid" -> {
            "確認する一覧の設定を使えませんでした。確認対象の設定と承認状態を確認してください。"
        }

        "listing_unresolved" -> {
            "確認できなかった一覧の候補があります。未確認の候補とページは、確認対象に含まれていません。"
        }

        "approved_listings_protected" -> {
            "承認済み一覧の学習し直しは現在利用できません。今の一覧の確認は続けます。"
        }

        "listing_retry_stopped" -> {
            "一覧の探索が繰り返し失敗したため、自動再試行を停止しました。原因を確認してから学習し直してください。"
        }

        "list_unsettled" -> {
            "一覧の表示が安定せず、今回の内容を確認できませんでした。保存済みの案内は保持しています。"
        }

        "listing_controls_changed" -> {
            "一覧に新しいリンクや操作が現れ、最後まで確認できたか確かめられません。確認対象の設定を見直す必要があります。"
        }

        "listing_decision_changed" -> {
            "提案の作成中に承認状態が変わったため、提案を保存しませんでした。次回の確認で提案し直します。"
        }

        "listing_candidates_not_found" -> {
            "マイページに一覧が見つかりませんでした。ログインURLが一覧のある画面か確認してください。"
        }

        "structure_changed" -> {
            "一部の一覧の形が変わり、確認できませんでした。他の一覧の情報は保存済みです。"
        }

        else -> {
            "確認の詳細を表示できません。保存済みの情報は保持しています。確認履歴を確認してください。"
        }
    }

internal fun companyCollectionState(value: String?): String =
    when (value) {
        "not_started" -> {
            "未取得"
        }

        "configured" -> {
            "接続確認前"
        }

        "queued" -> {
            "確認待ち"
        }

        "auth_required" -> {
            "認証の確認が必要"
        }

        "updated", "unchanged", "available", "partial", "running", "no_senders", "disabled", "failed" -> {
            collectionStateLabel(value)
        }

        else -> {
            "状態を確認できません"
        }
    }
