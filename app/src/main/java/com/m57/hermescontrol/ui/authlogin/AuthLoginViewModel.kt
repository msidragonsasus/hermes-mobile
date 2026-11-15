package com.m57.hermescontrol.ui.authlogin

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.config.resolveBaseUrl
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.AuthPayloads
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.remote.await
import com.m57.hermescontrol.data.remote.isSessionCookie
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.GatewayWebSocketProbe
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsTicketResponse
import com.m57.hermescontrol.worker.PushTokenLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * What auth the dashboard requires. Derived authoritatively from
 * `GET /api/status` (`auth_required` + `auth_providers`), not by sniffing
 * redirects — the status JSON is the source of truth.
 */
enum class DashboardAuthMode {
    /** Dashboard has no auth gate (loopback / `--insecure`) — just needs a session token. */
    TOKEN_ONLY,

    /** Dashboard has basic auth (gated `0.0.0.0` bind) — needs username + password. */
    BASIC_AUTH,

    /** Dashboard requires both basic auth credentials and a session token. */
    ALL,

    /**
     * Dashboard uses OAuth via Nous Portal (gated `0.0.0.0` bind).
     *
     * TODO: implement the OAuth browser flow + WS ticket minting. Until then the
     * UI renders a "coming soon" state and blocks connect. Tracked in issue #639.
     */
    OAUTH,
}

data class AuthLoginUiState(
    val baseUrl: String = ServerEndpoint.DEFAULT_BASE_URL,
    val transportWarning: String? = null,
    val token: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val probing: Boolean = false,
    val authMode: DashboardAuthMode? = null,
    val connectionSuccess: Boolean = false,
    val errorMessage: String? = null,
    val loggedInProfiles: List<ConnectionProfile> = emptyList(),
)

class AuthLoginViewModel(
    private val app: Application,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            AuthLoginUiState(
                baseUrl = AuthManager.getBaseUrl(),
            ),
        )
    val uiState: StateFlow<AuthLoginUiState> = _uiState.asStateFlow()

    init {
        loadLoggedInProfiles()
    }

    fun loadLoggedInProfiles() {
        val allProfiles = AuthManager.getConnectionProfiles()
        val loggedIn =
            allProfiles.filter { profile ->
                val token = AuthManager.getProfileToken(profile.id)
                !token.isNullOrBlank()
            }
        _uiState.update { it.copy(loggedInProfiles = loggedIn) }
    }

    private var connectionJob: Job? = null

    fun useExistingProfile(profileId: String) {
        if (_uiState.value.isLoading) return
        val profile = _uiState.value.loggedInProfiles.firstOrNull { it.id == profileId } ?: return
        val token = AuthManager.getProfileToken(profileId)?.takeIf(String::isNotBlank) ?: return
        val endpoint =
            runCatching { ServerEndpoint.parseForBuild(profile.resolveBaseUrl(AuthManager.getBaseUrl())) }
                .getOrNull() ?: return
        _uiState.update { it.copy(isLoading = true, errorMessage = null, connectionSuccess = false) }
        connectionJob =
            viewModelScope.launch {
                val gated =
                    withContext(Dispatchers.IO) {
                        val cookies = CookieManager.cookiesForProfile(profileId, endpoint)
                        if (cookies.isEmpty()) return@withContext false
                        val ticket = mintTicket(endpoint, isolatedProbeClient(), cookies, profileScopedCookies = true)
                        ticket != null && GatewayWebSocketProbe.check(endpoint, "ticket", ticket)
                    }
                if (!gated && !GatewayWebSocketProbe.check(endpoint, "token", token)) {
                    connectionFailed()
                    return@launch
                }
                AuthManager.setSelectedProfileId(profileId)
                AuthManager.syncCookieStoreForProfile(profileId)
                AuthManager.setWsAuthParam(if (gated) "ticket" else "token")
                ApiClient.rebuild()
                HermesWsClient.connect()
                PushTokenLifecycle.registerAsync()
                _uiState.update { it.copy(isLoading = false, connectionSuccess = true) }
            }
    }

    private fun connectionFailed() {
        _uiState.update {
            it.copy(
                isLoading = false,
                connectionSuccess = false,
                errorMessage = app.getString(R.string.connect_error_connection_failed, "WebSocket"),
            )
        }
    }

    companion object {
        private const val TAG = "AuthLoginVM"
    }

    private val probeClient: OkHttpClient =
        com.m57.hermescontrol.data.remote.OkHttpProvider.probe

    fun onBaseUrlChange(value: String) {
        val trimmed = value.trim()
        val warning =
            runCatching {
                ServerEndpoint.parse(trimmed, CleartextPolicy.ALLOW_WITH_WARNING).securityWarning
            }.getOrNull()
        _uiState.update { it.copy(baseUrl = trimmed, transportWarning = warning, errorMessage = null, authMode = null) }
    }

    /** Reset ephemeral connection state (called when screen leaves composition). */
    fun clearConnectionState() {
        connectionJob?.cancel()
        _uiState.update { it.copy(connectionSuccess = false, errorMessage = null, isLoading = false) }
    }

    fun onTokenChange(value: String) {
        _uiState.update { it.copy(token = value.trim(), errorMessage = null) }
    }

    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value.trim(), errorMessage = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null) }
    }

    /**
     * Step 1: Probe the dashboard to detect what auth it needs.
     *
     * Uses the public `GET /api/status` endpoint as the authoritative source:
     * it reports `auth_required` (gate engaged on non-loopback binds) and
     * `auth_providers` (e.g. ["basic"], ["oauth"], []). We map that to a
     * [DashboardAuthMode] instead of sniffing redirects, which is a heuristic.
     */
    fun probe() {
        val state = _uiState.value
        val endpoint =
            runCatching { ServerEndpoint.parseForBuild(state.baseUrl) }.getOrNull()
        if (endpoint == null) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.connect_error_url_invalid)) }
            return
        }

        _uiState.update { it.copy(probing = true, errorMessage = null, authMode = null) }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    probeDashboardInternal(endpoint)
                }
            _uiState.update {
                it.copy(
                    probing = false,
                    authMode = result?.authMode,
                    token = result?.extractedToken ?: it.token,
                    errorMessage =
                        if (result == null) {
                            app.getString(R.string.auth_login_error_unreachable)
                        } else {
                            null
                        },
                )
            }
        }
    }

    /**
     * Result of probing the dashboard.
     */
    private data class ProbeResult(
        val authMode: DashboardAuthMode,
        val extractedToken: String? = null,
    )

    /**
     * Derives the [DashboardAuthMode] from the authoritative `/api/status` fields.
     *
     * - Gate down (loopback / `--insecure`): caller decides TOKEN_ONLY vs ALL
     *   based on whether the SPA embedded a token, so this returns a sentinel
     *   [DashboardAuthMode.ALL] placeholder that [probeDashboardInternal] refines.
     * - Gate up (non-loopback): pick from the provider list (oauth > basic).
     *
     * Internal + pure so it is unit-testable without a live server.
     */
    internal fun deriveAuthMode(
        authRequired: Boolean,
        providers: List<String>,
    ): DashboardAuthMode =
        if (!authRequired) {
            // Refined by the caller once it knows whether the SPA embedded a token.
            DashboardAuthMode.ALL
        } else {
            when {
                providers.contains("oauth") -> DashboardAuthMode.OAUTH
                providers.contains("basic") -> DashboardAuthMode.BASIC_AUTH
                else -> DashboardAuthMode.BASIC_AUTH // gate up, unknown provider → basic fallback
            }
        }

    /**
     * Probes `GET /api/status` and derives the [DashboardAuthMode] from the
     * authoritative `auth_required` + `auth_providers` fields.
     *
     * Returns null if the dashboard is unreachable. When [ProbeResult.extractedToken]
     * is non-null, the session token was found embedded in the dashboard SPA HTML
     * (loopback mode only) and can be auto-populated.
     */
    private suspend fun probeDashboardInternal(endpoint: ServerEndpoint): ProbeResult? {
        // Step 1: reachability + auth mode from the public status endpoint.
        val statusJson =
            try {
                val req =
                    Request
                        .Builder()
                        .url(endpoint.resolve("api/status").toString())
                        .get()
                        .build()
                val resp = probeClient.newCall(req).await()
                if (!resp.isSuccessful) return null
                resp.body.string()
            } catch (e: Exception) {
                Log.w(TAG, "Status probe failed: ${e.message}")
                return null // Dashboard unreachable
            }

        val authRequired: Boolean
        val providers: List<String>
        try {
            val node = OkHttpProvider.json.parseToJsonElement(statusJson).jsonObject
            authRequired = node["auth_required"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            providers =
                node["auth_providers"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.content }
                    .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Status parse failed: ${e.message}")
            return null
        }

        // No gate (loopback / --insecure): token mode. Try to grab the embedded token.
        if (!authRequired) {
            var extractedToken: String? = null
            try {
                val spaReq =
                    Request
                        .Builder()
                        .url(endpoint.resolve("").toString())
                        .get()
                        .build()
                val spaResp = probeClient.newCall(spaReq).await()
                val body = spaResp.body.string()
                val tokenMatch = Regex("""__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"""").find(body)
                extractedToken = tokenMatch?.groupValues?.getOrNull(1)
            } catch (e: Exception) {
                Log.w(TAG, "SPA token extraction failed: ${e.message}")
            }
            val mode =
                if (extractedToken != null) {
                    DashboardAuthMode.TOKEN_ONLY
                } else {
                    DashboardAuthMode.ALL
                }
            return ProbeResult(authMode = mode, extractedToken = extractedToken)
        }

        // Gate engaged (non-loopback bind). Derive from the provider list.
        return ProbeResult(authMode = deriveAuthMode(authRequired, providers))
    }

    /**
     * Result of a successful connect attempt.
     */
    private data class ConnectResult(
        val wsCredential: String,
        val cookies: List<Cookie> = emptyList(),
    )

    /**
     * Step 2: Connect using the detected auth mode.
     */
    fun connect() {
        val state = _uiState.value
        if (state.isLoading || state.authMode == null) return
        val endpoint =
            runCatching { ServerEndpoint.parseForBuild(state.baseUrl) }.getOrNull()
                ?: return

        _uiState.update { it.copy(isLoading = true, errorMessage = null, connectionSuccess = false) }

        connectionJob =
            viewModelScope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        when (state.authMode) {
                            DashboardAuthMode.TOKEN_ONLY -> {
                                val token = connectTokenOnly(endpoint, state.token)
                                if (token != null) ConnectResult(wsCredential = token) else null
                            }

                            DashboardAuthMode.BASIC_AUTH -> {
                                connectBasicAuth(endpoint, state.username, state.password)
                            }

                            DashboardAuthMode.ALL -> {
                                connectBasicAuth(endpoint, state.username, state.password)
                            }

                            DashboardAuthMode.OAUTH -> {
                                // TODO(issue #639): implement OAuth browser flow + WS ticket minting.
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        errorMessage = app.getString(R.string.auth_login_error_oauth_unsupported),
                                    )
                                }
                                null
                            }
                        }
                    }

                if (result != null) {
                    val parameter = if (state.authMode == DashboardAuthMode.TOKEN_ONLY) "token" else "ticket"
                    val ready = GatewayWebSocketProbe.check(endpoint, parameter, result.wsCredential)
                    val current = _uiState.value
                    if (current.baseUrl != state.baseUrl || current.authMode != state.authMode ||
                        current.token != state.token || current.username != state.username ||
                        current.password != state.password
                    ) {
                        _uiState.update { it.copy(isLoading = false) }
                        return@launch
                    }
                    if (!ready) {
                        connectionFailed()
                        return@launch
                    }
                    AuthManager.setBaseUrl(state.baseUrl)
                    if (state.authMode == DashboardAuthMode.TOKEN_ONLY) {
                        // Loopback mode — no session cookie; ensure any stale one
                        // is cleared so the jar only sends the Bearer token.
                        AuthManager.setSessionCookie(null)
                        AuthManager.setWsAuthParam("token")
                    } else {
                        AuthManager.syncCookieStoreForProfile(AuthManager.getSelectedProfileId())
                        CookieManager.cookieJar.saveFromResponse(endpoint.baseUrl, result.cookies)
                        // The probe consumed its ticket. The live socket will mint a fresh one from these cookies.
                        AuthManager.setWsAuthParam("ticket")
                    }
                    AuthManager.setToken(result.wsCredential)
                    ApiClient.rebuild()
                    HermesWsClient.connect()
                    PushTokenLifecycle.registerAsync()
                    _uiState.update { it.copy(isLoading = false, connectionSuccess = true) }
                }
            }
    }

    /**
     * Validate the token by calling /api/status with it.
     */
    private suspend fun connectTokenOnly(
        endpoint: ServerEndpoint,
        token: String,
    ): String? {
        if (token.isBlank()) {
            _uiState.update {
                it.copy(isLoading = false, errorMessage = app.getString(R.string.auth_login_error_token_required))
            }
            return null
        }

        val tempApi = ApiClient.createTempService(endpoint.baseUrl.toString(), token)
        val result = safeApiCall { tempApi.getSessions() }

        return when (result) {
            is com.m57.hermescontrol.data.remote.NetworkResult.Success -> {
                token
            }

            is com.m57.hermescontrol.data.remote.NetworkResult.Failure -> {
                val msg =
                    when (val err = result.error) {
                        is com.m57.hermescontrol.data.remote.NetworkError.Http -> {
                            when (err.code) {
                                401 -> app.getString(R.string.connect_error_401)
                                403 -> app.getString(R.string.connect_error_403)
                                else -> app.getString(R.string.connect_error_http_code, err.code)
                            }
                        }

                        is com.m57.hermescontrol.data.remote.NetworkError.AuthExpired -> {
                            app.getString(R.string.connect_error_401)
                        }

                        is com.m57.hermescontrol.data.remote.NetworkError.Connection -> {
                            app.getString(R.string.connect_error_connection_failed, err.cause.message ?: "")
                        }

                        is com.m57.hermescontrol.data.remote.NetworkError.Unknown -> {
                            app.getString(R.string.connect_error_connection_failed, err.cause.message ?: "")
                        }
                    }
                _uiState.update { it.copy(isLoading = false, errorMessage = msg) }
                null
            }
        }
    }

    /**
     * Authenticate with basic auth, then mint a WS ticket.
     * Returns the WS ticket and the session cookie for REST auth.
     */
    private suspend fun connectBasicAuth(
        endpoint: ServerEndpoint,
        username: String,
        password: String,
    ): ConnectResult? {
        if (username.isBlank()) {
            _uiState.update {
                it.copy(isLoading = false, errorMessage = app.getString(R.string.auth_login_error_username_required))
            }
            return null
        }
        if (password.isBlank()) {
            _uiState.update {
                it.copy(isLoading = false, errorMessage = app.getString(R.string.auth_login_error_password_required))
            }
            return null
        }

        val jsonBody = AuthPayloads.passwordLogin(username, password)

        val loginClient = isolatedProbeClient()
        try {
            val loginUrl = endpoint.resolve("auth/password-login")
            val loginReq =
                Request
                    .Builder()
                    .url(loginUrl)
                    .header("Content-Type", "application/json")
                    .post(jsonBody.toRequestBody())
                    .build()
            val cookies =
                loginClient.newCall(loginReq).await().use { response ->
                    if (!response.isSuccessful) {
                        val msg =
                            when (response.code) {
                                401 -> app.getString(R.string.connect_error_401)
                                403 -> app.getString(R.string.connect_error_403)
                                else -> app.getString(R.string.connect_error_http_code, response.code)
                            }
                        _uiState.update { it.copy(isLoading = false, errorMessage = msg) }
                        return null
                    }
                    Cookie.parseAll(loginUrl, response.headers)
                }
            val ticket = mintTicket(endpoint, loginClient, cookies)
            if (ticket == null) {
                connectionFailed()
                return null
            }
            return ConnectResult(ticket, cookies)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            connectionFailed()
            return null
        }
    }

    private fun isolatedProbeClient(): OkHttpClient =
        probeClient
            .newBuilder()
            .cookieJar(
                CookieJar.NO_COOKIES,
            ).followRedirects(false)
            .followSslRedirects(false)
            .build()

    private suspend fun mintTicket(
        endpoint: ServerEndpoint,
        client: OkHttpClient,
        cookies: List<Cookie>,
        profileScopedCookies: Boolean = false,
    ): String? {
        val url = endpoint.resolve("api/auth/ws-ticket")
        val header =
            cookies
                .filter { cookie ->
                    // A saved profile owns its alternate server addresses. Relax only the
                    // session-cookie host check; retain path and secure-transport checks.
                    val matchingUrl =
                        if (profileScopedCookies && isSessionCookie(cookie)) {
                            url.newBuilder().host(cookie.domain).build()
                        } else {
                            url
                        }
                    cookie.matches(matchingUrl)
                }.joinToString("; ") { "${it.name}=${it.value}" }
        if (header.isEmpty()) return null
        val request =
            Request
                .Builder()
                .url(url)
                .header("Cookie", header)
                .post("{}".toRequestBody())
                .build()
        return try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) return null
                OkHttpProvider.json
                    .decodeFromString<WsTicketResponse>(
                        response.body.string(),
                    ).ticket
                    .takeIf { it.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

class AuthLoginViewModelFactory(
    private val app: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthLoginViewModel(app) as T
}
