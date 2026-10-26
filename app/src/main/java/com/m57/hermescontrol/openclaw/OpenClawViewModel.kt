package com.m57.hermescontrol.openclaw

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

internal data class ClawUiState(
    val connection: ClawConnection = ClawConnection.DISCONNECTED,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val saving: Boolean = false,
    val configured: Boolean = false,
    val sessionKey: String = "",
    val sessions: List<ClawSession> = emptyList(),
    val messages: List<ClawMessage> = emptyList(),
    val streaming: String = "",
    val models: List<ClawModel> = emptyList(),
    val model: String? = null,
    val effort: String? = null,
    val efforts: List<String> = emptyList(),
    val canSend: Boolean = false,
    val canPatch: Boolean = false,
    val canAdmin: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val defaultModel: String? = null,
    val defaultEffort: String? = null,
    val defaultLoaded: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val automaticTitlesEnabled: Boolean = true,
    val titleKeySaved: Boolean = false,
    val titleKeyFromServer: Boolean = false,
)

internal class OpenClawViewModel(
    application: Application,
    private val owner: String,
    private val store: OpenClawStore = OpenClawStore(application, owner),
) : AndroidViewModel(application) {
    private val state =
        MutableStateFlow(
            ClawUiState(
                configured = !store.read("url").isNullOrBlank(),
                notificationsEnabled = store.read("notifications") != "false",
                automaticTitlesEnabled = store.read("title-enabled") != "false",
                titleKeySaved = !store.read("title-api-key").isNullOrBlank(),
            ),
        )
    val uiState = state.asStateFlow()
    private var client: OpenClawClient? = null
    private val deviceCredentialsLock = Any()
    private var connectJob: Job? = null
    private var sessionJob: Job? = null
    private var agentId = "main"
    private var defaults = obj()
    private var row = obj()
    private var activeRun: String? = null
    private var streamOrder = ClawStreamOrder()
    private var fresh = false
    private val titleJobs = mutableMapOf<String, Job>()
    private val titles = OpenClawTitles()
    private var connectionEpoch = 0L
    private var pendingRuns = clawPendingRuns(store.read(store.sessionName("pending-runs", "all")))

    private fun savePendingRuns() {
        store.write(store.sessionName("pending-runs", "all"), encodeClawPendingRuns(pendingRuns))
    }

    private fun showNotice(notice: ClawNotice) {
        val title =
            state.value.sessions
                .firstOrNull { it.key == notice.session }
                ?.title ?: "OpenClawの会話"
        OpenClawNotifications.show(getApplication(), store, notice, title)
    }

    private suspend fun reconcilePending(current: OpenClawClient) {
        for ((key, run) in pendingRuns.toMap()) {
            if (client !== current || !ownerIsCurrent()) return
            if (pendingRuns[key] != run) continue
            if ("operator.read" !in current.scopes && "operator.admin" !in current.scopes) return
            val history =
                pendingRead(current, "chat.history", obj("sessionKey" to value(key), "limit" to JsonPrimitive(200)))
            if (client !== current || !ownerIsCurrent()) return
            if (pendingRuns[key] != run || history?.let { clawPendingRunActive(run, it) } == true) continue
            val notice =
                history?.let { clawRecoveredNotice(key, run, it) }
                    ?: pendingRead(
                        current,
                        "agent.wait",
                        obj("runId" to value(run.id), "timeoutMs" to JsonPrimitive(0)),
                    )?.let { clawWaitRecoveredNotice(key, run, it) }
            if (client !== current || !ownerIsCurrent()) return
            if (pendingRuns[key] != run || notice == null) continue
            pendingRuns.remove(key)
            savePendingRuns()
            showNotice(notice)
            if (state.value.sessionKey == key) {
                activeRun = null
                state.update { it.copy(busy = false) }
                try {
                    loadHistory(current, key)
                } catch (cancelled: CancellationException) {
                    if (cancelled !is TimeoutCancellationException) throw cancelled
                } catch (_: Exception) {
                    if (client === current && ownerIsCurrent() && state.value.sessionKey == key) {
                        state.update { it.copy(loading = false) }
                    }
                }
            }
        }
    }

    private suspend fun pendingRead(
        current: OpenClawClient,
        method: String,
        params: JsonObject,
    ): JsonObject? {
        if (client !== current || !ownerIsCurrent() || !current.supports(method)) return null
        return try {
            current.rpc(method, params).takeIf { client === current && ownerIsCurrent() }
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
            null
        } catch (_: Exception) {
            null
        }
    }

    fun setNotifications(enabled: Boolean) {
        store.write("notifications", enabled.toString())
        state.update { it.copy(notificationsEnabled = enabled) }
    }

    fun saveTitleKey(key: String) {
        connectionEpoch++
        titleJobs.values.forEach { it.cancel() }
        titleJobs.clear()
        store.write("title-api-key", key.trim().takeIf { it.isNotBlank() })
        state.update { it.copy(titleKeySaved = key.isNotBlank(), notice = "自動命名の設定を保存しました") }
        store.write(store.sessionName("title-attempt-v2", state.value.sessionKey), null)
        nameSession(state.value.sessionKey, state.value.messages)
    }

    fun setAutomaticTitles(enabled: Boolean) {
        store.write("title-enabled", enabled.toString())
        connectionEpoch++
        titleJobs.values.forEach { it.cancel() }
        titleJobs.clear()
        state.update { it.copy(automaticTitlesEnabled = enabled, titleKeyFromServer = false) }
        if (enabled) nameSession(state.value.sessionKey, state.value.messages)
    }

    private fun nameSession(
        key: String,
        messages: List<ClawMessage>,
    ) {
        if (key.isBlank() || owner != ownerScope()) return
        val first = messages.firstOrNull { it.role == "user" }?.text ?: return
        val saved = store.sessionName("title", key)

        fun display(title: String) {
            state.update { current ->
                val entries = current.sessions.filterNot { it.key == key } + ClawSession(key, title)
                current.copy(sessions = entries)
            }
        }
        val stored = store.read(saved)
        if (stored != null) {
            display(stored)
        } else {
            val fallback = clawTitle(first).ifBlank { "新しい会話" }
            store.write(saved, fallback)
            display(fallback)
        }
        val attempted = store.sessionName("title-attempt-v2", key)
        val generated = store.sessionName("title-generated", key)
        if (!state.value.automaticTitlesEnabled || store.read(generated) == "true" ||
            titleJobs[key]?.isActive == true
        ) {
            return
        }
        val lastAttempt = store.read(attempted)?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - lastAttempt < 3_600_000L) return
        val epoch = connectionEpoch
        titleJobs[key] =
            viewModelScope.launch {
                try {
                    val localKey = store.read("title-api-key")
                    val apiKey =
                        resolveClawTitleKey(
                            localKey,
                            { epoch == connectionEpoch && ownerIsCurrent() && state.value.automaticTitlesEnabled },
                            enabled = state.value.automaticTitlesEnabled,
                        )
                            ?: return@launch
                    if (epoch != connectionEpoch || !ownerIsCurrent() ||
                        !state.value.automaticTitlesEnabled
                    ) {
                        return@launch
                    }
                    state.update { it.copy(titleKeyFromServer = localKey.isNullOrBlank()) }
                    store.write(attempted, System.currentTimeMillis().toString())
                    val title = titles.generate(apiKey, first)
                    if (epoch != connectionEpoch || !ownerIsCurrent() ||
                        !state.value.automaticTitlesEnabled
                    ) {
                        return@launch
                    }
                    store.write(saved, title)
                    store.write(generated, "true")
                    display(title)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the first-message title. A title failure never blocks chat or uses a paid model.
                }
            }
    }

    init {
        viewModelScope.launch {
            com.m57.hermescontrol.data.remote.NetworkMonitor.networkChanges.collect { available ->
                if (available && owner == ownerScope() && client?.connectionState?.value == ClawConnection.CONNECTED) {
                    client?.close()
                }
            }
        }
        // Logout/server changes retire this connection; picking another Hermes bot does not.
        viewModelScope.launch {
            AuthManager.contextFlow.collect {
                if (!ownerIsCurrent()) {
                    disconnect()
                    state.value = ClawUiState(error = "接続先が変わりました。AgentからOpenClawを開き直してください。")
                }
            }
        }
    }

    fun savedUrl(): String = store.read("url").orEmpty()

    fun hasToken(): Boolean = !store.read("token").isNullOrBlank()

    fun saveConnection(
        url: String,
        replacementToken: String?,
        clearToken: Boolean = false,
    ) {
        try {
            val endpoint = OpenClawEndpoint.parse(url)
            val sameEndpoint = savedUrl() == endpoint.webSocketUrl
            val token =
                if (clearToken) {
                    ""
                } else {
                    replacementToken?.takeIf { it.isNotBlank() }
                        ?: if (sameEndpoint) store.read("token").orEmpty() else ""
                }
            disconnect()
            synchronized(deviceCredentialsLock) {
                store.write("device-token", null)
                store.write("device-scopes", null)
                store.saveConnection(endpoint.webSocketUrl, token)
            }
            pendingRuns = clawPendingRuns(store.read(store.sessionName("pending-runs", "all")))
            activeRun = null
            fresh = false
            state.value =
                ClawUiState(
                    configured = true,
                    notificationsEnabled = store.read("notifications") != "false",
                    automaticTitlesEnabled = store.read("title-enabled") != "false",
                    titleKeySaved = !store.read("title-api-key").isNullOrBlank(),
                    notice = "接続先を保存しました",
                )
            connect()
        } catch (_: Exception) {
            state.update { it.copy(error = "GatewayのURLを確認してください。例: wss://サーバー名/ またはプライベートネットワーク内のws://アドレス:ポート/") }
        }
    }

    private fun ownerIsCurrent(): Boolean = owner == ownerScope() && !AuthManager.tokenFlow.value.isNullOrBlank()

    fun ensureConnected() {
        if (ownerIsCurrent() && savedUrl().isNotBlank() && connectJob?.isActive != true) {
            state.update {
                it.copy(
                    configured = true,
                    notificationsEnabled = store.read("notifications") != "false",
                    automaticTitlesEnabled = store.read("title-enabled") != "false",
                    titleKeySaved = !store.read("title-api-key").isNullOrBlank(),
                )
            }
            connect()
        }
    }

    fun connect() {
        disconnect()
        if (savedUrl().isBlank() || !ownerIsCurrent()) return
        connectJob =
            viewModelScope.launch {
                maintainClawConnection(
                    connect = { ready -> connectOnce(ready) },
                    onFailure = { error, retry ->
                        state.update {
                            it.copy(
                                connection = if (retry) ClawConnection.CONNECTING else ClawConnection.DISCONNECTED,
                                loading = false,
                                canSend = false,
                                canPatch = false,
                                canAdmin = false,
                                error = if (retry) "接続が切れました。自動で再接続しています。" else failureMessage(error),
                            )
                        }
                    },
                )
            }
    }

    private suspend fun connectOnce(ready: () -> Unit) {
        state.update {
            it.copy(
                connection = ClawConnection.CONNECTING,
                loading = true,
                error = null,
                canSend = false,
                canPatch = false,
                canAdmin = false,
                defaultLoaded = false,
            )
        }
        val current =
            withContext(Dispatchers.IO) {
                val deviceToken = store.read("device-token")
                OpenClawClient(
                    OpenClawEndpoint.parse(savedUrl()),
                    store.identity(),
                    deviceToken ?: store.read("token"),
                    store
                        .read("device-scopes")
                        ?.split(',')
                        ?.filter { it.isNotBlank() }
                        ?.takeIf { deviceToken != null && it.isNotEmpty() }
                        ?: listOf("operator.read", "operator.write", "operator.admin"),
                )
            }
        client = current
        streamOrder = ClawStreamOrder()
        try {
            coroutineScope {
                launch { current.events.collect { if (client === current) event(it) } }
                launch {
                    current.connectionState.collect { connection ->
                        if (client === current) {
                            state.update {
                                it.copy(
                                    connection = connection,
                                    canSend =
                                        connection == ClawConnection.CONNECTED && current.canWrite() &&
                                            current.supports("chat.send"),
                                    canPatch =
                                        connection == ClawConnection.CONNECTED && current.canWrite() &&
                                            current.supports("sessions.patch"),
                                    canAdmin = connection == ClawConnection.CONNECTED && it.canAdmin,
                                )
                            }
                        }
                    }
                }
                val hello = current.connect()
                check(client === current)
                hello.obj("auth")?.string("deviceToken")?.let { issued ->
                    withContext(Dispatchers.IO) {
                        synchronized(deviceCredentialsLock) {
                            if (client === current && issued != store.read("device-token")) {
                                store.write("device-token", issued)
                                store.write("device-scopes", current.scopes.joinToString(","))
                            }
                        }
                    }
                }
                defaults = hello.obj("snapshot")?.obj("sessionDefaults") ?: obj()
                if (current.supports("agents.list")) {
                    agentId = current.rpc("agents.list").string("defaultId") ?: "main"
                }
                state.update {
                    it.copy(
                        canAdmin =
                            current.canAdmin() && current.supports("config.get") &&
                                current.supports("config.patch"),
                    )
                }
                if (current.supports("models.list")) {
                    val catalog = current.rpc("models.list")
                    state.update {
                        it.copy(
                            models =
                                catalog.array("models").mapNotNull { item ->
                                    val model = item as? JsonObject ?: return@mapNotNull null
                                    val id = model.string("id") ?: return@mapNotNull null
                                    val provider = model.string("provider")
                                    ClawModel(
                                        if (provider == null ||
                                            id.startsWith("$provider/")
                                        ) {
                                            id
                                        } else {
                                            "$provider/$id"
                                        },
                                        model.string("name") ?: id,
                                    )
                                },
                        )
                    }
                }
                refreshSessions(current)
                val key =
                    state.value.sessionKey.takeIf { it.isNotBlank() } ?: store.read("last-session")
                        ?: defaults.string("mainSessionKey")
                        ?: "agent:$agentId:main"
                activeRun = pendingRuns[key]?.id
                state.update { it.copy(sessionKey = key, busy = key in pendingRuns) }
                if (!fresh) loadHistory(current, key) else state.update { it.copy(loading = false) }
                ready()
                launch {
                    while (isActive) {
                        try {
                            reconcilePending(current)
                        } catch (cancelled: CancellationException) {
                            if (cancelled !is TimeoutCancellationException) throw cancelled
                        } catch (_: Exception) {
                            // The connection loop handles a broken transport; never retry writes.
                        }
                        delay(15_000)
                    }
                }
                current.connectionState.first { it == ClawConnection.DISCONNECTED }
                throw ClawFailure("DISCONNECTED")
            }
        } finally {
            current.close()
            if (client === current) client = null
        }
    }

    private suspend fun refreshSessions(current: OpenClawClient) {
        if (client !== current || !ownerIsCurrent()) return
        val result = current.rpc("sessions.list", obj("limit" to JsonPrimitive(100)))
        if (client !== current || !ownerIsCurrent()) return
        defaults = result.obj("defaults") ?: defaults
        val rows = result.array("sessions").mapNotNull { it as? JsonObject }
        state.update {
            it.copy(
                sessions =
                    rows
                        .filter { entry ->
                            entry.string("key")?.startsWith("agent:$agentId:") == true
                        }.mapNotNull { entry ->
                            entry.string("key")?.let {
                                ClawSession(
                                    it,
                                    store.read(store.sessionName("title", it))
                                        ?: entry.string("label") ?: entry.string("displayName")
                                        ?: entry.string("derivedTitle")
                                        ?: it,
                                )
                            }
                        },
            )
        }
        row = rows.firstOrNull { it.string("key") == state.value.sessionKey } ?: obj()
        updateSettings(row)
    }

    private fun updateSettings(entry: JsonObject) {
        state.update {
            it.copy(
                model = entry.string("model") ?: defaults.string("model"),
                effort =
                    entry.string("thinkingLevel") ?: entry.string("thinkingDefault")
                        ?: defaults.string("thinkingDefault")
                        ?: defaults.string("thinkingLevel"),
                // Metadata owns supported values. Unknown metadata never fabricates effort options.
                efforts = strings(entry["thinkingLevels"]).ifEmpty { strings(defaults["thinkingLevels"]) },
            )
        }
    }

    fun openNotifiedSession(key: String) {
        if (owner != ownerScope() || !key.startsWith("agent:")) return
        if (client?.connectionState?.value != ClawConnection.CONNECTED) {
            activeRun = pendingRuns[key]?.id
            state.update {
                it.copy(
                    sessionKey = key,
                    messages = emptyList(),
                    streaming = "",
                    busy = key in pendingRuns,
                )
            }
            store.write("last-session", key)
            ensureConnected()
        } else {
            selectSession(key)
        }
    }

    fun selectSession(key: String) {
        if (state.value.saving || key == state.value.sessionKey) return
        val current = client ?: return
        sessionJob?.cancel()
        fresh = false
        activeRun = pendingRuns[key]?.id
        state.update {
            it.copy(
                sessionKey = key,
                messages = emptyList(),
                streaming = "",
                loading = true,
                error = null,
                busy =
                    key in pendingRuns,
            )
        }
        sessionJob =
            viewModelScope.launch {
                try {
                    loadHistory(current, key)
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (
                    error: Exception,
                ) {
                    state.update { it.copy(loading = false, error = failureMessage(error)) }
                }
            }
    }

    fun newSession() {
        if (state.value.saving || state.value.loading) return
        sessionJob?.cancel()
        val key = "agent:$agentId:hermes-mobile:${UUID.randomUUID()}"
        fresh = true
        activeRun = null
        row = obj()
        state.update {
            it.copy(
                sessionKey = key,
                messages = emptyList(),
                streaming = "",
                busy = false,
                loading = false,
                error = null,
            )
        }
        updateSettings(obj())
        store.write("last-session", key)
    }

    private suspend fun loadHistory(
        current: OpenClawClient,
        key: String,
    ) {
        if (client !== current || !ownerIsCurrent()) return
        val result = current.rpc("chat.history", obj("sessionKey" to value(key), "limit" to JsonPrimitive(200)))
        if (client !== current || !ownerIsCurrent() || state.value.sessionKey != key) return
        val messages =
            result.array("messages").mapIndexedNotNull { index, element ->
                val entry = element as? JsonObject ?: return@mapIndexedNotNull null
                val role = entry.string("role") ?: return@mapIndexedNotNull null
                val text = messageText(entry)
                if (role !in listOf("user", "assistant") ||
                    text.isBlank()
                ) {
                    null
                } else {
                    ClawMessage(entry.string("id") ?: "$index", role, text)
                }
            }
        state.update { it.copy(messages = messages, streaming = "", loading = false) }
        updateSettings(result)
        store.write("last-session", key)
        refreshSessions(current)
        if (client !== current || !ownerIsCurrent() || state.value.sessionKey != key) return
        fresh = false
        nameSession(key, messages)
    }

    fun send(
        text: String,
        onAccepted: () -> Unit,
    ) {
        val before = state.value
        if (text.isBlank() || before.sessionKey.isBlank() || before.loading || before.busy || before.saving ||
            !before.canSend
        ) {
            return
        }
        val current = client ?: return
        val key = before.sessionKey
        val id = UUID.randomUUID().toString()
        activeRun = id
        pendingRuns[key] = ClawPendingRun(id, text, before.messages.count { it.role == "user" && it.text == text })
        savePendingRuns()
        state.update {
            it.copy(
                busy = true,
                streaming = "",
                error = null,
                notice = null,
                messages = it.messages + ClawMessage(id, "user", text),
            )
        }
        viewModelScope.launch {
            try {
                val ack =
                    current.rpc(
                        "chat.send",
                        obj(
                            "sessionKey" to value(key),
                            "message" to value(text),
                            "idempotencyKey" to value(id),
                        ),
                    )
                if (client !== current) return@launch
                if (pendingRuns[key]?.id == id) {
                    val accepted = ack.string("runId") ?: id
                    pendingRuns[key] = pendingRuns.getValue(key).copy(id = accepted)
                    savePendingRuns()
                    if (state.value.sessionKey == key) activeRun = accepted
                }
                if (state.value.sessionKey == key) onAccepted()
                if (state.value.sessionKey == key) store.write("last-session", key)
                nameSession(key, listOf(ClawMessage(id, "user", text)))
                if (state.value.sessionKey == key) fresh = false
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (client !== current) return@launch
                if ((error as? ClawFailure)?.definitive == true) {
                    pendingRuns.remove(key)
                    savePendingRuns()
                }
                if (state.value.sessionKey != key) return@launch
                state.update {
                    it.copy(
                        busy = key in pendingRuns,
                        error = "送信の受付を確認できません。入力を残しました。再送前に履歴を更新してください。 ${failureMessage(error)}",
                    )
                }
            }
        }
    }

    fun abort() {
        val current = client ?: return
        val key = state.value.sessionKey
        viewModelScope.launch {
            try {
                current.rpc("chat.abort", obj("sessionKey" to value(key), "runId" to value(activeRun)))
            } catch (
                cancelled: CancellationException,
            ) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(error = failureMessage(error)) }
            }
        }
    }

    fun refresh() {
        val current = client ?: return connect()
        if (state.value.saving) return
        val key = state.value.sessionKey
        state.update { it.copy(loading = true, error = null) }
        sessionJob?.cancel()
        sessionJob =
            viewModelScope.launch {
                try {
                    if (!fresh) {
                        loadHistory(current, key)
                    } else {
                        refreshSessions(current)
                        state.update { it.copy(loading = false) }
                    }
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (
                    error: Exception,
                ) {
                    state.update { it.copy(loading = false, error = failureMessage(error)) }
                }
            }
    }

    private suspend fun event(frame: JsonObject) {
        if (!ownerIsCurrent()) return
        clawNotice(frame)?.let { notice ->
            if (notice.identity.startsWith("run:") &&
                pendingRuns[notice.session]?.id == frame.obj("payload")?.string("runId")
            ) {
                pendingRuns.remove(notice.session)
                savePendingRuns()
            }
            showNotice(notice)
        }
        if (frame.string("event") != "chat") return
        val payload = frame.obj("payload") ?: return
        if (payload.string("sessionKey") != state.value.sessionKey) return
        val run = payload.string("runId") ?: return
        if (activeRun != null && run != activeRun) return
        if (!streamOrder.accept(state.value.sessionKey, payload)) return
        when (payload.string("state")) {
            "delta" -> {
                activeRun = run
                val snapshot = messageText(payload.obj("message"))
                state.update {
                    it.copy(
                        busy = true,
                        streaming =
                            snapshot.ifBlank {
                                if (payload.string("replace") == "true") {
                                    payload.string("deltaText").orEmpty()
                                } else {
                                    it.streaming + payload.string("deltaText").orEmpty()
                                }
                            },
                    )
                }
            }

            "final", "aborted", "error" -> {
                state.update {
                    it.copy(
                        busy = false,
                        error =
                            if (payload.string("state") ==
                                "error"
                            ) {
                                "OpenClawの応答に失敗しました。履歴を確認してください。"
                            } else {
                                it.error
                            },
                    )
                }
                activeRun = null
                val current = client ?: return
                try {
                    loadHistory(current, state.value.sessionKey)
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (
                    _: Exception,
                ) {
                    state.update { it.copy(loading = false, error = "履歴を更新できませんでした。更新を押してください。") }
                }
            }
        }
    }

    fun patchSession(
        field: String,
        selection: String?,
    ) {
        val current = client ?: return
        if (!state.value.canPatch || state.value.sessionKey.isBlank() || state.value.loading || state.value.busy ||
            state.value.saving
        ) {
            return
        }
        if (field == "thinkingLevel" && selection != null && selection !in state.value.efforts) return
        if (field == "model" && selection != null && state.value.models.none { it.id == selection }) return
        val key = state.value.sessionKey
        state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                current.rpc("sessions.patch", obj("key" to value(key), field to value(selection)))
                if (client !== current || state.value.sessionKey != key) return@launch
                refreshSessions(current)
                state.update { it.copy(notice = "この会話の設定を保存しました") }
            } catch (
                cancelled: CancellationException,
            ) {
                throw cancelled
            } catch (
                error: Exception,
            ) {
                state.update { it.copy(error = failureMessage(error)) }
            } finally {
                state.update { it.copy(saving = false) }
            }
        }
    }

    fun loadDefaults() {
        val current = client ?: return
        viewModelScope.launch {
            try {
                val config = current.rpc("config.get").obj("config") ?: error("config")
                val agents = config.obj("agents") ?: obj()
                val entry =
                    agents.obj("entries")?.obj(agentId)
                        ?: agents.array("list").mapNotNull { it as? JsonObject }.find { it.string("id") == agentId }
                        ?: obj()
                val shared = agents.obj("defaults") ?: obj()
                val selectedModel = entry["model"] ?: shared["model"]
                state.update {
                    it.copy(
                        defaultLoaded = true,
                        defaultModel =
                            (selectedModel as? JsonObject)?.string("primary")
                                ?: (selectedModel as? JsonPrimitive)?.content,
                        defaultEffort = entry.string("thinkingDefault") ?: shared.string("thinkingDefault"),
                    )
                }
            } catch (
                cancelled: CancellationException,
            ) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(error = failureMessage(error)) }
            }
        }
    }

    fun saveDefaults(
        model: String? = null,
        effort: String? = null,
    ) {
        val current = client ?: return
        if (!state.value.canAdmin || state.value.saving || !state.value.defaultLoaded) return
        if (model != null && state.value.models.none { it.id == model }) return
        if (effort != null && effort !in state.value.efforts) return
        state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val snapshot = current.rpc("config.get")
                val hash = snapshot.string("hash") ?: error("config hash")
                val patch = agentDefaultsPatch(snapshot.obj("config") ?: error("config"), agentId, model, effort)
                current.rpc("config.patch", obj("baseHash" to value(hash), "raw" to value(patch.toString())))
                state.update { it.copy(notice = "OpenClawの既定値を保存しました。反映のため再接続してください。", defaultLoaded = false) }
            } catch (
                cancelled: CancellationException,
            ) {
                throw cancelled
            } catch (
                error: Exception,
            ) {
                state.update { it.copy(error = failureMessage(error)) }
            } finally {
                state.update { it.copy(saving = false) }
            }
        }
    }

    private fun disconnect() {
        connectJob?.cancel()
        sessionJob?.cancel()
        client?.close()
        client = null
        connectionEpoch++
        titleJobs.values.forEach { it.cancel() }
        titleJobs.clear()
        streamOrder = ClawStreamOrder()
    }

    override fun onCleared() {
        disconnect()
    }

    companion object {
        fun ownerScope(): String = "${AuthManager.baseUrlFlow.value}|${AuthManager.selectedProfileFlow.value}"

        private fun failureMessage(error: Exception): String =
            (error as? ClawFailure)?.userMessage
                ?: "接続を確認できませんでした。サーバーの起動状態・URL・端末承認を確認してください。"
    }
}
