package com.m57.hermescontrol.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.career.careerErrorCode
import com.m57.hermescontrol.career.careerScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.HttpException

@Serializable
data class GoogleConnectionStatus(
    val state: String,
    val email: String? = null,
    val reason: String? = null,
    val accounts: List<GoogleConnectedAccount> = emptyList(),
    val can_add: Boolean = false,
    val multi_account: Boolean = false,
)

@Serializable
data class GoogleConnectedAccount(
    val id: String,
    val email: String,
    val label: String = "",
    val state: String = "connected",
)

@Serializable
class GoogleConnectionFlow(
    val flow_id: String,
    val client_id: String,
    val scope: String,
)

internal interface GoogleConnectionRepository {
    suspend fun status(): GoogleConnectionStatus

    suspend fun begin(accountId: String? = null): GoogleConnectionFlow

    suspend fun manage(
        action: String,
        accountId: String,
        label: String? = null,
    ): GoogleConnectionStatus

    suspend fun complete(
        flow: GoogleConnectionFlow,
        code: String,
    ): GoogleConnectionStatus
}

internal class DashboardGoogleConnectionRepository(
    private val scope: String = careerScope(),
) : GoogleConnectionRepository {
    private val api = ApiClient.hermesApi

    private fun checkScope() {
        check(scope == careerScope() && AuthManager.isGatedMode()) { "connection_changed" }
    }

    override suspend fun status(): GoogleConnectionStatus {
        checkScope()
        return api.googleConnectionStatus().also { checkScope() }
    }

    override suspend fun begin(accountId: String?): GoogleConnectionFlow {
        checkScope()
        return api
            .googleConnectionBegin(
                buildJsonObject { accountId?.let { put("account_id", it) } },
            ).also { checkScope() }
    }

    override suspend fun manage(
        action: String,
        accountId: String,
        label: String?,
    ): GoogleConnectionStatus {
        checkScope()
        check(action in setOf("rename", "disconnect"))
        return api
            .googleConnectionManage(
                action,
                buildJsonObject {
                    put("account_id", accountId)
                    label?.let { put("label", it) }
                },
            ).also { checkScope() }
    }

    override suspend fun complete(
        flow: GoogleConnectionFlow,
        code: String,
    ): GoogleConnectionStatus {
        checkScope()
        return api
            .googleConnectionComplete(
                buildJsonObject {
                    put("flow_id", flow.flow_id)
                    put("code", code)
                },
            ).also { checkScope() }
    }
}

internal data class GoogleConnectionUiState(
    val status: GoogleConnectionStatus? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

internal class GoogleConnectionViewModel(
    private val repository: GoogleConnectionRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GoogleConnectionUiState())
    val state = mutableState.asStateFlow()
    private var flow: GoogleConnectionFlow? = null
    private var job: Job? = null

    fun refresh() {
        if (mutableState.value.busy) return
        run { mutableState.value = GoogleConnectionUiState(status = repository.status()) }
    }

    fun begin(
        accountId: String? = null,
        authorize: suspend (GoogleConnectionFlow) -> String?,
    ) {
        if (mutableState.value.busy) return
        run {
            val pending = repository.begin(accountId)
            check(pending.scope == GMAIL_READONLY)
            flow = pending
            val code = authorize(pending)
            if (code != null) {
                flow = null
                mutableState.value = GoogleConnectionUiState(status = repository.complete(pending, code))
            }
        }
    }

    fun complete(code: String?) {
        val pending = flow ?: return
        flow = null
        if (code.isNullOrBlank()) {
            cancel()
            return
        }
        run { mutableState.value = GoogleConnectionUiState(status = repository.complete(pending, code)) }
    }

    fun cancel() {
        flow = null
        job?.cancel()
        mutableState.value = mutableState.value.copy(busy = false)
    }

    fun rename(
        accountId: String,
        label: String,
    ) = manage("rename", accountId, label)

    fun disconnect(accountId: String) = manage("disconnect", accountId)

    private fun manage(
        action: String,
        accountId: String,
        label: String? = null,
    ) {
        if (mutableState.value.busy) return
        run { mutableState.value = GoogleConnectionUiState(status = repository.manage(action, accountId, label)) }
    }

    private fun run(action: suspend () -> Unit) {
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        job =
            viewModelScope.launch {
                try {
                    action()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    flow = null
                    mutableState.value = mutableState.value.copy(error = googleConnectionError(error))
                } finally {
                    // While native consent is open, keep the connect button disabled.
                    mutableState.value = mutableState.value.copy(busy = flow != null)
                }
            }
    }

    companion object {
        const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
    }
}

internal fun googleConnectionError(error: Exception): String {
    val code = if (error is HttpException) careerErrorCode(error) else null
    return when {
        error is HttpException && error.code() == 404 -> "サーバーの更新が必要です。Google接続機能を導入してから再読込してください。"

        code in
            setOf(
                "google_setup_required",
                "google_workspace_required",
            )
        -> "Google連携の初期設定が必要です。管理者が設定すると、この画面から接続できます。"

        code == "google_existing_connection" -> "既存のGoogle接続があります。再読込して確認してください。"

        code == "google_account_exists" -> "このアカウントは接続済みです。一覧から「接続し直す」を選んでください。"

        code == "google_account_mismatch" -> "接続し直すアカウントと同じGoogleアカウントを選んでください。"

        code == "google_account_missing" -> "アカウントの接続状態が変わりました。再読込してください。"

        code == "google_flow_expired" -> "接続の有効時間が切れました。もう一度接続してください。"

        code == "google_authorization_failed" -> "Googleの許可を確認できませんでした。もう一度接続してください。"

        code == "google_storage_unavailable" -> "サーバーに接続情報を保存できませんでした。"

        error is IllegalStateException -> "接続先を確認して、この画面を開き直してください。"

        else -> "接続を確認できませんでした。再読込してから接続をやり直してください。"
    }
}
