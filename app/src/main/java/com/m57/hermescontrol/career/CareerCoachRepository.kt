package com.m57.hermescontrol.career

import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

interface CareerCoachRepository {
    suspend fun capabilities(refresh: Boolean = false): CareerCapabilities

    suspend fun dailySettings(): CareerDailySettings

    suspend fun conversation(before: Long? = null): CareerCoachSnapshot

    suspend fun events(after: Long): CareerCoachEvents

    suspend fun mutate(
        action: String,
        payload: JsonObject,
    ): JsonObject
}

/** Captures both authenticated service and explicit server profile at construction. */
class DashboardCareerCoachRepository(
    private val scope: String = careerScope(),
    private val threadId: String = "global",
) : CareerCoachRepository {
    private val api = ApiClient.hermesApi
    private val profile = AppScope.PROFILE

    private fun checkScope() {
        check(scope == careerScope()) { "接続先が変わりました。就活Appを開き直してください。" }
        check(AuthManager.isGatedMode()) { "就活AppにはDashboardへのログインが必要です。" }
    }

    override suspend fun capabilities(refresh: Boolean): CareerCapabilities {
        checkScope()
        return api.careerCoachCapabilities(profile, refresh).also {
            checkScope()
            check(threadId == "global" || "company_threads" in it.features) { "企業ごとの会話に対応した就活サーバーへ更新してください。" }
        }
    }

    override suspend fun dailySettings(): CareerDailySettings {
        checkScope()
        return api.careerCoachDailySettings(profile).also { checkScope() }
    }

    override suspend fun conversation(before: Long?): CareerCoachSnapshot {
        checkScope()
        return api.careerCoachConversation(profile, before, threadId).also {
            checkScope()
            check(it.api_version == 2 && it.profile == profile && it.thread_id == threadId) { "サーバーの就活コーチを更新してください。" }
        }
    }

    override suspend fun events(after: Long): CareerCoachEvents {
        checkScope()
        return api.careerCoachEvents(profile, after, threadId).also {
            checkScope()
            check(it.thread_id == threadId) { "企業ごとの会話を確認できません。" }
        }
    }

    override suspend fun mutate(
        action: String,
        payload: JsonObject,
    ): JsonObject {
        checkScope()
        val scopedPayload =
            // Preserve pre-upgrade pending control payloads: adding a field would change their idempotency hash.
            if (action in setOf("message", "research") ||
                (action == "control" && (threadId.startsWith("thread:") || "thread_id" in payload))
            ) {
                JsonObject(payload + ("thread_id" to JsonPrimitive(threadId)))
            } else {
                payload
            }
        return (
            if (threadId ==
                "global"
            ) {
                api.careerCoachMutation(action, profile, scopedPayload)
            } else {
                api.careerThreadMutation(action, profile, scopedPayload)
            }
        ).also { checkScope() }
    }
}
