package com.m57.hermescontrol.ui.connections

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.m57.hermescontrol.career.awaitCareerResult
import com.m57.hermescontrol.career.careerErrorCode
import com.m57.hermescontrol.career.findActivity
import com.m57.hermescontrol.data.local.AppScope
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

/** Google Calendar and Drive share one Hermes server connection (Workspace MCP). */
internal val GOOGLE_WORKSPACE_SERVICES = setOf("calendar", "drive")

@Serializable
class GoogleWorkspaceFlow(
    val flow_id: String,
    val client_id: String,
    val scopes: List<String> = emptyList(),
)

internal interface GoogleWorkspaceRepository {
    suspend fun status(): GoogleConnectionStatus

    suspend fun begin(): GoogleWorkspaceFlow

    suspend fun complete(
        flow: GoogleWorkspaceFlow,
        code: String,
    ): GoogleConnectionStatus

    suspend fun disconnect(accountId: String): GoogleConnectionStatus
}

internal class DashboardGoogleWorkspaceRepository(
    private val scope: String = AppScope.connectionKey(),
) : GoogleWorkspaceRepository {
    private val api = ApiClient.hermesApi

    private fun checkScope() {
        check(scope == AppScope.connectionKey() && AuthManager.isGatedMode()) { "connection_changed" }
    }

    override suspend fun status(): GoogleConnectionStatus {
        checkScope()
        return api.googleWorkspaceStatus().also { checkScope() }
    }

    override suspend fun begin(): GoogleWorkspaceFlow {
        checkScope()
        return api.googleWorkspaceBegin(buildJsonObject {}).also { checkScope() }
    }

    override suspend fun complete(
        flow: GoogleWorkspaceFlow,
        code: String,
    ): GoogleConnectionStatus {
        checkScope()
        return api
            .googleWorkspaceComplete(
                buildJsonObject {
                    put("flow_id", flow.flow_id)
                    put("code", code)
                },
            ).also { checkScope() }
    }

    override suspend fun disconnect(accountId: String): GoogleConnectionStatus {
        checkScope()
        return api
            .googleWorkspaceDisconnect(buildJsonObject { put("account_id", accountId) })
            .also { checkScope() }
    }
}

internal data class GoogleWorkspaceUiState(
    val status: GoogleConnectionStatus? = null,
    val loaded: Boolean = false,
    /** The server has no app login for Calendar/Drive yet (older server). */
    val unsupported: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** Set right after a successful connection, for the sheet's done state. */
    val connectedEmail: String? = null,
)

/** The connect sheet can start Google's account choice. */
internal fun GoogleWorkspaceUiState.canConnect(): Boolean =
    !unsupported && status != null && status.state != "setup_required" && status.can_add

internal class GoogleWorkspaceViewModel(
    private val repository: GoogleWorkspaceRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GoogleWorkspaceUiState())
    val state = mutableState.asStateFlow()
    private var flow: GoogleWorkspaceFlow? = null
    private var job: Job? = null

    fun refresh() {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        job =
            viewModelScope.launch {
                try {
                    mutableState.value =
                        mutableState.value.copy(status = repository.status(), loaded = true, unsupported = false)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val missing = error is HttpException && error.code() == 404
                    mutableState.value =
                        mutableState.value.copy(
                            loaded = true,
                            unsupported = missing,
                            error = if (missing) null else googleWorkspaceError(error),
                        )
                } finally {
                    mutableState.value = mutableState.value.copy(busy = false)
                }
            }
    }

    fun begin(authorize: suspend (GoogleWorkspaceFlow) -> String?) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(connectedEmail = null)
        run {
            val pending = repository.begin()
            check(pending.scopes.isNotEmpty() && pending.scopes.none { "gmail" in it })
            flow = pending
            val code = authorize(pending)
            if (code != null) {
                flow = null
                finish(repository.complete(pending, code))
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
        run { finish(repository.complete(pending, code)) }
    }

    fun cancel() {
        flow = null
        job?.cancel()
        mutableState.value = mutableState.value.copy(busy = false)
    }

    fun disconnect(accountId: String) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(connectedEmail = null)
        run { mutableState.value = mutableState.value.copy(status = repository.disconnect(accountId)) }
    }

    fun clearResult() {
        mutableState.value = mutableState.value.copy(error = null, connectedEmail = null)
    }

    private fun finish(status: GoogleConnectionStatus) {
        val before =
            mutableState.value.status
                ?.accounts
                .orEmpty()
                .map { it.email }
                .toSet()
        val added = status.accounts.firstOrNull { it.email !in before } ?: status.accounts.firstOrNull()
        mutableState.value = mutableState.value.copy(status = status, connectedEmail = added?.email ?: "")
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
                    mutableState.value = mutableState.value.copy(error = googleWorkspaceError(error))
                } finally {
                    // While Google's consent screen is open, keep the button disabled.
                    mutableState.value = mutableState.value.copy(busy = flow != null)
                }
            }
    }
}

internal fun googleWorkspaceError(error: Exception): String =
    when (if (error is HttpException) careerErrorCode(error) else null) {
        "workspace_mcp_missing",
        "workspace_credentials_dir_unknown",
        "google_client_missing",
        "google_client_invalid",
        -> {
            "Hermesサーバーのカレンダー・ドライブ接続の設定が必要です。"
        }

        "google_scope_missing" -> {
            "カレンダー・ドライブ・ドキュメントのすべてを許可してください。"
        }

        "google_native_consent_required" -> {
            "アプリから接続し直してください。"
        }

        "account_not_found" -> {
            "アカウントの接続状態が変わりました。再読込してください。"
        }

        else -> {
            googleConnectionError(error)
        }
    }

/** Starts Google's own account choice and consent for Calendar/Drive. */
@Composable
internal fun rememberGoogleWorkspaceConnect(model: GoogleWorkspaceViewModel): () -> Unit {
    val activity = LocalContext.current.findActivity()
    val resolution =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && activity != null) {
                val code =
                    runCatching {
                        Identity
                            .getAuthorizationClient(activity)
                            .getAuthorizationResultFromIntent(result.data)
                            .serverAuthCode
                    }.getOrNull()
                model.complete(code)
            } else {
                model.cancel()
            }
        }
    DisposableEffect(model) { onDispose { model.cancel() } }
    return {
        if (activity != null) {
            model.begin { flow ->
                val authorization =
                    Identity
                        .getAuthorizationClient(activity)
                        .authorize(
                            AuthorizationRequest
                                .builder()
                                .setRequestedScopes(flow.scopes.map(::Scope))
                                .setOptOutIncludingGrantedScopes(true)
                                .requestOfflineAccess(flow.client_id)
                                .setPrompt(
                                    AuthorizationRequest.Prompt.CONSENT or
                                        AuthorizationRequest.Prompt.SELECT_ACCOUNT,
                                ).build(),
                        ).awaitCareerResult()
                if (authorization.hasResolution()) {
                    resolution.launch(
                        IntentSenderRequest.Builder(requireNotNull(authorization.pendingIntent)).build(),
                    )
                    null
                } else {
                    requireNotNull(authorization.serverAuthCode)
                }
            }
        }
    }
}
