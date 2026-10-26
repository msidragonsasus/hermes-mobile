package com.m57.hermescontrol.career

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.serialization.json.JsonObject
import retrofit2.HttpException

interface CareerRepository {
    suspend fun snapshot(): CareerSnapshot

    suspend fun mutate(
        action: String,
        payload: JsonObject,
    ): JsonObject

    suspend fun document(
        id: String,
        version: Int,
    ): JsonObject

    suspend fun migrationPreview(): JsonObject
}

/** Reuses the authenticated Dashboard service, cookie jar and host policy. */
class DashboardCareerRepository(
    private val scope: String = careerScope(),
) : CareerRepository {
    private val api = ApiClient.hermesApi

    private fun checkScope() {
        check(scope == careerScope()) { "接続先が変わりました。就活Appを開き直してください。" }
        check(AuthManager.isGatedMode()) { "就活AppにはDashboardへのログインが必要です。" }
    }

    override suspend fun snapshot(): CareerSnapshot {
        checkScope()
        val result = api.careerSnapshot()
        checkScope()
        check(result.api_version == 1) { "未対応の就活プラグインです。" }
        return result
    }

    override suspend fun mutate(
        action: String,
        payload: JsonObject,
    ): JsonObject {
        checkScope()
        val result = api.careerMutation(action, payload)
        checkScope()
        return result
    }

    override suspend fun document(
        id: String,
        version: Int,
    ): JsonObject {
        checkScope()
        return api.careerDocument(id, version).also { checkScope() }
    }

    override suspend fun migrationPreview(): JsonObject {
        checkScope()
        return api.careerMigrationPreview().also { checkScope() }
    }
}

internal fun careerPreparationReason(code: String?): String? =
    when (code) {
        "oauth_unavailable" -> "サーバーのCodex OAuthに再ログインしてください。"
        "provider_unavailable" -> "選択したプロバイダーに接続できません。Hermesの接続設定を確認してください。"
        "model_not_configured" -> "モデルと推論強度を選択してください。"
        "model_catalog_empty", "model_catalog_unavailable" -> "モデル一覧を取得できません。接続を確認して候補を再読込してください。"
        "model_unsupported", "reasoning_unsupported" -> "モデルまたは推論強度に対応していません。候補を確認してください。"
        "quota_paused" -> "選択したプロバイダーの利用上限で中断しました。利用可能になってから続行できます。"
        "hermes_contract_unsupported", "execution_mismatch" -> "実行時のモデル設定を照合できません。サーバーの対応状況を確認してください。"
        "paid_fallback_blocked" -> "Codex OAuthの接続を確認してください。別の接続では実行しません。"
        "execution_confirmation_required" -> "以前の作業です。モデルと推論強度を確認して続行してください。"
        "document_version_conflict" -> "文書が更新されています。表示中の版と最新版を確認してください。"
        else -> null
    }

internal fun careerErrorCode(error: HttpException): String? =
    runCatching {
        val body =
            error
                .response()
                ?.errorBody()
                ?.string()
                ?.take(16384) ?: return@runCatching null
        val root =
            kotlinx.serialization.json.Json
                .parseToJsonElement(body) as? JsonObject
        (root?.get("detail") as? JsonObject)?.text("code")
    }.getOrNull()

fun careerCapabilityError(
    error: Throwable,
    conversationAvailable: Boolean,
): String =
    if (error is HttpException && error.code() == 404 && conversationAvailable) {
        "就活サーバーは旧版です。モデル選択に対応する版へ更新してください。会話と入力は保持しています。"
    } else {
        careerError(error)
    }

fun careerError(error: Throwable): String =
    when (error) {
        is HttpException -> {
            val message =
                when (careerErrorCode(error)) {
                    "unapproved_fields" -> "保存内容を確認できません。入力を保持しています。設定から保存状況を確認してください。"
                    "unsupported_import" -> "保存内容を確認できません。入力を保持しています。設定から保存状況を確認してください。"
                    "invalid_import", "invalid_source" -> "保存内容を確認できません。入力を保持しています。設定から保存状況を確認してください。"
                    "invalid_mail_sender" -> "保存内容を確認できません。入力を保持しています。設定から保存状況を確認してください。"
                    "payload_too_large", "invalid_data" -> "保存内容を確認できません。入力を保持しています。設定から保存状況を確認してください。"
                    "oauth_unavailable" -> "サーバーのCodex OAuthに再ログインしてください。"
                    "provider_unavailable" -> "選択したプロバイダーに接続できません。Hermesの接続設定を確認してください。"
                    "dashboard_session_required" -> "再ログインが必要です。接続設定からログインを確認してください。"
                    "model_not_configured", "invalid_execution" -> "モデルと推論強度を選択してください。"
                    "model_unsupported", "reasoning_unsupported" -> "このモデル・推論強度には対応していません。候補を更新して選び直してください。"
                    "model_catalog_empty", "model_catalog_unavailable" -> "モデル一覧を取得できません。接続を確認して候補を再読込してください。"
                    "execution_mismatch", "hermes_contract_unsupported" -> "モデル設定を実行まで照合できません。サーバーの対応状況を確認してください。"
                    "execution_immutable" -> "受理済みのモデル設定は変更できません。変更は次の新しい依頼に反映されます。"
                    "execution_confirmation_required" -> "以前の作業です。モデルと推論強度を確認して続行してください。"
                    "paid_fallback_blocked" -> "Codex OAuthの接続を確認してください。別の接続では実行していません。"
                    "quota_paused" -> "選択したプロバイダーの利用上限で中断しました。利用可能になってから続行できます。"
                    "queue_full" -> "待機中の依頼が上限に達しています。先の依頼を完了・停止してから再送してください。"
                    "document_version_conflict" -> "文書が更新されています。表示中の版と最新版を確認してから修正を依頼してください。"
                    "operation_reused" -> "同じ操作IDで内容が変わっています。元の依頼と保存結果を確認してください。"
                    "document_missing", "target_missing", "run_missing" -> "対象が見つかりません。記録を更新して確認してください。"
                    else -> null
                }
            message ?: when (error.code()) {
                401, 403 -> "ログイン・権限を確認してください。変更内容は保持しています。"
                404 -> "サーバーに就活プラグインが見つかりません。導入状況を確認してください。"
                409 -> "ほかの更新と競合しました。最新の内容と比較してください。"
                400, 413, 422 -> "保存内容を確認してください。形式または容量が対応範囲外です。"
                else -> "保存結果を確認できません。同じ操作として再試行できます。"
            }
        }

        is IllegalStateException -> {
            error.message ?: "接続先を確認してください。"
        }

        else -> {
            "接続できません。保存結果が不明な場合は同じ操作として再試行してください。"
        }
    }
