package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.AccountLimitsResponse
import retrofit2.Response

/** Profile-bound read adapter over the existing authenticated REST client. */
object AccountLimitsRepository {
    suspend fun read(profile: String?): Response<AccountLimitsResponse> {
        val api = ApiClient.hermesApi
        return api.getAccountLimits(profile)
    }
}
