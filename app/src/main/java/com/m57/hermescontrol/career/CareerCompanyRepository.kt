package com.m57.hermescontrol.career

import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CareerCompanyRepository(
    private val scope: String = careerScope(),
) {
    private val api = ApiClient.hermesApi
    private val json = Json { ignoreUnknownKeys = true }
    private val profile = AppScope.PROFILE

    private fun checkScope() {
        check(scope == careerScope() && AuthManager.isGatedMode()) { "接続先が変わりました。就活Appを開き直してください。" }
    }

    suspend fun saveCompany(
        action: String,
        payload: JsonObject,
    ): JsonObject {
        checkScope()
        return api.careerMutation(action, payload).also { checkScope() }
    }

    suspend fun companies(): CareerCompanies {
        checkScope()
        return api.careerCompanies(profile).also { checkScope() }
    }

    suspend fun saveCompanyOrder(
        ids: List<String>,
        expectedVersion: Int,
        requestId: String,
    ): CareerCompanyOrder {
        val result =
            mutate(
                "company-order",
                buildJsonObject {
                    put("operation_id", requestId)
                    put("expected_version", expectedVersion)
                    put("company_ids", JsonArray(ids.map { JsonPrimitive(it) }))
                },
            )
        return json.decodeFromString(result.toString())
    }

    suspend fun company(id: String): CareerCompanyDetail {
        checkScope()
        return api.careerCompany(id, profile).also { checkScope() }
    }

    suspend fun connections(): CareerConnections {
        checkScope()
        return api.careerConnections().also { checkScope() }
    }

    suspend fun mailStatus(): JsonObject {
        checkScope()
        return api.careerMailStatus().also { checkScope() }
    }

    suspend fun notices(
        after: Long,
        baseline: Boolean = false,
    ): CareerNoticeFeed {
        checkScope()
        return api.careerNotices(after, profile, baseline).also { checkScope() }
    }

    suspend fun noticeContent(id: String): JsonObject {
        checkScope()
        return api.careerNoticeContent(id, profile).also { checkScope() }
    }

    suspend fun importClientMail(
        accountEmail: String,
        messages: List<CareerClientMailMessage>,
        companyId: String? = null,
    ): JsonObject {
        checkScope()
        return api
            .careerClientMail(
                profile,
                CareerClientMailImport(accountEmail, messages, companyId),
            ).also { checkScope() }
    }

    suspend fun cachedMail(
        accountEmail: String,
        companyId: String,
        ids: List<String>,
    ): Set<String> {
        checkScope()
        return api
            .careerMailCache(CareerMailCacheRequest(accountEmail, companyId, ids))
            .also { checkScope() }
            .cached_ids
            .toSet()
    }

    suspend fun mailPage(
        companyId: String,
        cursor: String,
    ): CareerMailPage {
        checkScope()
        return api.careerMailPage(companyId, cursor).also { checkScope() }
    }

    suspend fun mutate(
        action: String,
        payload: JsonObject,
    ): JsonObject {
        checkScope()
        return if (action == "mypage-resume-analysis") {
            val runId = checkNotNull(payload.text("run_id"))
            api.careerMyPageResumeAnalysis(runId, profile, JsonObject(payload - "run_id")).also { checkScope() }
        } else {
            api.careerCollection(action, profile, payload).also { checkScope() }
        }
    }
}
