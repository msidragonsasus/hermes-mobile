package com.m57.hermescontrol.ui.settings.limits

import androidx.lifecycle.ViewModel
import com.m57.hermescontrol.data.model.AccountLimitsResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.Response

enum class LimitsStatus { LOADING, READY, UNAVAILABLE, ERROR }

data class AccountLimitsState(
    val status: LimitsStatus = LimitsStatus.LOADING,
    val snapshot: AccountLimitsResponse? = null,
)

/** One instance per server/profile. Old requests cannot overwrite a newer refresh. No disk cache. */
class AccountLimitsViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(AccountLimitsState())
    val state = mutableState.asStateFlow()
    private var generation = 0

    suspend fun refresh(fetch: suspend () -> Response<AccountLimitsResponse>) {
        val request = ++generation
        mutableState.value = AccountLimitsState()
        val next =
            try {
                val response = fetch()
                val body = response.body()
                when {
                    response.code() in setOf(404, 405, 501) -> AccountLimitsState(LimitsStatus.UNAVAILABLE)
                    !response.isSuccessful -> AccountLimitsState(LimitsStatus.ERROR)
                    body?.available != true || body.windows.isEmpty() -> AccountLimitsState(LimitsStatus.UNAVAILABLE)
                    else -> AccountLimitsState(LimitsStatus.READY, body)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Do not surface URLs, provider errors or payloads to logs/UI.
                AccountLimitsState(LimitsStatus.ERROR)
            }
        if (request == generation) mutableState.value = next
    }
}
