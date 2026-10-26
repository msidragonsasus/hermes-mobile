package com.m57.hermescontrol.career

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class CareerCompanyRow(
    val id: String,
    val name: String,
    val offer_count: Int = 0,
    val application_counts: Map<String, Int> = emptyMap(),
    val next_action: String = "企業情報を確認・相談",
    val deadline: String? = null,
    val new_count: Int = 0,
    val needs_action: Boolean = false,
    val collection_state: String = "not_started",
    val last_success_at: String? = null,
    val mypage_readiness: CareerMyPageReadiness? = null,
    val mail_status: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class CareerMyPageReadiness(
    val credential_state: String = "unknown",
    val credential_availability: String = "not_checked",
    val website_registration: String = "unknown",
    val login_state: String = "unknown",
    val collection_state: String = "not_started",
    val label: String = "ログイン設定の状態は未確認",
    val next_action: String? = null,
    val portal_url_configured: Boolean = false,
    val automation: String = "disabled",
    val last_attempt_at: String? = null,
    val last_success_at: String? = null,
    val reason: String? = null,
    val source: String = "unknown",
)

@Serializable
data class CareerCompanies(
    val api_version: Int = 1,
    val companies: List<CareerCompanyRow> = emptyList(),
    val checked_at: String = "",
    val order_version: Int? = null,
    val order_state: String? = null,
)

@Serializable
data class CareerCompanyOrder(
    val version: Int,
    val company_ids: List<String>,
)

@Serializable
data class CareerCompanyDetail(
    val api_version: Int = 1,
    val company: CareerEntity,
    val offers: List<CareerEntity> = emptyList(),
    val records: List<CareerEntity> = emptyList(),
    val links: List<CareerEntity> = emptyList(),
    val documents: List<CareerEntity> = emptyList(),
    val evidence: List<JsonObject> = emptyList(),
    val mail: List<CareerMailItem> = emptyList(),
    val mail_senders: List<CareerMailSenderRule> = emptyList(),
    val mail_status: JsonObject = JsonObject(emptyMap()),
    val connections: List<JsonObject> = emptyList(),
    val thread_id: String,
    val notice_cursor: Long = 0,
    val checked_at: String = "",
    val answer_states: JsonObject = JsonObject(emptyMap()),
    val es_workspace: Boolean = false,
    val current_offer_count: Int = 0,
    val mypage: CareerMyPage? = null,
    val mail_coverage: String = "取得範囲はサーバーから確認できていません。",
    val mail_next_cursor: String? = null,
)

@Serializable
data class CareerMyPage(
    val status: JsonObject = JsonObject(emptyMap()),
    val notices: List<JsonObject> = emptyList(),
    val history: List<JsonObject> = emptyList(),
    val supported: Boolean = false,
    val automation: String = "disabled",
    val coverage: String = "",
    val fetch_history: List<JsonObject> = emptyList(),
    val portal_url: String? = null,
    val bot_available: Boolean = false,
    val connection_version: Int = 0,
    val verified: Boolean = false,
    val next_run_at: String? = null,
    val schedule_error: String? = null,
    val scopes: List<JsonObject> = emptyList(),
    val coverage_state: String? = null,
    val interpretation_state: String? = null,
    val collection_state: String? = null,
    val analysis_state: String? = null,
    val analysis_resumable: Boolean = false,
    val unconfirmed: List<JsonObject> = emptyList(),
    val listing_proposal: JsonObject? = null,
    /** Coach-requested URL/on-off awaiting the person's login entry in this form. Never holds credentials. */
    val pending_request: JsonObject? = null,
    val listing_approved: Boolean = false,
    val listing_relearn: Boolean = false,
    val listing_relearn_allowed: Boolean? = null,
    val listing_relearn_block_reason: String? = null,
    val listing_retry_state: String? = null,
    val listing_retry_after: String? = null,
    val listing_proposal_approval_allowed: Boolean? = null,
    val listing_unresolved: JsonObject? = null,
    val new_entrance_count: Int = 0,
)

@Serializable
data class CareerMailItem(
    val message_id: String,
    val thread_id: String,
    val sender_address: String,
    val from_text: String,
    val subject: String,
    val snippet: String,
    val body: String,
    val locator: String,
    val received_at: String,
    val gmail_unread: Boolean = false,
    val first_observed_at: String = "",
    val observed_at: String = "",
    val account_id: String = "",
)

@Serializable
data class CareerMailSenderRule(
    val id: String,
    val version: Int,
    val company_id: String,
    val address: String,
    val enabled: Boolean = true,
    val source: String = "Hermes",
    val updated_at: String = "",
)

@Serializable
data class CareerWorkspaceAccount(
    val id: String,
    val email: String,
    val provider: String = "",
    val read_surface: String = "",
    val label: String = "",
    val state: String = "connected",
)

@Serializable
data class CareerConnections(
    val accounts: List<String> = emptyList(),
    val account_details: List<CareerWorkspaceAccount> = emptyList(),
    val independent_channels: Boolean = false,
    val mail: JsonObject = JsonObject(emptyMap()),
    val mail_status: String? = null,
    val mail_senders: List<CareerMailSenderRule> = emptyList(),
)

@Serializable
data class CareerClientMailMessage(
    val id: String,
    val thread_id: String,
    val sender_address: String,
    val from_text: String,
    val subject: String,
    val snippet: String,
    val body: String,
    val locator: String,
    val received_at: String,
    val gmail_unread: Boolean,
)

@Serializable
data class CareerClientMailImport(
    val account_email: String,
    val messages: List<CareerClientMailMessage>,
    val company_id: String? = null,
)

@Serializable
data class CareerMailCacheRequest(
    val account_email: String,
    val company_id: String,
    val message_ids: List<String>,
)

@Serializable
data class CareerMailCacheResult(
    val cached_ids: List<String> = emptyList(),
)

@Serializable
data class CareerMailPage(
    val messages: List<CareerMailItem>,
    val next_cursor: String? = null,
)

@Serializable
data class CareerNotice(
    val seq: Long,
    val id: String,
    val company_id: String,
    val offer_id: String? = null,
    val kind: String,
    val title: String,
    val created_at: String,
    val eligible: Boolean = false,
    val company_name: String = "",
    val category: String? = null,
    val label: String? = null,
    val quote: String = "",
    val deadline_text: String = "",
    val source_ref: CareerNoticeSource? = null,
)

@Serializable
data class CareerNoticeSource(
    val kind: String,
    val id: String,
    val key: String? = null,
)

@Serializable
data class CareerNoticeFeed(
    val notices: List<CareerNotice> = emptyList(),
    val cursor: Long = 0,
    val more: Boolean = false,
)
