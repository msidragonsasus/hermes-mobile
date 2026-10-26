package com.m57.hermescontrol.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.career.careerScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.McpOAuthFlowResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.UUID

@Serializable
data class SlackConnectionStatus(
    val state: String,
    val serverName: String = "slack",
    val enabled: Boolean = false,
)

@Serializable
data class SlackConnectRequest(
    val requestId: String,
)

internal interface SlackConnectionRepository {
    suspend fun status(): SlackConnectionStatus

    suspend fun begin(requestId: String): McpOAuthFlowResponse

    suspend fun flow(id: String): McpOAuthFlowResponse

    suspend fun cancel(id: String)
}

internal class SlackConnectionFailure(
    val code: String,
) : Exception()

internal class DashboardSlackConnectionRepository(
    private val scope: String = careerScope(),
) : SlackConnectionRepository {
    private val api = ApiClient.hermesApi

    private fun checkScope() {
        check(scope == careerScope() && AuthManager.isGatedMode())
    }

    private suspend fun <T> call(block: suspend () -> retrofit2.Response<T>): T {
        checkScope()
        val response = block()
        checkScope()
        if (!response.isSuccessful) {
            val code =
                runCatching {
                    OkHttpProvider.json
                        .parseToJsonElement(response.errorBody()?.string().orEmpty())
                        .jsonObject["detail"]
                        ?.jsonObject
                        ?.get("code")
                        ?.jsonPrimitive
                        ?.content
                }.getOrNull()
            throw SlackConnectionFailure(
                code ?: if (response.code() == 404) "backend_update_required" else "unavailable",
            )
        }
        return requireNotNull(response.body())
    }

    override suspend fun status() = call { api.slackConnectionStatus() }

    override suspend fun begin(requestId: String) = call { api.connectSlack(SlackConnectRequest(requestId)) }

    override suspend fun flow(id: String) = call { api.slackConnectionFlow(id) }

    override suspend fun cancel(id: String) {
        checkScope()
        val response = api.cancelSlackConnectionFlow(id)
        checkScope()
        check(response.isSuccessful)
    }
}

internal data class SlackConnectionUiState(
    val loading: Boolean = true,
    val status: SlackConnectionStatus? = null,
    val connecting: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

internal fun isSlackAuthorizationUrl(value: String): Boolean =
    runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "slack.com" && uri.port in setOf(-1, 443) &&
            uri.userInfo == null && uri.fragment == null &&
            uri.path in setOf("/oauth/v2_user/authorize", "/oauth/v2/authorize")
    }.getOrDefault(false)

internal class SlackConnectionViewModel(
    private val repository: SlackConnectionRepository,
    private val pollIntervalMs: Long = 1_000,
    private val maxPolls: Int = 300,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SlackConnectionUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var requestId: String? = null
    private var flowId: String? = null

    fun refresh() {
        if (job?.isActive == true) return
        mutableState.value = SlackConnectionUiState()
        job =
            viewModelScope.launch {
                try {
                    val status = repository.status()
                    check(
                        status.serverName == "slack" &&
                            status.state in setOf("ready", "authorized", "setup_required", "manual_connection"),
                    )
                    mutableState.value = SlackConnectionUiState(loading = false, status = status)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    mutableState.value = SlackConnectionUiState(loading = false, error = errorMessage(error))
                }
            }
    }

    fun connect(openBrowser: (String) -> Unit) {
        if (job?.isActive == true || mutableState.value.status?.state !in setOf("ready", "authorized")) return
        val operation = requestId ?: UUID.randomUUID().toString().also { requestId = it }
        mutableState.value = mutableState.value.copy(connecting = true, error = null, message = null)
        job =
            viewModelScope.launch {
                try {
                    var flow = repository.begin(operation)
                    check(flow.serverName == "slack")
                    flowId = flow.flowId
                    var browserOpened = false
                    var failures = 0
                    repeat(maxPolls + 1) { poll ->
                        when (flow.status) {
                            "approved", "completed" -> {
                                val status = repository.status()
                                check(status.serverName == "slack" && status.state == "authorized")
                                flowId = null
                                requestId = null
                                mutableState.value =
                                    SlackConnectionUiState(
                                        loading = false,
                                        status = status,
                                        message = "Slackの認証が完了しました。",
                                    )
                                return@launch
                            }

                            "error" -> {
                                flowId = null
                                requestId = null
                                error("authorization_failed")
                            }

                            "authorization_required" -> {
                                if (!browserOpened) {
                                    val url = requireNotNull(flow.authorizationUrl)
                                    if (!isSlackAuthorizationUrl(url)) {
                                        repository.cancel(flow.flowId)
                                        flowId = null
                                        requestId = null
                                        error("invalid_authorization_url")
                                    }
                                    openBrowser(url)
                                    browserOpened = true
                                }
                            }

                            "starting" -> {}

                            else -> {
                                error("unexpected_flow")
                            }
                        }
                        if (poll < maxPolls) {
                            delay(pollIntervalMs)
                            try {
                                val next = repository.flow(flow.flowId)
                                check(next.serverName == "slack" && next.flowId == flowId)
                                flow = next
                                failures = 0
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                if (isDefinitiveFailure(error) || ++failures >= 3) throw error
                            }
                        }
                    }
                    flowId?.let { repository.cancel(it) }
                    flowId = null
                    requestId = null
                    error("authorization_timed_out")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (isDefinitiveFailure(error)) {
                        flowId = null
                        requestId = null
                    }
                    mutableState.value = mutableState.value.copy(connecting = false, error = errorMessage(error))
                }
            }
    }

    fun cancel() {
        job?.cancel()
        val id = flowId
        flowId = null
        if (id != null) {
            requestId = null
            viewModelScope.launch {
                try {
                    repository.cancel(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Core expires the pending flow; existing credentials are retained.
                }
            }
        }
        mutableState.value = mutableState.value.copy(connecting = false)
    }

    private fun errorMessage(error: Exception): String =
        when ((error as? SlackConnectionFailure)?.code) {
            "backend_update_required" -> "サーバーにSlackログイン機能の導入が必要です。"
            "slack_setup_required" -> "管理者によるSlack連携の初回設定が必要です。"
            "authorization_in_progress" -> "認証処理が進行中です。元の認証画面を確認してください。"
            "flow_expired" -> "認証の有効時間が切れました。もう一度接続してください。"
            "flow_owner_mismatch", "dashboard_session_required" -> "Hermesへのログイン状態を確認してください。"
            else -> "Slackの認証を確認できません。再読込するか、もう一度接続してください。"
        }

    private fun isDefinitiveFailure(error: Exception): Boolean =
        (error as? SlackConnectionFailure)?.code in
            setOf(
                "flow_expired",
                "flow_owner_mismatch",
                "dashboard_session_required",
                "slack_setup_required",
                "authorization_in_progress",
                "backend_update_required",
            )
}
