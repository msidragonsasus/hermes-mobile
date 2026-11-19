package com.m57.hermescontrol.pcpower

import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

interface PcPowerRepository {
    suspend fun status(): PcPowerStatus

    suspend fun wake(): PcPowerActionResponse

    suspend fun sleep(operationId: String): PcPowerActionResponse
}

class DashboardPcPowerRepository(
    private val scope: String = pcPowerScope(),
) : PcPowerRepository {
    private val api = ApiClient.hermesApi

    private fun checkScope() {
        check(scope == pcPowerScope()) { "接続先が変わりました。PC電源Appを開き直してください。" }
        check(AuthManager.isGatedMode()) { "PC電源AppにはDashboardへのログインが必要です。" }
    }

    override suspend fun status(): PcPowerStatus {
        checkScope()
        return api.pcPowerStatus().also {
            checkScope()
            check(it.api_version == 1) { "未対応のPC電源プラグインです。" }
        }
    }

    override suspend fun wake(): PcPowerActionResponse {
        checkScope()
        return api.pcPowerWake().also {
            checkScope()
            check(it.api_version == 1) { "未対応のPC電源プラグインです。" }
        }
    }

    override suspend fun sleep(operationId: String): PcPowerActionResponse {
        checkScope()
        return api.pcPowerSleep(PcPowerSleepRequest(operationId, confirm = true)).also {
            checkScope()
            check(it.api_version == 1 && it.operation_id == operationId) {
                "スリープ要求の受理結果を照合できません。PCの状態を確認してください。"
            }
        }
    }
}

fun pcPowerScope(): String = AppScope.connectionKey()

private fun powerErrorCode(error: HttpException): String? =
    runCatching {
        val text =
            error
                .response()
                ?.errorBody()
                ?.string()
                ?.take(4096) ?: return@runCatching null
        val root =
            com.m57.hermescontrol.data.remote.OkHttpProvider.json
                .parseToJsonElement(text) as? JsonObject
        (root?.get("detail") as? JsonObject)?.get("code")?.jsonPrimitive?.content
    }.getOrNull()

fun pcPowerError(error: Throwable): String =
    when (error) {
        is HttpException -> {
            when (powerErrorCode(error)) {
                "dashboard_session_required" -> {
                    "Dashboardへ再ログインしてください。"
                }

                "pc_power_not_configured" -> {
                    "サーバーのPC電源設定が完了していません。"
                }

                "sleep_receiver_not_configured" -> {
                    "対象PCのスリープ受信設定を確認してください。"
                }

                "wake_send_failed" -> {
                    "起動信号を送信できませんでした。サーバーのLAN接続を確認してください。"
                }

                "sleep_rejected" -> {
                    "対象PCがスリープ要求を拒否しました。"
                }

                "sleep_result_unknown" -> {
                    "スリープ要求の受理結果を確認できません。PCの状態を確認してから再操作してください。"
                }

                else -> {
                    when (error.code()) {
                        401, 403 -> "Dashboardのログインと権限を確認してください。"
                        404 -> "PC電源プラグインがサーバーに見つかりません。"
                        else -> "PC電源の状態を確認できません。接続を確認してください。"
                    }
                }
            }
        }

        is IllegalStateException -> {
            error.message ?: "接続先を確認してください。"
        }

        else -> {
            "PC電源の状態を確認できません。接続を確認してください。"
        }
    }
