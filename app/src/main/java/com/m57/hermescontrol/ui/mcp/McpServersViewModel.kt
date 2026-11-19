package com.m57.hermescontrol.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.AddMcpServerRequest
import com.m57.hermescontrol.data.model.McpCatalogEntry
import com.m57.hermescontrol.data.model.McpCatalogInstallRequest
import com.m57.hermescontrol.data.model.McpOAuthFlowResponse
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.data.model.McpServerTestResponse
import com.m57.hermescontrol.data.model.McpServerToggleRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ActionProgressController
import com.m57.hermescontrol.ui.common.ActionProgressPhase
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AddServerMode { HTTP, Stdio }

data class McpServersUiState(
    val isLoading: Boolean = false,
    val servers: List<McpServer> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    // Add server form
    val showAddForm: Boolean = false,
    val addMode: AddServerMode = AddServerMode.HTTP,
    val addServerName: String = "",
    val addServerUrl: String = "",
    val addServerCommand: String = "",
    val addServerArgs: String = "",
    val addServerAuth: String = "none", // "none" | "header" | "oauth"
    val addServerBearerToken: String = "",
    val addingServer: Boolean = false,
    val lastAddedServerName: String? = null,
    // JSON Import (issue #1029)
    val showImportDialog: Boolean = false,
    val importJsonInput: String = "",
    val isImportingJson: Boolean = false,
    // Batch health & tool test results (issue #1029)
    val serverTestResults: Map<String, McpServerTestResponse> = emptyMap(),
    val testingServers: Set<String> = emptySet(),
    val isTestingAll: Boolean = false,
    // Env vars for editing
    val editingEnvFor: String? = null,
    val envKeyInput: String = "",
    val envValueInput: String = "",
    // Catalog
    val catalogQuery: String = "",
    val catalogEntries: List<McpCatalogEntry> = emptyList(),
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val installingCatalogEntry: String? = null,
    val catalogInstallEnv: Map<String, String> = emptyMap(),
    val catalogSetupEntry: String? = null,
    val unconfirmedCatalogEntries: Set<String> = emptySet(),
    val activeOAuthFlow: McpOAuthFlowResponse? = null,
)

class McpServersViewModel(
    private val profile: String? = null,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(McpServersUiState())
    val uiState: StateFlow<McpServersUiState> = _uiState.asStateFlow()
    private var preparedConnection = false
    private var catalogInstallJob: Job? = null

    private fun requestScope(): String =
        if (profile == AppScope.PROFILE) AppScope.connectionKey() else AuthManager.uiPreferenceScope()

    // ── Data loading ──────────────────────────────────────────

    fun loadServers() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getMcpServers(profile) } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        servers = data.servers.orEmpty(),
                        serverTestResults = emptyMap(),
                        testingServers = emptySet(),
                        unconfirmedCatalogEntries =
                            it.unconfirmedCatalogEntries - data.servers.map { server -> server.name }.toSet(),
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load MCP servers: $errorMsg",
                    )
                }
            },
        )
    }

    // ── Server toggle ────────────────────────────────────────

    fun toggleServer(server: McpServer) {
        val originalEnabled = server.enabled
        val targetEnabled = !originalEnabled

        _uiState.update { state ->
            state.copy(
                servers =
                    state.servers.map {
                        if (it.name == server.name) it.copy(enabled = targetEnabled) else it
                    },
            )
        }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.toggleMcpServer(
                            server.name,
                            McpServerToggleRequest(targetEnabled),
                            profile,
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Server '${server.name}' ${if (targetEnabled) "enabled" else "disabled"}",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    revertToggle(server.name, originalEnabled, "Failed to toggle server: ${result.error.message}")
                }
            }
        }
    }

    fun testServer(name: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    testingServers = it.testingServers + name,
                    toastMessage = "Testing server '$name'…",
                )
            }
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.testMcpServer(name, profile) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val response = result.data
                    val toolCount = response.tools.size
                    val tokenEst = McpTokenEstimator.estimateTokens(response.tools)
                    val tokenStr = McpTokenEstimator.formatTokenOverhead(toolCount, tokenEst)
                    _uiState.update { state ->
                        state.copy(
                            serverTestResults = state.serverTestResults + (name to response),
                            testingServers = state.testingServers - name,
                            toastMessage =
                                if (response.ok) {
                                    "Server '$name' tested — OK ($tokenStr)"
                                } else {
                                    "Server '$name' test failed: ${response.error ?: "unknown error"}"
                                },
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    val errResp = McpServerTestResponse(ok = false, error = result.error.message)
                    _uiState.update { state ->
                        state.copy(
                            serverTestResults = state.serverTestResults + (name to errResp),
                            testingServers = state.testingServers - name,
                            toastMessage = "Server '$name' test failed: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun testAllServers() {
        val enabledServers = _uiState.value.servers.filter { it.enabled }
        if (enabledServers.isEmpty()) {
            _uiState.update { it.copy(toastMessage = "No enabled servers to test") }
            return
        }

        viewModelScope.launch {
            val names = enabledServers.map { it.name }.toSet()
            _uiState.update {
                it.copy(
                    isTestingAll = true,
                    testingServers = it.testingServers + names,
                    toastMessage = "Testing ${enabledServers.size} server(s)…",
                )
            }

            val testJobs =
                enabledServers.map { server ->
                    async(Dispatchers.IO) {
                        val res = safeApiCall { ApiClient.hermesApi.testMcpServer(server.name, profile) }
                        server.name to res
                    }
                }

            val results = testJobs.awaitAll()
            val newResults = mutableMapOf<String, McpServerTestResponse>()
            var passCount = 0

            for ((name, result) in results) {
                when (result) {
                    is NetworkResult.Success -> {
                        newResults[name] = result.data
                        if (result.data.ok) passCount++
                    }

                    is NetworkResult.Failure -> {
                        newResults[name] = McpServerTestResponse(ok = false, error = result.error.message)
                    }
                }
            }

            val failCount = enabledServers.size - passCount
            _uiState.update { state ->
                state.copy(
                    isTestingAll = false,
                    serverTestResults = state.serverTestResults + newResults,
                    testingServers = state.testingServers - names,
                    toastMessage =
                        "Tested ${enabledServers.size} servers: $passCount passed, $failCount failed",
                )
            }
        }
    }

    fun deleteServer(name: String) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.deleteMcpServer(name, profile) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Server '$name' deleted") }
                    loadServers()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to delete server: ${result.error.message}") }
                }
            }
        }
    }

    // ── JSON Import (issue #1029) ────────────────────────────

    fun toggleImportDialog() {
        _uiState.update { it.copy(showImportDialog = !it.showImportDialog, importJsonInput = "") }
    }

    fun updateImportJsonInput(v: String) {
        _uiState.update { it.copy(importJsonInput = v) }
    }

    fun submitImportJson() {
        val input = _uiState.value.importJsonInput
        val requests = McpJsonParser.parse(input)
        if (requests.isEmpty()) {
            _uiState.update { it.copy(toastMessage = "Invalid JSON or no MCP servers found in input") }
            return
        }

        _uiState.update { it.copy(isImportingJson = true) }
        viewModelScope.launch {
            val importJobs =
                requests.map { req ->
                    async(Dispatchers.IO) {
                        val result = safeApiCall { ApiClient.hermesApi.addMcpServer(req, profile) }
                        req to result
                    }
                }

            val results = importJobs.awaitAll()
            var successCount = 0
            val errors = mutableListOf<String>()

            for ((req, result) in results) {
                when (result) {
                    is NetworkResult.Success -> successCount++
                    is NetworkResult.Failure -> errors.add("${req.name}: ${result.error.message}")
                }
            }

            _uiState.update { state ->
                val errSummary = errors.joinToString("; ")
                val msg =
                    when {
                        errors.isEmpty() -> "Successfully imported $successCount server(s)"
                        successCount > 0 -> "Imported $successCount server(s), ${errors.size} failed: $errSummary"
                        else -> "Failed to import: $errSummary"
                    }
                state.copy(
                    isImportingJson = false,
                    showImportDialog = false,
                    importJsonInput = "",
                    toastMessage = msg,
                )
            }
            loadServers()
        }
    }

    // ── Add server form ──────────────────────────────────────

    fun prepareConnection(serviceId: String) {
        if (preparedConnection) return
        preparedConnection = true
        _uiState.update {
            it.copy(showAddForm = false, addServerName = serviceId)
        }
    }

    fun clearConnectionForm() {
        dismissOAuthFlow()
        stopEditingEnv()
        catalogInstallJob?.cancel()
        _uiState.update {
            it.copy(
                addServerBearerToken = "",
                importJsonInput = "",
                showImportDialog = false,
                catalogInstallEnv = emptyMap(),
                catalogSetupEntry = null,
                installingCatalogEntry = null,
            )
        }
    }

    fun toggleAddForm() {
        _uiState.update { it.copy(showAddForm = !it.showAddForm) }
    }

    fun setAddMode(mode: AddServerMode) {
        _uiState.update { it.copy(addMode = mode) }
    }

    fun updateAddServerName(v: String) {
        _uiState.update { it.copy(addServerName = v) }
    }

    fun updateAddServerUrl(v: String) {
        _uiState.update { it.copy(addServerUrl = v) }
    }

    fun updateAddServerCommand(v: String) {
        _uiState.update { it.copy(addServerCommand = v) }
    }

    fun updateAddServerArgs(v: String) {
        _uiState.update { it.copy(addServerArgs = v) }
    }

    fun updateAddServerAuth(v: String) {
        _uiState.update { it.copy(addServerAuth = v) }
    }

    fun updateAddServerBearerToken(v: String) {
        _uiState.update { it.copy(addServerBearerToken = v) }
    }

    fun submitAddServer() {
        val state = _uiState.value
        if (state.addingServer || state.isLoading) return
        if (state.addServerName.isBlank()) {
            _uiState.update { it.copy(toastMessage = "Server name is required") }
            return
        }
        if (state.servers.any { it.name == state.addServerName.trim() }) {
            _uiState.update { it.copy(toastMessage = "同じ名前の接続先があります。別の名前を指定してください。") }
            return
        }
        _uiState.update { it.copy(addingServer = true) }
        viewModelScope.launch {
            val request =
                AddMcpServerRequest(
                    name = state.addServerName.trim(),
                    url = if (state.addMode == AddServerMode.HTTP) state.addServerUrl.trim().ifBlank { null } else null,
                    command =
                        if (state.addMode == AddServerMode.Stdio) {
                            state.addServerCommand.trim().ifBlank { null }
                        } else {
                            null
                        },
                    args =
                        if (state.addMode == AddServerMode.Stdio && state.addServerArgs.isNotBlank()) {
                            state.addServerArgs
                                .trim()
                                .split("\\s+".toRegex())
                                .filter { it.isNotEmpty() }
                        } else {
                            null
                        },
                    auth =
                        if (state.addMode == AddServerMode.HTTP && state.addServerAuth != "none") {
                            state.addServerAuth
                        } else {
                            null
                        },
                    bearerToken =
                        if (state.addMode == AddServerMode.HTTP && state.addServerAuth == "header") {
                            state.addServerBearerToken.trim().ifBlank { null }
                        } else {
                            null
                        },
                )
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.addMcpServer(request, profile) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            addingServer = false,
                            lastAddedServerName = request.name,
                            showAddForm = false,
                            addServerName = "",
                            addServerUrl = "",
                            addServerCommand = "",
                            addServerArgs = "",
                            addServerAuth = "none",
                            addServerBearerToken = "",
                            toastMessage = "Server '${request.name}' added",
                        )
                    }
                    loadServers()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            addingServer = false,
                            toastMessage = "Failed to add server: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Env var editing ──────────────────────────────────────

    fun startEditingEnv(server: McpServer) {
        _uiState.update { it.copy(editingEnvFor = server.name, envKeyInput = "", envValueInput = "") }
    }

    fun stopEditingEnv() {
        _uiState.update { it.copy(editingEnvFor = null, envKeyInput = "", envValueInput = "") }
    }

    fun updateEnvKey(v: String) {
        _uiState.update { it.copy(envKeyInput = v) }
    }

    fun updateEnvValue(v: String) {
        _uiState.update { it.copy(envValueInput = v) }
    }

    fun addEnvVar(serverName: String) {
        val state = _uiState.value
        val key = state.envKeyInput.trim()
        val value = state.envValueInput.trim()
        if (key.isBlank()) {
            _uiState.update { it.copy(toastMessage = "Key is required") }
            return
        }
        viewModelScope.launch {
            val existingEnv = state.servers.find { it.name == serverName }?.env ?: emptyMap()
            val updatedEnv = existingEnv + (key to value)
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.updateMcpServer(serverName, mapOf("env" to updatedEnv), profile) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            envKeyInput = "",
                            envValueInput = "",
                            toastMessage = "Env var '$key' added",
                        )
                    }
                    loadServers()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to add env var: ${result.error.message}") }
                }
            }
        }
    }

    fun removeEnvVar(
        serverName: String,
        key: String,
    ) {
        viewModelScope.launch {
            val state = _uiState.value
            val existingEnv = state.servers.find { it.name == serverName }?.env ?: emptyMap()
            val updatedEnv = existingEnv - key
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.updateMcpServer(serverName, mapOf("env" to updatedEnv), profile) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Env var '$key' removed") }
                    loadServers()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to remove env var: ${result.error.message}") }
                }
            }
        }
    }

    // ── Catalog ──────────────────────────────────────────────

    fun loadCatalog() {
        if (_uiState.value.catalogLoading) return
        val api = ApiClient.hermesApi
        val scope = requestScope()
        _uiState.update { it.copy(catalogLoading = true, catalogError = null) }
        viewModelScope.launch {
            val result = safeApiCall { api.getMcpCatalog(profile) }
            if (scope != requestScope()) {
                _uiState.update { it.copy(catalogLoading = false, catalogEntries = emptyList()) }
                return@launch
            }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            catalogLoading = false,
                            catalogEntries = result.data.entries,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            catalogLoading = false,
                            catalogError = "接続方法を取得できません。再読込するか、手動設定を開いてください。",
                        )
                    }
                }
            }
        }
    }

    fun updateCatalogQuery(v: String) {
        _uiState.update { it.copy(catalogQuery = v) }
    }

    fun installCatalogEntry(entry: McpCatalogEntry) {
        val state = _uiState.value
        if (state.installingCatalogEntry != null || state.isLoading ||
            entry.name in state.unconfirmedCatalogEntries
        ) {
            return
        }
        if (entry.installed || state.servers.any { it.name == entry.name }) return
        val declared = entry.env.orEmpty()
        val values = if (state.catalogSetupEntry == entry.name) state.catalogInstallEnv else emptyMap()
        if (declared.any { it.required && values[it.key].isNullOrBlank() }) {
            _uiState.update { it.copy(toastMessage = "必須項目を入力してください。") }
            return
        }
        val request = McpCatalogInstallRequest(entry.name, values.filterKeys { key -> declared.any { it.key == key } })
        val api = ApiClient.hermesApi
        val scope = requestScope()
        _uiState.update {
            it.copy(
                installingCatalogEntry = entry.name,
                catalogSetupEntry = entry.name,
                catalogInstallEnv = values,
            )
        }
        catalogInstallJob =
            viewModelScope.launch {
                try {
                    // Do not overwrite a connection installed since the catalog was loaded.
                    val inventory = safeApiCall { api.getMcpServers(profile) }
                    currentCoroutineContext().ensureActive()
                    if (scope != requestScope()) return@launch
                    if (inventory !is NetworkResult.Success) {
                        _uiState.update { it.copy(toastMessage = "既存の接続先を確認できません。再読込してください。") }
                        return@launch
                    }
                    if (inventory.data.servers.any { it.name == entry.name }) {
                        _uiState.update {
                            it.copy(
                                servers = inventory.data.servers,
                                toastMessage = "登録済みの接続先から認証・設定を開いてください。",
                            )
                        }
                        loadCatalog()
                        return@launch
                    }
                    _uiState.update { it.copy(unconfirmedCatalogEntries = it.unconfirmedCatalogEntries + entry.name) }
                    // Installation can write credentials and start a process. Never replay an uncertain POST.
                    val result = safeApiCall(retries = 0) { api.installMcpCatalogEntry(request, profile) }
                    currentCoroutineContext().ensureActive()
                    if (scope != requestScope()) return@launch
                    if (result !is NetworkResult.Success || !result.data.ok) {
                        if (result is NetworkResult.Failure && result.error is NetworkError.Http &&
                            result.error.code in 400..499
                        ) {
                            _uiState.update {
                                it.copy(
                                    unconfirmedCatalogEntries = it.unconfirmedCatalogEntries - entry.name,
                                )
                            }
                        }
                        _uiState.update { it.copy(toastMessage = "接続準備を完了できませんでした。再読込して状態を確認してください。") }
                        return@launch
                    }
                    if (result.data.background) {
                        val action = result.data.action?.takeIf { it.isNotBlank() } ?: return@launch
                        val tracker =
                            ActionProgressController(
                                scope = viewModelScope,
                                statusRequest = { name ->
                                    if (scope == requestScope()) {
                                        safeApiCall { api.getActionStatus(name) }
                                    } else {
                                        NetworkResult.Failure(
                                            NetworkError.Unknown(
                                                "connection_changed",
                                                IllegalStateException("connection_changed"),
                                            ),
                                        )
                                    }
                                },
                            )
                        val outcome =
                            try {
                                tracker.markStarted(action)
                                tracker.state.first {
                                    it.phase == ActionProgressPhase.SUCCEEDED ||
                                        it.phase == ActionProgressPhase.FAILED
                                }
                            } finally {
                                tracker.dismiss()
                            }
                        if (scope != requestScope()) return@launch
                        if (outcome.phase != ActionProgressPhase.SUCCEEDED) {
                            if (outcome.exitCode != null) {
                                _uiState.update {
                                    it.copy(
                                        unconfirmedCatalogEntries =
                                            it.unconfirmedCatalogEntries - entry.name,
                                    )
                                }
                            }
                            _uiState.update { it.copy(toastMessage = "接続準備の完了を確認できません。再読込して状態を確認してください。") }
                            loadServers()
                            loadCatalog()
                            return@launch
                        }
                    }
                    _uiState.update {
                        it.copy(
                            lastAddedServerName = result.data.name ?: entry.name,
                            unconfirmedCatalogEntries = it.unconfirmedCatalogEntries - entry.name,
                            catalogInstallEnv = emptyMap(),
                            catalogSetupEntry = null,
                            toastMessage = "接続先を準備しました。必要なアカウント認証は接続先から進めてください。",
                        )
                    }
                    loadServers()
                    loadCatalog()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } finally {
                    if (scope == requestScope()) {
                        _uiState.update {
                            it.copy(installingCatalogEntry = null)
                        }
                    }
                }
            }
    }

    fun beginCatalogSetup(entry: McpCatalogEntry) {
        if (_uiState.value.installingCatalogEntry != null) return
        _uiState.update {
            it.copy(
                catalogSetupEntry = entry.name,
                catalogInstallEnv = if (it.catalogSetupEntry == entry.name) it.catalogInstallEnv else emptyMap(),
            )
        }
    }

    fun updateCatalogEnvVar(
        key: String,
        value: String,
    ) {
        val entry = _uiState.value.catalogEntries.find { it.name == _uiState.value.catalogSetupEntry } ?: return
        if (entry.env.orEmpty().none { it.key == key } || _uiState.value.installingCatalogEntry != null) return
        _uiState.update { it.copy(catalogInstallEnv = it.catalogInstallEnv + (key to value)) }
    }

    // ── OAuth ─────────────────────────────────────────────────

    private var oauthPollJob: kotlinx.coroutines.Job? = null

    fun startMcpOAuthFlow(
        server: McpServer,
        onOpenBrowser: (String) -> Unit,
    ) {
        oauthPollJob?.cancel()
        _uiState.update { it.copy(toastMessage = "ログイン画面を準備中…") }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.authMcpServer(server.name, profile) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val flow = result.data
                    _uiState.update { it.copy(activeOAuthFlow = flow) }
                    flow.authorizationUrl?.let { url ->
                        onOpenBrowser(url)
                        startPollingOAuthFlow(flow.flowId)
                    } ?: run {
                        _uiState.update {
                            it.copy(
                                toastMessage = "ログイン画面を開けませんでした。詳細設定から確認してください。",
                            )
                        }
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "ログインを開始できませんでした。再読込してもう一度試してください。") }
                }
            }
        }
    }

    private fun startPollingOAuthFlow(flowId: String) {
        oauthPollJob?.cancel()
        oauthPollJob =
            viewModelScope.launch {
                var polling = true
                while (polling) {
                    kotlinx.coroutines.delay(2000)
                    val result =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getMcpOAuthFlowStatus(flowId, profile) }
                        }
                    when (result) {
                        is NetworkResult.Success -> {
                            val flow = result.data
                            _uiState.update { it.copy(activeOAuthFlow = flow) }
                            when (flow.status) {
                                "approved", "completed" -> {
                                    polling = false
                                    _uiState.update {
                                        it.copy(
                                            activeOAuthFlow = null,
                                            toastMessage = OAUTH_CONNECTED_MESSAGE,
                                        )
                                    }
                                    loadServers()
                                }

                                "error" -> {
                                    polling = false
                                    _uiState.update {
                                        it.copy(
                                            activeOAuthFlow = null,
                                            toastMessage = "ログインを完了できませんでした。もう一度試してください。",
                                        )
                                    }
                                }

                                "authorization_required" -> {
                                    // Keep polling
                                }

                                else -> {
                                    // Check if worker is done or expired
                                    // Continue polling until status transitions
                                }
                            }
                        }

                        is NetworkResult.Failure -> {
                            // Keep polling or stop after too many failures? Let's just log/toast n retry a few times
                        }
                    }
                }
            }
    }

    fun dismissOAuthFlow() {
        oauthPollJob?.cancel()
        oauthPollJob = null
        _uiState.update { it.copy(activeOAuthFlow = null) }
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun revertToggle(
        name: String,
        originalEnabled: Boolean,
        errorMsg: String,
    ) {
        _uiState.update { state ->
            state.copy(
                servers =
                    state.servers.map {
                        if (it.name == name) it.copy(enabled = originalEnabled) else it
                    },
                toastMessage = errorMsg,
            )
        }
    }

    companion object {
        const val OAUTH_CONNECTED_MESSAGE = "接続しました。"
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
