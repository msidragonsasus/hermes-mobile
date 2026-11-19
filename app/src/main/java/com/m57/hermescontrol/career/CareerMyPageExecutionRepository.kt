package com.m57.hermescontrol.career

import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient

interface CareerMyPageExecutionRepository {
    suspend fun capabilities(refresh: Boolean = false): CareerMyPageCapabilities

    suspend fun settings(): CareerMyPageExecutionSettings

    suspend fun save(update: CareerMyPageExecutionUpdate): CareerMyPageExecutionSettings
}

class DashboardCareerMyPageExecutionRepository(
    private val scope: String = careerScope(),
) : CareerMyPageExecutionRepository {
    private val api = ApiClient.hermesApi
    private val profile = AppScope.PROFILE

    private fun checkScope() {
        check(scope == careerScope() && AuthManager.isGatedMode()) {
            "接続先が変わりました。就活Appを開き直してください。"
        }
    }

    override suspend fun capabilities(refresh: Boolean): CareerMyPageCapabilities {
        checkScope()
        return api.careerMyPageCapabilities(profile, refresh).also { checkScope() }
    }

    override suspend fun settings(): CareerMyPageExecutionSettings {
        checkScope()
        return api.careerMyPageExecutionSettings(profile).also { checkScope() }
    }

    override suspend fun save(update: CareerMyPageExecutionUpdate): CareerMyPageExecutionSettings {
        checkScope()
        return api.careerMyPageSaveExecutionSettings(profile, update).also { checkScope() }
    }
}
