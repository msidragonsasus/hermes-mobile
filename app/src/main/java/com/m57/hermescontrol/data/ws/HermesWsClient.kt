package com.m57.hermescontrol.data.ws

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.config.resolvedBaseUrl
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.DashboardSessionTokenRefresher
import com.m57.hermescontrol.data.remote.NetworkMonitor
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.KNOWN_APPROVAL_CHOICES
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.utf8Size
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Connection status for the WebSocket client.
 */
enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    NO_NETWORK,
    AUTH_EXPIRED,
}

/**
 * WebSocket client for the Hermes Dashboard JSON-RPC 2.0 interface.
 *
 * Connects to `ws://HOST:PORT/api/ws?token=TOKEN`, auto-reconnects with
 * exponential backoff, and emits parsed [WsEvent]s via [events] SharedFlow
 * as well as direct callbacks.
 */
object HermesWsClient {
    private const val TAG = "HermesWsClient"

    // ── Backoff settings ─────────────────────────────────────────────────

    private var initialBackoffMs = 1_000L
    private const val MAX_BACKOFF_MS = 30_000L
    private const val BACKOFF_MULTIPLIER = 2.0

    /**
     * Test hook: the initial reconnect backoff, overridable so reconnect
     * tests are deterministic instead of racing real time (CI runners
     * routinely exceed fixed latch windows). Production keeps 1s.
     */
    @VisibleForTesting
    internal fun setReconnectBackoffForTest(initialMillis: Long) {
        initialBackoffMs = initialMillis
        currentBackoff = initialMillis
    }

    private const val MAX_OUTBOUND_MESSAGE_BYTES = 16 * 1024 * 1024
    private const val OUTBOUND_DRAIN_TIMEOUT_MS = 60_000L

    /**
     * No inbound frame for this long means the link is dead even though the
     * TCP session may still look open (silent NAT/VPN drop). OkHttp pings
     * every 30s (OkHttpProvider.websocket) and any inbound frame refreshes
     * [lastPongTimestamp], so a healthy link never exceeds ~30s of silence.
     * 90s tolerates exactly one missed ping cycle before acting.
     */
    private const val STALE_THRESHOLD_MS = 90_000L

    // ── Internal state (all access through synchronized / atomic) ────────

    private val requestId = AtomicInteger(0)
    private val connectionGeneration = AtomicInteger(0)
    private val connected = AtomicBoolean(false)
    private val intentionalClose = AtomicBoolean(false)
    private val acceptQueuedMessages = AtomicBoolean(true)
    private val appInForeground = AtomicBoolean(true)
    private val externalActivityConnectionLease = AtomicBoolean(false)
    private val messageQueue = ConcurrentLinkedQueue<String>()
    private val queuedMessagesById = ConcurrentHashMap<String, String>()
    private val outboundLock = Any()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var closingSocket: WebSocket? = null

    @Volatile
    private var currentBackoff = initialBackoffMs

    private val wsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Started lazily on the first [connect] instead of in [init]: the
     * reconnect-on-network-change subscription only matters once a socket
     * has actually been opened. init-time subscription also leaks a live
     * real-IO collector into the unit-test JVM (class load), where
     * NetworkMonitor emissions crashed it after unmockkAll.
     */
    private val networkCollectorStarted = AtomicBoolean(false)

    @Volatile
    private var reconnectJob: Job? = null

    @Volatile
    private var outboundDrainJob: Job? = null

    // ── Sequence tracking & replay on reconnect (issue #1016) ─────────────
    private val lastSeenSeq = ConcurrentHashMap<String, Int>()
    private val sessionReplayEpochs = ConcurrentHashMap<String, String>()

    @Volatile
    private var replayEpoch: String? = null

    @Volatile
    private var replayInFlight: Boolean = false
    private val replayHold = ConcurrentHashMap<String, MutableList<Pair<Int?, WsEvent>>>()

    @Volatile
    private var replayJob: Job? = null

    // ── Health and Ping/Pong tracking ────────────────────────────────────

    @Volatile
    var lastPongTimestamp: Long = 0L
        private set

    val isHealthy: Boolean
        get() = isConnected && (System.currentTimeMillis() - lastPongTimestamp < 60_000L)

    // ── Observable latency tracking (issue #1017) ─────────────────────────
    private val _lastLatencyMs = MutableStateFlow<Long?>(null)

    /** Last measured round-trip latency in milliseconds via [ping], or null when disconnected / unmeasured. */
    val lastLatencyMs: StateFlow<Long?> = _lastLatencyMs.asStateFlow()

    private var healthJob: Job? = null

    private fun startHealthTracking() {
        healthJob?.cancel()
        lastPongTimestamp = System.currentTimeMillis()
        healthJob =
            wsScope.launch {
                while (connected.get()) {
                    delay(30_000L)
                    if (connected.get()) {
                        try {
                            ping(timeoutMs = 10_000L)
                        } catch (e: Exception) {
                            Log.w(TAG, "Health check ping failed: ${e.javaClass.simpleName}")
                        }
                    }
                    if (!runHealthCheckPass()) break
                }
            }
    }

    /**
     * Ultra-lightweight JSON-RPC liveness check and RTT measurement (issue #1017).
     * Bypasses agent queues on the backend; measures round-trip time in milliseconds.
     * Updates [lastLatencyMs] and [lastPongTimestamp].
     */
    suspend fun ping(timeoutMs: Long = 5_000L): Long {
        val start = System.currentTimeMillis()
        request(WsMethods.PING, emptyMap(), timeoutMs = timeoutMs).await()
        val latency = (System.currentTimeMillis() - start).coerceAtLeast(0L)
        lastPongTimestamp = System.currentTimeMillis()
        _lastLatencyMs.value = latency
        return latency
    }

    @VisibleForTesting
    internal fun setLastLatencyForTest(latency: Long?) {
        _lastLatencyMs.value = latency
    }

    /**
     * One liveness pass. Returns false when the tracking loop should stop:
     * either the connection is already gone or the watchdog fired a cancel.
     *
     * Cancelling (not close()) forces an immediate onFailure, which rides the
     * existing teardown path: generation bump, RECONNECTING, scheduleReconnect.
     */
    private fun runHealthCheckPass(): Boolean {
        if (!connected.get()) return false
        val staleMs = System.currentTimeMillis() - lastPongTimestamp
        if (staleMs <= STALE_THRESHOLD_MS) return true
        Log.w(TAG, "WebSocket stale (${staleMs / 1000}s without frames) — cancelling to trigger reconnect")
        synchronized(outboundLock) {
            if (!connected.get()) return false
            webSocket?.cancel()
        }
        return false
    }

    /**
     * Test hook: backdate liveness past the staleness threshold and force one
     * synchronous watchdog pass, so reconnect tests are deterministic instead
     * of racing the real 30s/90s cadence.
     */
    @VisibleForTesting
    internal fun forceHealthCheckForTest(staleMillis: Long) {
        lastPongTimestamp = System.currentTimeMillis() - staleMillis
        runHealthCheckPass()
    }

    private fun stopHealthTracking() {
        healthJob?.cancel()
        healthJob = null
        _lastLatencyMs.value = null
    }

    // ── Public observable stream ─────────────────────────────────────────

    private val parsedEvents =
        MutableSharedFlow<WsEvent>(
            extraBufferCapacity = 512,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Collect this from ViewModels to receive all parsed [WsEvent]s. */
    val events: SharedFlow<WsEvent> = parsedEvents.asSharedFlow()

    private val processIdentity = UUID.randomUUID().toString()
    private val observationOrdinal = AtomicLong()
    private val _connectionScope = MutableStateFlow<ConnectionScope?>(null)

    @Volatile private var connectionBaseUrl: String? = null
    val connectionScope: StateFlow<ConnectionScope?> = _connectionScope.asStateFlow()
    private val _scopedEvents =
        MutableSharedFlow<ScopedWsEvent>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val scopedEvents: SharedFlow<ScopedWsEvent> = _scopedEvents.asSharedFlow()
    private val pendingVaultPrompts = ConcurrentHashMap<String, ConnectionScope>()
    private val answeredVaultPrompts = ConcurrentHashMap<String, ConnectionScope>()

    private fun emitScoped(
        scope: ConnectionScope?,
        event: WsEvent? = null,
        gap: Boolean = false,
        gapSessionId: String? = null,
    ) {
        if (scope == null || !isCurrentGeneration(scope.generation)) return
        if ((
                event is WsEvent.ApprovalRequest || event is WsEvent.ClarifyRequest ||
                    event is WsEvent.ServerRequestCancel || event is WsEvent.OpenRequestsSnapshot
            ) &&
            !matchesCurrentScope(scope)
        ) {
            return
        }
        when (event) {
            is WsEvent.VaultRequest -> {
                if (answeredVaultPrompts[event.request.id] == scope) return
                pendingVaultPrompts[event.request.id] = scope
            }

            is WsEvent.ServerRequestCancel -> {
                event.requestId?.let { pendingVaultPrompts.remove(it, scope) }
            }

            else -> {}
        }
        val observation = ScopedWsEvent(scope, observationOrdinal.incrementAndGet(), event, gap, gapSessionId)
        // Publish the original first; the proof also retains these exact bytes/metadata until VM admission.
        if (_scopedEvents.tryEmit(observation)) resolveTerminalProof(observation)
        if (event is WsEvent.SessionInfo) {
            restoreOpenPrompts(scope, event.data)
        }
    }

    internal fun snapshotObservationOrdinal(): Long = observationOrdinal.get()

    /** Incoming name shares the delivered session fence and atomic snapshot admission. */
    internal fun restoreOpenPrompts(
        scope: ConnectionScope,
        payload: Map<*, *>?,
        expectedRuntimeId: String? = payload?.get("session_id") as? String,
        startedAt: Long = observationOrdinal.get(),
    ) = restoreVaultPrompts(scope, payload, expectedRuntimeId, startedAt)

    /** All authoritative carriers share session validation and one scoped admission. */
    internal fun restoreVaultPrompts(
        scope: ConnectionScope,
        payload: Map<*, *>?,
        expectedRuntimeId: String? = payload?.get("session_id") as? String,
        startedAt: Long = observationOrdinal.get(),
    ) {
        fun validId(value: String?): Boolean =
            !value.isNullOrBlank() && value.length <= 512 && value.none(Char::isISOControl)
        if (!matchesCurrentScope(scope) || expectedRuntimeId.isNullOrBlank() || !validId(expectedRuntimeId)) return
        if (payload?.containsKey("session_id") == true && payload["session_id"] != expectedRuntimeId) return
        val raw = payload?.get("open_requests") as? List<*> ?: return
        if (raw.size > 1000) return
        val ids = mutableSetOf<String>()
        val requests = mutableListOf<WsEvent>()
        for (entry in raw) {
            val frame = entry as? Map<*, *> ?: return
            val id = (frame["id"] as? String)?.takeIf(::validId) ?: return
            val method = (frame["method"] as? String)?.takeIf(String::isNotBlank) ?: return
            val params = frame["params"] as? Map<*, *> ?: return
            if (!validId(params["session_id"] as? String) || params["session_id"] != expectedRuntimeId ||
                !ids.add(id)
            ) {
                return
            }
            val parsed = EventParser.parseServerRequest(id, method, params)
            if (parsed is WsEvent.UnsupportedServerRequest &&
                (method in setOf("approval", "clarify") || VaultPromptKind.entries.any { it.method == method })
            ) {
                return
            }
            requests.add(parsed)
        }
        emitScoped(scope, WsEvent.OpenRequestsSnapshot(expectedRuntimeId, startedAt, requests, ids))
        // Existing Chat and notification consumers use the same scoped request events.
        requests.forEach { restored ->
            if (restored is WsEvent.VaultRequest || restored is WsEvent.ApprovalRequest ||
                restored is WsEvent.ClarifyRequest
            ) {
                emitScoped(scope, restored)
            }
        }
    }

    /** Answer the original server RPC once, on its original socket. Never queued or replayed. */
    fun respondToVaultPrompt(
        scope: ConnectionScope,
        requestId: String,
        value: String,
    ): Boolean =
        synchronized(outboundLock) {
            if (pendingVaultPrompts[requestId] != scope || !matchesCurrentScope(scope) || !connected.get()) {
                return@synchronized false
            }
            val response = JsonRpcResponse(id = requestId, result = mapOf("value" to value).toJsonElement())
            val json = OkHttpProvider.json.encodeToString(response)
            if (!withinOutboundLimit(json) || webSocket?.send(json) != true) return@synchronized false
            answeredVaultPrompts[requestId] = scope
            pendingVaultPrompts.remove(requestId, scope)
            true
        }

    // ── Connection status flow ──────────────────────────────────────────
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)

    /** Observable connection status */
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    // ── Reply-pending tracking ─────────────────────────────────────────
    // True while at least one agent turn is in flight. Drives the chat
    // notification foreground service: it only runs while a reply is
    // actually pending, instead of for the whole time the app is
    // backgrounded (issue #794).
    @Volatile
    var pendingReply: Boolean = false
        private set

    private val pendingPromptSubmits = ConcurrentHashMap.newKeySet<String>()

    // ── Credential warning (issue #534) ─────────────────────────────────
    // Backend surfaces `credential_warning` in `gateway.ready` / `session.info`
    // WS payloads (desktop `requestDesktopOnboarding`). Mobile has no equivalent
    // at the auth layer, so we extract it here once, globally, and let any
    // screen render a banner that deep-links to ProvidersScreen.
    private val _credentialWarning = MutableStateFlow<String?>(null)

    /** Non-null when the backend reports a credential warning to resolve. */
    val credentialWarning: StateFlow<String?> = _credentialWarning.asStateFlow()

    fun clearCredentialWarning() {
        _credentialWarning.value = null
    }

    init {
        // Extract credential_warning from gateway.ready / session.info payloads.
        wsScope.launch {
            events.collect { event ->
                val data: Map<String, Any?>? =
                    when (event) {
                        is WsEvent.GatewayReady -> event.data
                        is WsEvent.SessionInfo -> event.data
                        else -> null
                    }
                val warning = data?.get("credential_warning") as? String
                if (!warning.isNullOrBlank()) {
                    _credentialWarning.value = warning
                }
            }
        }
        // Track whether a reply is actually in flight so the chat notification
        // foreground service only runs while the user is waiting for one
        // (issue #794), instead of for the whole time the app is backgrounded.
        // Also covers turns started from other devices on the same session.
        wsScope.launch {
            events.collect { event ->
                when (event) {
                    is WsEvent.MessageStart,
                    is WsEvent.MessageToken,
                    is WsEvent.ThinkingDelta,
                    is WsEvent.ReasoningDelta,
                    is WsEvent.ToolStart,
                    -> {
                        pendingReply = true
                    }

                    is WsEvent.MessageComplete -> {
                        pendingReply = false
                        disconnectIfIdleInBackground()
                    }

                    else -> {}
                }
            }
        }
        // Forward parsed change events (issue #784) to the hub so screens can
        // refresh on change without touching this singleton — its real static
        // init must never run inside unit tests (see ChangeEventHub).
        wsScope.launch {
            events
                .filterIsInstance<WsEvent.ChangeEvent>()
                .collect { ChangeEventHub.emit(it) }
        }
        // Track gateway replay_epoch across gateway.ready events (issue #1016)
        wsScope.launch {
            events.collect { event ->
                if (event is WsEvent.GatewayReady) {
                    val epoch = event.data?.get("replay_epoch") as? String
                    if (!epoch.isNullOrEmpty()) {
                        if (replayEpoch != null && replayEpoch != epoch) {
                            lastSeenSeq.clear()
                            sessionReplayEpochs.clear()
                        }
                        replayEpoch = epoch
                    }
                }
            }
        }
    }

    // ── Connection helpers ────────────────────────────────────────────────

    @VisibleForTesting
    val isConnected: Boolean get() = connected.get()

    fun setAppForeground(foreground: Boolean) {
        appInForeground.set(foreground)
        if (!foreground) disconnectIfIdleInBackground()
    }

    fun acquireExternalActivityConnectionLease() {
        externalActivityConnectionLease.set(true)
    }

    fun releaseExternalActivityConnectionLease() {
        externalActivityConnectionLease.set(false)
        disconnectIfIdleInBackground()
    }

    private fun disconnectIfIdleInBackground() {
        synchronized(outboundLock) {
            if (!appInForeground.get() &&
                !externalActivityConnectionLease.get() &&
                !pendingReply &&
                pendingCalls.isEmpty() &&
                messageQueue.isEmpty()
            ) {
                disconnect()
            }
        }
    }

    /**
     * [connect] for main-thread callers: the handshake first mints a ticket or
     * refreshes the dashboard token with blocking network I/O.
     */
    fun connectInBackground() {
        wsScope.launch { connect() }
    }

    /** Open a WebSocket connection using settings from [AuthManager]. */
    fun connect() {
        if (networkCollectorStarted.compareAndSet(false, true)) {
            wsScope.launch {
                NetworkMonitor.networkChanges.collect { networkAvailable ->
                    reconnectForNetworkChange(networkAvailable)
                }
            }
        }
        var socketToCancel: WebSocket? = null
        val shouldOpen =
            synchronized(outboundLock) {
                acceptQueuedMessages.set(true)
                if (_connectionStatus.value == ConnectionStatus.AUTH_EXPIRED) {
                    connectionGeneration.incrementAndGet()
                    reconnectJob?.cancel()
                    reconnectJob = null
                    outboundDrainJob?.cancel()
                    outboundDrainJob = null
                    socketToCancel = webSocket
                    webSocket = null
                    closingSocket = null
                    connected.set(false)
                    stopHealthTracking()
                }
                if (connected.get()) {
                    Log.d(TAG, "Already connected — skipping")
                    return@synchronized false
                }
                if (_connectionStatus.value == ConnectionStatus.CONNECTING ||
                    _connectionStatus.value == ConnectionStatus.RECONNECTING
                ) {
                    Log.d(TAG, "Connection already in flight (${_connectionStatus.value}) — skipping")
                    return@synchronized false
                }
                // An explicit connect follows successful authentication and is
                // therefore allowed to leave the terminal AUTH_EXPIRED state.
                intentionalClose.set(false)
                _connectionStatus.value = ConnectionStatus.CONNECTING
                currentBackoff = initialBackoffMs
                true
            }
        if (socketToCancel != null) {
            rejectAllPending()
            socketToCancel.cancel()
        }
        if (shouldOpen) openSocket()
    }

    @VisibleForTesting
    internal fun reconnectForNetworkChange(
        networkAvailable: Boolean = NetworkMonitor.isConnected.value,
        openReplacement: () -> Unit = ::openSocket,
    ) {
        val socketToCancel: WebSocket?
        val shouldOpen: Boolean
        synchronized(outboundLock) {
            if (intentionalClose.get() || _connectionStatus.value == ConnectionStatus.AUTH_EXPIRED) {
                return
            }
            Log.d(TAG, "Default network changed — reconnecting WebSocket")
            currentBackoff = initialBackoffMs
            reconnectJob?.cancel()
            reconnectJob = null
            outboundDrainJob?.cancel()
            outboundDrainJob = null
            connectionGeneration.incrementAndGet()
            socketToCancel = webSocket
            webSocket = null
            closingSocket = null
            connected.set(false)
            stopHealthTracking()
            shouldOpen = networkAvailable && AuthManager.isAutoReconnect()
            _connectionStatus.value =
                when {
                    !networkAvailable -> ConnectionStatus.NO_NETWORK
                    shouldOpen -> ConnectionStatus.RECONNECTING
                    else -> ConnectionStatus.DISCONNECTED
                }
            rejectAllPending()
        }
        socketToCancel?.cancel()
        if (shouldOpen) openReplacement()
    }

    /**
     * If a session cookie is present (gated mode), mint a fresh WS ticket
     * from the dashboard. The ticket is single-use and has a 30-second TTL,
     * so we must mint a new one on every connect (first launch and reconnect).
     *
     * The session cookie is attached automatically by the shared CookieJar on
     * OkHttpProvider.probe (issue #470), so we no longer inject it manually.
     *
     * Returns true if the token is ready (either because we are not in gated mode,
     * or because ticket refresh succeeded). Returns false if we are in gated mode
     * and ticket refresh failed or cannot be performed.
     */
    internal fun refreshWsTicketIfNeeded(generation: Int): Boolean {
        val isGated =
            try {
                AuthManager.serverStore.getLatestState().wsAuthParam == "ticket"
            } catch (_: IllegalStateException) {
                // serverStore not initialized yet (transient early call); treat
                // as loopback so a stale-token refresh still runs. Log so a real
                // misconfiguration isn't silently swallowed.
                Log.w(TAG, "serverStore uninitialized during WS handshake; assuming non-gated")
                false
            }
        if (!isGated) {
            // The loopback dashboard token is regenerated on every server
            // restart. Refresh it before each WebSocket handshake so automatic
            // reconnect does not get stuck in AUTH_EXPIRED with a stale token.
            val token =
                synchronized(DashboardSessionTokenRefresher) {
                    runCatching {
                        DashboardSessionTokenRefresher.fetch(AuthManager.baseUrl(), OkHttpProvider.probe)
                    }.getOrNull()
                }
            synchronized(outboundLock) {
                if (isCurrentGeneration(generation) && token != null) {
                    runCatching { AuthManager.setToken(token) }
                }
            }
            return true
        }

        val ticketResult = requestWsTicket()
        if (!ticketResult.ticket.isNullOrBlank()) {
            synchronized(outboundLock) {
                if (!isCurrentGeneration(generation)) return false
                AuthManager.setToken(ticketResult.ticket)
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "WS ticket refreshed")
            return true
        }
        val status =
            when {
                ticketResult.exception -> ConnectionStatus.RECONNECTING
                ticketResult.httpCode == 401 || ticketResult.httpCode == 403 -> ConnectionStatus.AUTH_EXPIRED
                ticketResult.httpCode == 408 || ticketResult.httpCode == 429 -> ConnectionStatus.RECONNECTING
                ticketResult.httpCode != null && ticketResult.httpCode >= 500 -> ConnectionStatus.RECONNECTING
                else -> ConnectionStatus.DISCONNECTED
            }
        return handleWsTicketRefreshFailure(status, generation)
    }

    /**
     * Mint a fresh WS ticket for a *secondary* consumer (e.g. the kanban
     * events stream) WITHOUT touching the shared token slot.
     *
     * The shared slot is a single-use ticket that the chat WebSocket consumes
     * at handshake. If two clients mint and stash into the same slot on their
     * own schedules, each can grab the other's just-consumed ticket and the
     * pair starves each other into a permanent reconnect loop. Secondary
     * consumers must use their own ticket per connection.
     *
     * Gated mode: cookie-auth'd POST to /api/auth/ws-ticket, returns the
     * ticket. Loopback mode: refresh the dashboard token and return it.
     * Returns null when a ticket could not be obtained.
     */
    internal fun mintWsTicket(): String? {
        val isGated =
            try {
                AuthManager.serverStore.getLatestState().wsAuthParam == "ticket"
            } catch (_: IllegalStateException) {
                false
            }
        if (!isGated) {
            DashboardSessionTokenRefresher.refresh()
            return AuthManager.getToken()
        }
        return requestWsTicket().ticket
    }

    private data class TicketRequestResult(
        val ticket: String?,
        val httpCode: Int?,
        val exception: Boolean = false,
    )

    /** POST /api/auth/ws-ticket (cookie-auth'd via the shared CookieJar) and parse the ticket. */
    private fun requestWsTicket(): TicketRequestResult {
        try {
            val client = OkHttpProvider.probe
            val request =
                Request
                    .Builder()
                    .url(AuthManager.endpointForBuild().resolve("api/auth/ws-ticket").toString())
                    .post("{}".toRequestBody())
                    .build()

            // Run the ENTIRE call on Dispatchers.IO — execute() already hops,
            // but ResponseBody.string() reads the socket on the CALLING
            // thread. When the caller is main (kanban events connect), that
            // read throws NetworkOnMainThreadException and the mint fails.
            val (code, body) =
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    if (CookieManager.isInitialized()) {
                        CookieManager.useStore(CookieManager.cookieJar.currentServer())
                    }
                    client.newCall(request).execute().use { resp ->
                        resp.code to resp.body.string()
                    }
                }

            if (code in 200..299) {
                // Real JSON decode — the ticket is base64url and may contain
                // characters a naive [^"]+ regex cannot extract (see
                // testGatedMode_parsesEscapedTicketFromRealJsonShape).
                val parsed =
                    runCatching {
                        OkHttpProvider.json.decodeFromString<WsTicketResponse>(body)
                    }.getOrNull()
                val ticket = parsed?.ticket
                if (!ticket.isNullOrBlank()) {
                    return TicketRequestResult(ticket, null)
                }
                Log.w(TAG, "WS ticket mint failed: unparseable ticket response")
            } else {
                Log.w(TAG, "WS ticket mint failed: HTTP $code")
                return TicketRequestResult(null, code)
            }
        } catch (e: Exception) {
            // PR02 / R03: Throwable messages, causes and stacks can contain credentials.
            Log.w(TAG, "WS ticket mint failed: ${e.javaClass.simpleName}")
            return TicketRequestResult(null, null, exception = true)
        }
        return TicketRequestResult(null, null)
    }

    private fun handleWsTicketRefreshFailure(
        status: ConnectionStatus,
        generation: Int,
    ): Boolean {
        synchronized(outboundLock) {
            if (!isCurrentGeneration(generation)) return false
            _connectionStatus.value = status
            if (status == ConnectionStatus.RECONNECTING) scheduleReconnect()
        }
        return false
    }

    /** Cleanly close the WebSocket and stop auto-reconnect. */
    fun disconnect(clearPendingMessages: Boolean = false) {
        ActiveSessionHolder.clear()
        synchronized(outboundLock) {
            intentionalClose.set(true)
            pendingVaultPrompts.clear()
            answeredVaultPrompts.clear()
            acceptQueuedMessages.set(!clearPendingMessages)
            connectionGeneration.incrementAndGet()
            reconnectJob?.cancel()
            reconnectJob = null
            outboundDrainJob?.cancel()
            outboundDrainJob = null
            stopHealthTracking()
            val socket = webSocket
            if (connected.get()) {
                socket?.close(1000, "Client closed")
            } else {
                // An opening socket cannot send a close frame; cancel its pending upgrade.
                socket?.cancel()
            }
            webSocket = null
            closingSocket = null
            if (clearPendingMessages) {
                messageQueue.clear()
                queuedMessagesById.clear()
                pendingPromptSubmits.clear()
                pendingReply = false
                lastSeenSeq.clear()
                sessionReplayEpochs.clear()
                replayHold.clear()
                replayEpoch = null
                replayJob?.cancel()
                replayJob = null
                replayInFlight = false
            }
            connected.set(false)
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    // ── Awaited RPC request layer (issue #526) ─────────────────────────
    // Mirrors desktop apps/shared JsonRpcGatewayClient.request(): an in-flight
    // map with a per-call timeout and rejectAllPending on socket close, so a
    // dropped RPC response can't leave a caller awaiting forever.

    /** Thrown when an awaited [request] is neither answered nor rejected within [REQUEST_TIMEOUT_MS], or is rejected by a disconnect. */
    class HermesRpcException(
        message: String,
        val code: Int? = null,
    ) : Exception(message)

    /**
     * Default per-request timeout. Matches the desktop
     * `apps/shared` `JsonRpcGatewayClient.DEFAULT_REQUEST_TIMEOUT_MS` (120s),
     * so legitimately long agent turns are not pruned early.
     */
    const val REQUEST_TIMEOUT_MS: Long = 120_000L

    /** A single in-flight [request] awaiting its RPC result/error. */
    private data class PendingCall(
        val method: String,
        val deferred: CompletableDeferred<Any?>,
        var timeoutJob: Job? = null,
        val writeScope: ConnectionScope? = null,
        val groupCreate: Pair<GroupSessionAccess, String>? = null,
        val snapshotScope: ConnectionScope? = null,
        val expectedRuntimeId: String? = null,
        val snapshotStartedAt: Long = observationOrdinal.get(),
        val authenticationEpoch: Long = AuthManager.connectionEpoch.value,
        val socket: WebSocket? = webSocket,
        val terminalExpectation: TerminalExpectation? = null,
        val terminalProof: CompletableDeferred<TerminalAcceptanceProof> = CompletableDeferred(),
    )

    private data class TerminalCapability(
        val scope: ConnectionScope,
        val epoch: Long,
        val socket: WebSocket,
        val peer: String,
        val clientSurfaces: Set<String> = emptySet(),
    )

    private data class TerminalExpectation(
        val capability: TerminalCapability,
        val runtime: String,
        val target: String,
        val nonce: String,
        val deadlineNanos: Long,
    )

    @Volatile private var terminalCapability: TerminalCapability? = null

    private fun currentCapability(capability: TerminalCapability): Boolean =
        capability.epoch % 2L == 0L && AuthManager.connectionEpoch.value == capability.epoch &&
            connected.get() && webSocket === capability.socket && matchesCurrentScope(capability.scope) &&
            (capability.peer.isEmpty() || terminalCapability == capability)

    private fun cacheTerminalCapability(
        call: PendingCall,
        result: Any?,
        error: JsonRpcError?,
    ) {
        if (call.method != "gateway.capabilities") return
        val scope = call.snapshotScope ?: return
        val socket = call.socket ?: return
        val candidate = TerminalCapability(scope, call.authenticationEpoch, socket, "")
        if (!currentCapability(candidate)) return
        terminalCapability = null
        if (error != null) return
        val raw = (if (result is JsonElement) result.toAny() else result) as? Map<*, *> ?: return
        val surfaces = (raw["client_surfaces"] as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        val feature = raw["terminal_acceptance"] as? Map<*, *>
        val peer =
            if (feature?.get("version") == 1 && feature["mode"] == "local-ordinary-text-v1") {
                (feature["peer_id"] as? String)?.takeIf(::isTerminalUuid).orEmpty()
            } else {
                ""
            }
        terminalCapability = candidate.copy(peer = peer, clientSurfaces = surfaces)
    }

    fun supportsClientSurface(
        scope: ConnectionScope,
        surface: String,
    ): Boolean =
        synchronized(outboundLock) {
            terminalCapability?.let {
                it.scope == scope && currentCapability(it) && surface in it.clientSurfaces
            } == true
        }

    private fun resolveTerminalProof(observation: ScopedWsEvent) {
        val event = observation.event as? WsEvent.MessageComplete ?: return
        val receipt = event.terminalAcceptance ?: return
        val id = receipt.originalRequestId
        val call = pendingCalls[id] ?: return
        val expected = call.terminalExpectation ?: return
        synchronized(outboundLock) {
            if (call.method != WsMethods.PROMPT_SUBMIT || call.writeScope != observation.scope ||
                !currentCapability(expected.capability) || System.nanoTime() >= expected.deadlineNanos ||
                receipt.peerId != expected.capability.peer || receipt.clientAttemptNonce != expected.nonce ||
                receipt.runtimeSessionId != expected.runtime || receipt.targetSessionId != expected.target ||
                receipt.transcriptSessionId != expected.target ||
                (event.storedSessionId != null && event.storedSessionId != expected.target) ||
                !event.hasSafeTerminalAcceptance(receipt)
            ) {
                return
            }
            // Exact instance compare-remove is the single winner against ACK, deadline and cleanup.
            if (!pendingCalls.remove(id, call)) return
            call.timeoutJob?.cancel()
            pendingPromptSubmits.remove(id)
            call.terminalProof.complete(
                TerminalAcceptanceProof(observation, receipt, expected.capability.epoch) {
                    currentCapability(expected.capability)
                },
            )
            // call.deferred is RPC-only: no forged JSON-RPC response and no remote cancellation.
        }
    }

    /** Tracks in-flight [request] calls by their JSON-RPC id. */
    private val pendingCalls = ConcurrentHashMap<String, PendingCall>()

    /**
     * Send a JSON-RPC request that expects a result and returns a
     * [CompletableDeferred] for it — mirroring the desktop
     * `JsonRpcGatewayClient.request()`. Fire-and-forget notifications should
     * keep using [send].
     *
     * The deferred is completed on the matching [WsEvent.RpcResult] /
     * [WsEvent.RpcError], rejected after [timeoutMs] (no response), or
     * rejected by [rejectAllPending] when the socket closes.
     */
    fun request(
        method: String,
        params: Map<String, Any> = emptyMap(),
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): CompletableDeferred<Any?> {
        val deferred = CompletableDeferred<Any?>()
        val id =
            send(method, params) { reqId ->
                pendingCalls[reqId] =
                    PendingCall(
                        method,
                        deferred,
                        snapshotScope = _connectionScope.value,
                        expectedRuntimeId = params["session_id"] as? String,
                    )
            }
        // Arm the per-request timeout (fires if the server never answers).
        pendingCalls[id]?.timeoutJob =
            wsScope.launch {
                delay(timeoutMs)
                resolvePending(id, null, JsonRpcError(-1, "Request timed out: $method"))
            }
        return deferred
    }

    /** A fixed-scope, read-only request. No reconnect, queue, or protocol fallback. */
    fun readSessions(
        scope: ConnectionScope,
        timeoutMs: Long = 5_000L,
    ): CompletableDeferred<Any?> = requestFixedScope(scope, WsMethods.SESSION_LIST, emptyMap(), timeoutMs)

    /** Read-only live turn roster (`session.active_list`) on the fixed scope; never resumes or queues. */
    fun readActiveSessions(
        scope: ConnectionScope,
        timeoutMs: Long = 5_000L,
    ): CompletableDeferred<Any?> = requestFixedScope(scope, WsMethods.SESSION_ACTIVE_LIST, emptyMap(), timeoutMs)

    /** Read the live child roster only on the socket/profile that owns the session. */
    fun readSubagents(
        scope: ConnectionScope,
        sessionId: String,
    ): CompletableDeferred<Any?> =
        requestFixedScope(scope, WsMethods.SUBAGENT_LIST, mapOf("session_id" to sessionId), 5_000L)

    /** Read the session's own process roster only on the socket/profile that owns the session. */
    fun readProcesses(
        scope: ConnectionScope,
        sessionId: String,
    ): CompletableDeferred<Any?> =
        requestFixedScope(scope, WsMethods.PROCESS_LIST, mapOf("session_id" to sessionId), 5_000L)

    /** Vault requests never enter the offline queue, transcript, or reconnect replay. */
    internal fun requestVault(
        scope: ConnectionScope,
        method: String,
        params: Map<String, Any> = emptyMap(),
    ): CompletableDeferred<Any?> {
        if (method !in setOf("vault.list", "vault.add", "vault.remove") ||
            (params.containsKey("profile") && params["profile"] != scope.serverProfileId)
        ) {
            return CompletableDeferred<Any?>().apply {
                completeExceptionally(HermesRpcException("Request not sent", OfflineSendPolicy.NOT_SENT))
            }
        }
        return requestFixedScope(scope, method, params, 15_000L)
    }

    internal suspend fun resumeAfterReplayGap(
        scope: ConnectionScope,
        savedSessionId: String,
    ): Any? =
        requestFixedScope(
            scope,
            WsMethods.SESSION_RESUME,
            mapOf("session_id" to savedSessionId, "omit_messages" to true),
            REQUEST_TIMEOUT_MS,
        ).await()

    private fun requestFixedScope(
        scope: ConnectionScope,
        method: String,
        params: Map<String, Any>,
        timeoutMs: Long,
    ): CompletableDeferred<Any?> =
        synchronized(outboundLock) {
            val result = CompletableDeferred<Any?>()
            if (!matchesCurrentScope(scope) || !connected.get()) {
                result.completeExceptionally(HermesRpcException("Connection scope unavailable"))
                return@synchronized result
            }
            val id = requestId.incrementAndGet().toString()
            val fixedParams =
                params.toMutableMap().apply {
                    remove("profile")
                    scope.serverProfileId?.let { put("profile", it) }
                }
            val request =
                JsonRpcRequest(
                    id = id,
                    method = method,
                    params = fixedParams.mapValues { it.value.toJsonElement() },
                )
            val json = OkHttpProvider.json.encodeToString(request)
            if (!withinOutboundLimit(json)) {
                result.completeExceptionally(HermesRpcException("Request not sent", OfflineSendPolicy.NOT_SENT))
                return@synchronized result
            }
            pendingCalls[id] =
                PendingCall(
                    method,
                    result,
                    snapshotScope = scope,
                    expectedRuntimeId = params["session_id"] as? String,
                    writeScope =
                        if (method == WsMethods.SESSION_LIST ||
                            method == WsMethods.SESSION_ACTIVE_LIST
                        ) {
                            null
                        } else {
                            scope
                        },
                )
            if (webSocket?.send(json) != true) {
                resolvePending(id, null, JsonRpcError(-1, "Read unavailable"))
            } else {
                pendingCalls[id]?.timeoutJob =
                    wsScope.launch {
                        delay(timeoutMs)
                        resolvePending(id, null, JsonRpcError(-1, "Read timed out"))
                    }
            }
            result
        }

    fun matchesCurrentScope(scope: ConnectionScope): Boolean =
        _connectionScope.value == scope && isCurrentGeneration(scope.generation) &&
            AuthManager.getSelectedProfileId() == scope.connectionProfileId &&
            AuthManager.activeProfileId.value == scope.serverProfileId && AuthManager.getBaseUrl() == connectionBaseUrl

    /** A configured destination for offline drafts; reading it never opens a connection. */
    fun configuredScope(): ConnectionScope? {
        val state = AuthManager.serverStore.getLatestState()
        val identity = serverIdentity(state.resolvedBaseUrl) ?: return null
        return ConnectionScope(
            identity,
            state.selectedProfileId?.takeIf(String::isNotBlank),
            AuthManager.activeProfileId.value,
            processIdentity,
            connectionGeneration.get(),
        )
    }

    private fun serverIdentity(baseUrl: String): String? {
        val endpoint =
            baseUrl
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.username("")
                ?.password("")
                ?.query(null)
                ?.fragment(null)
                ?.build()
                ?.toString() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it)
        }
    }

    /** Fixed destination and no queue. Shares the normal RPC deadline, including slow cold starts. */
    internal fun writeScoped(
        scope: ConnectionScope,
        method: String,
        params: Map<String, Any?>,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): ScopedRpcSubmission = writeScopedInternal(scope, method, params, timeoutMs, null)

    /** Only a known saved ordinary text target may opt into the positively negotiated feature. */
    internal fun writePromptScoped(
        scope: ConnectionScope,
        session: com.m57.hermescontrol.domain.hermes.SessionRef,
        params: Map<String, Any?>,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): ScopedRpcSubmission = writeScopedInternal(scope, WsMethods.PROMPT_SUBMIT, params, timeoutMs, session.savedId)

    private fun writeScopedInternal(
        scope: ConnectionScope,
        method: String,
        params: Map<String, Any?>,
        timeoutMs: Long,
        savedTarget: String?,
    ): ScopedRpcSubmission =
        synchronized(outboundLock) {
            val response = CompletableDeferred<Any?>()
            val allowed =
                method in
                    setOf(
                        WsMethods.PROMPT_SUBMIT,
                        WsMethods.SESSION_REDIRECT,
                        WsMethods.IMAGE_ATTACH_BYTES,
                        WsMethods.FILE_ATTACH,
                        WsMethods.SESSION_RESUME,
                        WsMethods.COMMAND_DISPATCH,
                        WsMethods.SLASH_EXEC,
                        WsMethods.PROMPT_BTW,
                        WsMethods.APPROVAL_RESPOND,
                        WsMethods.APPROVAL_RECEIVED,
                        WsMethods.APPROVAL_PENDING,
                        WsMethods.CLARIFY_RESPOND,
                        WsMethods.CLARIFY_LOCK,
                        WsMethods.REQUEST_ANSWER,
                    )
            val sessionId = params["session_id"] as? String
            val exactResponse =
                when (method) {
                    WsMethods.APPROVAL_RESPOND -> {
                        params["choice"] in KNOWN_APPROVAL_CHOICES && params["all"] == false &&
                            !(params["request_id"] as? String).isNullOrBlank()
                    }

                    WsMethods.CLARIFY_LOCK -> {
                        !(params["request_id"] as? String).isNullOrBlank() &&
                            !(params["question_id"] as? String).isNullOrBlank() && params.containsKey("answer") &&
                            (params["answer"] == null || params["answer"] is String)
                    }

                    WsMethods.APPROVAL_RECEIVED, WsMethods.CLARIFY_RESPOND -> {
                        !(params["request_id"] as? String).isNullOrBlank()
                    }

                    WsMethods.REQUEST_ANSWER -> {
                        !(params["id"] as? String).isNullOrBlank() && params["result"] is Map<*, *>
                    }

                    else -> {
                        true
                    }
                }
            val ws = webSocket
            if (!allowed || !exactResponse || sessionId.isNullOrBlank() || !matchesCurrentScope(scope) ||
                !connected.get() ||
                ws == null || (params.containsKey("profile") && params["profile"] != scope.serverProfileId)
            ) {
                response.completeExceptionally(HermesRpcException("Request not sent", OfflineSendPolicy.NOT_SENT))
                return@synchronized ScopedRpcSubmission(null, false, response)
            }
            val capability = terminalCapability?.takeIf { it.scope == scope && currentCapability(it) }
            val surface = params["surface"]
            if (surface != null && (surface !is String || surface !in capability?.clientSurfaces.orEmpty())) {
                response.completeExceptionally(HermesRpcException("Request not sent", OfflineSendPolicy.NOT_SENT))
                return@synchronized ScopedRpcSubmission(null, false, response)
            }
            val text = params["text"] as? String
            val expectation =
                if (method == WsMethods.PROMPT_SUBMIT && !savedTarget.isNullOrBlank() &&
                    capability != null && capability.peer.isNotEmpty() &&
                    params["queued"] != true && !text.isNullOrBlank() && !text.trimStart().startsWith("/") &&
                    !text.contains("@image:") && !text.contains("@file:") && !text.contains("MEDIA:")
                ) {
                    TerminalExpectation(
                        capability,
                        sessionId,
                        savedTarget,
                        UUID.randomUUID().toString(),
                        System.nanoTime() + timeoutMs * 1_000_000L,
                    )
                } else {
                    null
                }
            val fixedParams =
                params.toMutableMap().apply {
                    // Never pass an unnegotiated or caller-selected tag through this boundary.
                    remove("terminal_acceptance")
                    expectation?.let {
                        put(
                            "terminal_acceptance",
                            mapOf(
                                "version" to 1,
                                "client_attempt_nonce" to it.nonce,
                            ),
                        )
                    }
                    remove("profile")
                    // These contracts reject unknown keys; session_id above only scopes the write.
                    if (method == WsMethods.CLARIFY_LOCK || method == WsMethods.REQUEST_ANSWER) remove("session_id")
                    scope.serverProfileId?.let { put("profile", it) }
                }
            val id = requestId.incrementAndGet().toString()
            val request =
                JsonRpcRequest(id = id, method = method, params = fixedParams.mapValues { it.value.toJsonElement() })
            val json = OkHttpProvider.json.encodeToString(request)
            if (!withinOutboundLimit(json)) {
                response.completeExceptionally(HermesRpcException("Request not sent", OfflineSendPolicy.NOT_SENT))
                return@synchronized ScopedRpcSubmission(id, false, response)
            }
            val replyPendingBeforeSend = pendingReply
            val call =
                PendingCall(
                    method,
                    response,
                    writeScope = scope,
                    snapshotScope = scope,
                    expectedRuntimeId = sessionId,
                    terminalExpectation = expectation,
                )
            pendingCalls[id] = call // Including terminal expectation: MUST precede ws.send.
            if (method == WsMethods.PROMPT_SUBMIT) {
                pendingPromptSubmits.add(id)
                pendingReply = true
            }
            val accepted = ws.send(json)
            if (!accepted) {
                rejectNotSent(id, replyPendingBeforeSend)
            } else {
                if (pendingCalls[id] === call) {
                    call.timeoutJob =
                        wsScope.launch {
                            delay(timeoutMs)
                            resolvePending(id, null, JsonRpcError(-1, "Write acknowledgement unavailable"), call)
                        }
                }
            }
            ScopedRpcSubmission(id, accepted, response, call.terminalProof.takeIf { expectation != null }) {
                synchronized(outboundLock) {
                    if (pendingCalls.remove(id, call)) {
                        call.timeoutJob?.cancel()
                        pendingPromptSubmits.remove(id)
                    }
                }
            }
        }

    private val groupAccesses = mutableSetOf<GroupSessionAccess>()

    internal fun openGroupAccess(
        room: String,
        members: Set<String>,
        metadataProfile: String,
    ): GroupSessionAccess? =
        synchronized(outboundLock) {
            val scope = _connectionScope.value ?: return@synchronized null
            if (room.isBlank() || members.isEmpty() || members.any(String::isBlank) || metadataProfile.isBlank() ||
                !matchesCurrentScope(scope) || !connected.get() || webSocket == null
            ) {
                return@synchronized null
            }
            GroupSessionAccess(scope, room, members.toSet(), metadataProfile).also { groupAccesses.add(it) }
        }

    internal fun isCurrentGroupAccess(access: GroupSessionAccess): Boolean =
        synchronized(outboundLock) {
            access in groupAccesses && matchesCurrentScope(access.scope) && connected.get() && webSocket != null
        }

    internal fun closeGroupAccess(access: GroupSessionAccess) =
        synchronized(outboundLock) {
            groupAccesses.remove(access)
            access.sessions.clear()
            access.acknowledgedCreates.clear()
        }

    internal fun createGroupMember(
        access: GroupSessionAccess,
        member: String,
    ): ScopedRpcSubmission =
        writeGroupSession(
            access,
            member,
            null,
            WsMethods.SESSION_CREATE,
            mapOf("title" to "Group: ${access.room}", "source" to "desktop", "hidden" to true),
        )

    internal fun bindGroupMember(
        access: GroupSessionAccess,
        member: String,
        runtimeId: String?,
        storedId: String?,
    ): GroupMemberSession =
        synchronized(outboundLock) {
            if (!isCurrentGroupAccess(access) || member !in access.members || runtimeId.isNullOrBlank() ||
                access.acknowledgedCreates[member] != (runtimeId to storedId) ||
                access.sessions.values.any {
                    it.member != member &&
                        listOfNotNull(it.runtimeSessionId, it.storedSessionId).any { id ->
                            id == runtimeId ||
                                id == storedId
                        }
                }
            ) {
                throw HermesRpcException("Group session unavailable", OfflineSendPolicy.NOT_SENT)
            }
            GroupMemberSession(member, runtimeId, storedId?.takeIf(String::isNotBlank), access).also {
                access.sessions[member] = it
            }
        }

    internal fun submitGroupMember(
        access: GroupSessionAccess,
        session: GroupMemberSession,
        text: String,
    ): ScopedRpcSubmission =
        writeGroupSession(
            access,
            session.member,
            session,
            WsMethods.PROMPT_SUBMIT,
            mapOf("session_id" to session.runtimeSessionId, "text" to text),
        )

    internal fun interruptGroupMember(
        access: GroupSessionAccess,
        session: GroupMemberSession,
    ): ScopedRpcSubmission =
        writeGroupSession(
            access,
            session.member,
            session,
            WsMethods.SESSION_INTERRUPT,
            mapOf("session_id" to session.runtimeSessionId),
        )

    internal fun configureGroupSnapshot(
        access: GroupSessionAccess,
        snapshot: Map<String, Any?>,
    ): ScopedRpcSubmission =
        writeGroupSession(
            access,
            null,
            null,
            WsMethods.PROFILES_CONFIGURE,
            mapOf("name" to access.metadataProfile, "ui_meta" to mapOf("hermes-bots-groups" to snapshot)),
        )

    /** Narrow group operations only: no arbitrary RPC, caller profile or session-ID override. */
    private fun writeGroupSession(
        access: GroupSessionAccess,
        member: String?,
        session: GroupMemberSession?,
        method: String,
        params: Map<String, Any>,
    ): ScopedRpcSubmission =
        synchronized(outboundLock) {
            val response = CompletableDeferred<Any?>()
            val routeAllowed =
                when (method) {
                    WsMethods.PROFILES_CONFIGURE -> {
                        member == null && session == null
                    }

                    WsMethods.SESSION_CREATE -> {
                        member in access.members && session == null
                    }

                    WsMethods.PROMPT_SUBMIT, WsMethods.SESSION_INTERRUPT -> {
                        member in access.members && session != null && session.access === access &&
                            access.sessions[member] === session
                    }

                    else -> {
                        false
                    }
                }
            val ws = webSocket
            if (!routeAllowed || !isCurrentGroupAccess(access) || ws == null) {
                response.completeExceptionally(HermesRpcException("Request not sent", OfflineSendPolicy.NOT_SENT))
                return@synchronized ScopedRpcSubmission(null, false, response)
            }
            val fixedParams =
                params.toMutableMap().apply {
                    put("profile", member ?: access.metadataProfile)
                }
            val id = requestId.incrementAndGet().toString()
            val request =
                JsonRpcRequest(id = id, method = method, params = fixedParams.mapValues { it.value.toJsonElement() })
            val encoded = OkHttpProvider.json.encodeToString(request)
            if (!withinOutboundLimit(encoded)) {
                response.completeExceptionally(HermesRpcException("Request not sent", OfflineSendPolicy.NOT_SENT))
                return@synchronized ScopedRpcSubmission(id, false, response)
            }
            val replyPendingBeforeSend = pendingReply
            pendingCalls[id] =
                PendingCall(
                    method,
                    response,
                    writeScope = access.scope,
                    groupCreate = if (method == WsMethods.SESSION_CREATE && member != null) access to member else null,
                )
            if (method == WsMethods.PROMPT_SUBMIT) {
                pendingPromptSubmits.add(id)
                pendingReply = true
            }
            val accepted = ws.send(encoded)
            if (!accepted) {
                rejectNotSent(id, replyPendingBeforeSend)
            } else {
                pendingCalls[id]?.timeoutJob =
                    wsScope.launch {
                        delay(REQUEST_TIMEOUT_MS)
                        resolvePending(id, null, JsonRpcError(-1, "Group acknowledgement unavailable"))
                    }
            }
            ScopedRpcSubmission(id, accepted, response)
        }

    /** Complete (or fail) a single pending call and cancel its timer. */
    private fun resolvePending(
        id: String,
        result: Any?,
        error: JsonRpcError?,
        expectedCall: PendingCall? = null,
    ): Unit =
        synchronized(outboundLock) {
            val call = pendingCalls[id] ?: return
            if (expectedCall != null && call !== expectedCall) return
            if (error == null &&
                call.method in setOf(WsMethods.SESSION_RESUME, "session.activate", WsMethods.SESSION_EVENTS_SINCE)
            ) {
                val raw = if (result is JsonElement) result.toAny() else result
                val payload = raw as? Map<*, *>
                val runtime =
                    if (call.method == WsMethods.SESSION_EVENTS_SINCE) {
                        call.expectedRuntimeId
                    } else {
                        (payload?.get("session_id") as? String)?.takeIf(String::isNotBlank)
                    }
                call.snapshotScope?.let { restoreVaultPrompts(it, payload, runtime, call.snapshotStartedAt) }
            }
            if (!pendingCalls.remove(id, call)) return
            cacheTerminalCapability(call, result, error)
            removeQueuedMessage(id)
            call.timeoutJob?.cancel()
            if (error == null) {
                call.groupCreate?.let { (access, member) ->
                    synchronized(outboundLock) {
                        if (isCurrentGroupAccess(access)) {
                            val raw = if (result is kotlinx.serialization.json.JsonElement) result.toAny() else result
                            val values = raw as? Map<*, *>
                            val payload = values?.get("result") as? Map<*, *> ?: values
                            val runtimeId = payload?.get("session_id") as? String
                            if (!runtimeId.isNullOrBlank()) {
                                access.acknowledgedCreates[member] =
                                    runtimeId to (payload?.get("stored_session_id") as? String)
                            }
                        }
                    }
                }
            }
            if (error != null) {
                call.deferred.completeExceptionally(HermesRpcException(error.message, error.code))
            } else {
                call.deferred.complete(result)
            }
            disconnectIfIdleInBackground()
        }

    /**
     * Fail and clear every in-flight [request]. Called on disconnect /
     * reconnect so callers awaiting a result don't hang across a socket
     * close — mirrors desktop `JsonRpcGatewayClient.rejectAllPending(error)`
     * invoked on socket close.
     */
    fun rejectAllPending(
        error: HermesRpcException =
            HermesRpcException("Connection lost — request cancelled"),
    ): Unit =
        synchronized(outboundLock) {
            groupAccesses.clear()
            terminalCapability = null
            if (pendingCalls.isEmpty()) return
            val snapshot = pendingCalls.toList()
            for ((id, call) in snapshot) {
                if (!pendingCalls.remove(id, call)) continue
                removeQueuedMessage(id)
                Log.w(TAG, "Rejecting pending request on disconnect")
                call.timeoutJob?.cancel()
                call.deferred.completeExceptionally(error)
            }
        }

    // ── Send helpers ─────────────────────────────────────────────────────

    /**
     * Send a JSON-RPC request with the given [method] and optional [params].
     * @return the request id used (can be matched against [WsEvent.RpcResult]).
     */
    fun send(
        method: String,
        params: Map<String, Any> = emptyMap(),
        onSent: ((String) -> Unit)? = null,
    ): String {
        val id = requestId.incrementAndGet().toString()
        val decoratedParams = WsProfileParams.decorate(method, params)
        val request =
            JsonRpcRequest(
                id = id,
                method = method,
                params = decoratedParams.mapValues { it.value.toJsonElement() },
            )
        val json = OkHttpProvider.json.encodeToString(request)
        // PR02 / R03: diagnostics must never receive wire payloads or Throwable details.
        if (BuildConfig.DEBUG) Log.d(TAG, "WS send chars=${json.length}")
        var reconnect = false
        synchronized(outboundLock) {
            val replyPendingBeforeSend = pendingReply
            if (method == WsMethods.PROMPT_SUBMIT) {
                pendingPromptSubmits.add(id)
                pendingReply = true
            }
            onSent?.invoke(id)
            if (method == WsMethods.APPROVAL_RESPOND || method == WsMethods.APPROVAL_RECEIVED ||
                method == WsMethods.CLARIFY_RESPOND || method == WsMethods.CLARIFY_LOCK ||
                method == WsMethods.REQUEST_ANSWER
            ) {
                // Answers require the original RequestRef through writeScoped.
                rejectNotSent(id, replyPendingBeforeSend)
                return@synchronized
            }
            val ws = webSocket
            if (ws != null && connected.get()) {
                val scope = _connectionScope.value
                if (!OfflineSendPolicy.mayQueue(method) &&
                    (
                        scope == null || !matchesCurrentScope(scope) ||
                            (
                                method in WsMethods.PROFILE_SCOPED_METHODS &&
                                    decoratedParams["profile"] != scope.serverProfileId
                            )
                    )
                ) {
                    rejectNotSent(id, replyPendingBeforeSend)
                    return@synchronized
                }
                // Never replay send(true): the server may have executed it even if its response is lost.
                if (!ws.send(json)) {
                    if (webSocket !== ws || !acceptQueuedMessages.get()) {
                        rejectNotSent(id, replyPendingBeforeSend)
                        return@synchronized
                    }
                    if (isRetryableMessage(json)) {
                        Log.w(TAG, "WS rejected outgoing message — queuing for reconnect")
                        queueMessage(id, json)
                        recoverRejectedSocket(ws)
                    } else {
                        Log.w(TAG, "WS outgoing request was not sent — not retrying")
                        rejectNotSent(id, replyPendingBeforeSend)
                        if (withinOutboundLimit(json)) recoverRejectedSocket(ws)
                    }
                }
            } else if (acceptQueuedMessages.get()) {
                if (isRetryableMessage(json)) {
                    Log.d(TAG, "WS disconnected — queuing message")
                    queueMessage(id, json)
                    reconnect = true
                } else {
                    Log.w(TAG, "WS disconnected — request not queued")
                    rejectNotSent(id, replyPendingBeforeSend)
                }
            } else {
                rejectNotSent(id, replyPendingBeforeSend)
            }
        }
        if (reconnect) connect()
        return id
    }

    private fun isRetryableMessage(json: String): Boolean {
        if (!withinOutboundLimit(json)) return false
        val method =
            try {
                OkHttpProvider.json.decodeFromString<JsonRpcRequest>(json).method
            } catch (_: Exception) {
                return false
            }
        if (!OfflineSendPolicy.mayQueue(method)) return false
        return true
    }

    private fun withinOutboundLimit(json: String): Boolean {
        if (json.length > MAX_OUTBOUND_MESSAGE_BYTES) return false
        if (json.length <= MAX_OUTBOUND_MESSAGE_BYTES / 4) return true
        return json.utf8Size() <= MAX_OUTBOUND_MESSAGE_BYTES.toLong()
    }

    private fun rejectNotSent(
        id: String,
        replyPendingBeforeSend: Boolean,
    ) {
        if (pendingPromptSubmits.remove(id) && pendingPromptSubmits.isEmpty() &&
            !replyPendingBeforeSend
        ) {
            pendingReply = false
        }
        val writeScope = pendingCalls[id]?.writeScope
        val error = JsonRpcError(OfflineSendPolicy.NOT_SENT, "Request not sent; reconnect does not submit it")
        resolvePending(id, null, error)
        val event = WsEvent.RpcError(id, error)
        if (writeScope == null) parsedEvents.tryEmit(event)
        emitScoped(writeScope ?: _connectionScope.value, event)
    }

    private fun queueMessage(
        id: String,
        json: String,
    ) {
        if (queuedMessagesById.putIfAbsent(id, json) == null) messageQueue.add(json)
    }

    private fun removeQueuedMessage(id: String) {
        synchronized(outboundLock) {
            queuedMessagesById.remove(id)?.let(messageQueue::remove)
        }
    }

    private fun markQueuedMessageSent(json: String) {
        queuedMessagesById.entries.removeIf { it.value == json }
    }

    private fun recoverRejectedSocket(ws: WebSocket) {
        connected.set(false)
        if (closingSocket === ws) return
        _connectionStatus.value = ConnectionStatus.RECONNECTING
        if (ws.queueSize() == 0L) {
            ws.cancel()
            scheduleReconnect()
            return
        }
        if (intentionalClose.get()) return
        outboundDrainJob?.cancel()
        outboundDrainJob =
            wsScope.launch {
                delay(OUTBOUND_DRAIN_TIMEOUT_MS)
                synchronized(outboundLock) {
                    if (!connected.get() && !intentionalClose.get() && webSocket === ws) {
                        ws.cancel()
                        outboundDrainJob = null
                        scheduleReconnect()
                    }
                }
            }
    }

    /** Convenience: submit a user prompt to an existing session. */
    fun sendMessage(
        sessionId: String,
        text: String,
        onSent: ((String) -> Unit)? = null,
        queued: Boolean = false,
    ): String {
        val params =
            buildMap {
                put("session_id", sessionId)
                put("text", text)
                // Explicit queue semantics: the gateway's busy-input policy
                // forces "run after, never interrupt" when prompt.submit
                // carries queued=true (hermes-agent methods_prompt.py
                // _handle_busy_submit) — used by /queue.
                if (queued) put("queued", true)
            }
        return send(
            method = WsMethods.PROMPT_SUBMIT,
            params = params,
            onSent = onSent,
        )
    }

    /**
     * Convenience: redirect the active model turn while it is still generating
     * (backend `session.redirect`). Fire-and-forget — the backend either rewrites
     * the live turn, queues the correction as the next turn, or rejects it (the
     * caller's ViewModel handles the `4010` fall-back to [sendMessage]).
     */
    fun sendRedirect(
        sessionId: String,
        text: String,
        onSent: ((String) -> Unit)? = null,
    ): String =
        send(
            method = WsMethods.SESSION_REDIRECT,
            params = mapOf("session_id" to sessionId, "text" to text),
            onSent = onSent,
        )

    // ── Sequence tracking & replay on reconnect (issue #1016) ─────────────

    private fun triggerReplay() {
        if (lastSeenSeq.isEmpty() || replayInFlight) return
        replayJob?.cancel()
        replayJob =
            wsScope.launch {
                fetchReplay()
            }
    }

    private suspend fun fetchReplay() {
        if (replayInFlight || lastSeenSeq.isEmpty()) return
        val scope = _connectionScope.value ?: return
        replayInFlight = true
        val sessionsToReplay = lastSeenSeq.keys().toList()
        for (sid in sessionsToReplay) {
            replayHold[sid] = Collections.synchronizedList(mutableListOf())
        }
        try {
            for (sid in sessionsToReplay) {
                val lastSeen = lastSeenSeq[sid] ?: continue
                try {
                    val result =
                        requestFixedScope(
                            scope = scope,
                            method = WsMethods.SESSION_EVENTS_SINCE,
                            params =
                                buildMap {
                                    put("session_id", sid)
                                    put("last_seen", lastSeen)
                                    sessionReplayEpochs[sid]?.let { put("replay_epoch", it) }
                                },
                            timeoutMs = 10_000L,
                        ).await()
                    if (!matchesCurrentScope(scope)) return

                    @Suppress("UNCHECKED_CAST")
                    val resultMap =
                        when (result) {
                            is JsonElement -> result.toAny() as? Map<String, Any?>
                            is Map<*, *> -> result as? Map<String, Any?>
                            else -> null
                        }
                    if (resultMap != null) {
                        val epoch = resultMap["epoch"] as? String
                        val latest = (resultMap["latest_seq"] as? Number)?.toInt()
                        val sessionEpoch = resultMap["replay_epoch"] as? String
                        val previousSessionEpoch = sessionEpoch?.let { sessionReplayEpochs.put(sid, it) }
                        val epochChanged =
                            if (sessionEpoch != null) {
                                previousSessionEpoch != null && previousSessionEpoch != sessionEpoch
                            } else {
                                epoch != null && replayEpoch != null && epoch != replayEpoch
                            }
                        val requiresSnapshot =
                            resultMap["truncated"] == true || resultMap["snapshot_required"] == true || epochChanged
                        if (requiresSnapshot) {
                            reportReplayGap(scope, sid, latest)
                            continue
                        }
                        if (sessionEpoch == null && !epoch.isNullOrEmpty()) replayEpoch = epoch
                        val eventsList = (resultMap["events"] as? List<*>)?.filterIsInstance<Map<String, Any?>>()
                        if (eventsList != null) {
                            for (eventMap in eventsList) {
                                val eventSeq = (eventMap["seq"] as? Number)?.toInt()
                                val eventSid = (eventMap["session_id"] as? String) ?: sid
                                if (eventSeq != null && eventSid.isNotEmpty()) {
                                    val prev = lastSeenSeq[eventSid] ?: 0
                                    if (eventSeq <= prev) continue
                                    lastSeenSeq[eventSid] = eventSeq
                                }
                                val parsedEvent = EventParser.parseParams(eventMap)
                                val finalEvent =
                                    if (parsedEvent is WsEvent.MessageComplete) {
                                        parsedEvent.copy(
                                            storedSessionId =
                                                ActiveSessionHolder.resolveStoredSessionId(
                                                    scope,
                                                    parsedEvent.sessionId,
                                                ),
                                        )
                                    } else {
                                        parsedEvent
                                    }
                                parsedEvents.tryEmit(finalEvent)
                                emitScoped(scope, finalEvent)
                            }
                        }
                        // open_requests was admitted before PendingCall metadata was removed.
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!matchesCurrentScope(scope)) return
                    reportReplayGap(scope, sid, null)
                    Log.w(TAG, "Replay failed: ${e.javaClass.simpleName}")
                }
            }
        } finally {
            for (sid in sessionsToReplay) {
                val held = replayHold.remove(sid)
                if (held != null && matchesCurrentScope(scope)) {
                    synchronized(held) {
                        for ((heldSeq, heldEvent) in held) {
                            if (heldSeq != null) {
                                val prev = lastSeenSeq[sid] ?: 0
                                if (heldSeq <= prev) continue
                                lastSeenSeq[sid] = heldSeq
                            }
                            parsedEvents.tryEmit(heldEvent)
                            emitScoped(scope, heldEvent)
                        }
                    }
                }
            }
            replayInFlight = false
        }
    }

    internal fun adoptReplaySnapshot(
        scope: ConnectionScope,
        runtimeId: String,
        snapshot: Map<*, *>,
    ) {
        if (runtimeId.isBlank() || (snapshot.containsKey("session_id") && snapshot["session_id"] != runtimeId)) return
        val sequence = (snapshot["last_sequence"] as? Number)?.toInt()?.takeIf { it >= 0 } ?: return
        val epoch = (snapshot["replay_epoch"] as? String)?.takeIf(String::isNotBlank) ?: return
        synchronized(outboundLock) {
            if (!matchesCurrentScope(scope)) return
            val previous = sessionReplayEpochs.put(runtimeId, epoch)
            if (previous != null && previous != epoch) {
                lastSeenSeq[runtimeId] = sequence
            } else {
                lastSeenSeq.merge(runtimeId, sequence, ::maxOf)
            }
        }
    }

    private fun reportReplayGap(
        scope: ConnectionScope,
        sessionId: String,
        latest: Int?,
    ) {
        if (!matchesCurrentScope(scope)) return
        if (latest != null && latest >= 0) lastSeenSeq[sessionId] = latest
        emitScoped(scope, gap = true, gapSessionId = sessionId)
    }

    @VisibleForTesting
    internal fun getSeqWatermarks(): Map<String, Int> = HashMap(lastSeenSeq)

    @VisibleForTesting
    internal fun setSeqWatermark(
        sessionId: String,
        seq: Int,
    ) {
        lastSeenSeq[sessionId] = seq
    }

    @VisibleForTesting
    internal fun clearSeqWatermarks() {
        lastSeenSeq.clear()
        sessionReplayEpochs.clear()
        replayHold.clear()
        replayEpoch = null
        replayInFlight = false
    }

    @VisibleForTesting
    internal fun isReplayInFlight(): Boolean = replayInFlight

    @VisibleForTesting
    internal fun setReplayEpochForTest(epoch: String?) {
        replayEpoch = epoch
    }

    @VisibleForTesting
    internal suspend fun fetchReplayForTest() {
        fetchReplay()
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun openSocket() {
        val connectionProfileId = AuthManager.getSelectedProfileId()
        val serverProfileId = AuthManager.activeProfileId.value
        val baseUrl = AuthManager.getBaseUrl()
        val generation =
            synchronized(outboundLock) {
                if (intentionalClose.get()) return
                connectionGeneration.incrementAndGet()
            }
        if (!refreshWsTicketIfNeeded(generation)) {
            Log.w(TAG, "Aborting openSocket: WS ticket refresh failed")
            return
        }
        if (!isCurrentGeneration(generation)) return
        val url = AuthManager.wsUrl()
        if (connectionProfileId != AuthManager.getSelectedProfileId() ||
            serverProfileId != AuthManager.activeProfileId.value ||
            baseUrl != AuthManager.getBaseUrl() || !isCurrentGeneration(generation)
        ) {
            abandonSocketOpening(generation, reconnect = true)
            return
        }
        val identity = serverIdentity(baseUrl)
        if (identity == null) {
            abandonSocketOpening(generation, reconnect = false)
            return
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Opening WebSocket")

        val request = Request.Builder().url(url).build()
        val scope =
            ConnectionScope(
                identity,
                connectionProfileId,
                serverProfileId,
                processIdentity,
                generation,
            )
        synchronized(outboundLock) {
            if (!isCurrentGeneration(generation)) return
            connectionBaseUrl = baseUrl
            _connectionScope.value = scope
        }
        val newSocket = OkHttpProvider.websocket.newWebSocket(request, WsListenerImpl(generation, scope))
        synchronized(outboundLock) {
            if (connectionGeneration.get() == generation && !intentionalClose.get()) {
                webSocket = newSocket
            } else {
                newSocket.cancel()
            }
        }
    }

    private fun isCurrentGeneration(generation: Int): Boolean =
        connectionGeneration.get() == generation && !intentionalClose.get()

    private fun abandonSocketOpening(
        generation: Int,
        reconnect: Boolean,
    ) {
        synchronized(outboundLock) {
            if (!isCurrentGeneration(generation)) return
            _connectionStatus.value = if (reconnect) ConnectionStatus.RECONNECTING else ConnectionStatus.DISCONNECTED
            if (reconnect) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        synchronized(outboundLock) {
            if (intentionalClose.get() ||
                _connectionStatus.value == ConnectionStatus.AUTH_EXPIRED ||
                reconnectJob?.isActive == true
            ) {
                return
            }
            if (!AuthManager.isAutoReconnect()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Auto-reconnect disabled")
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                return
            }
            if (!NetworkMonitor.isConnected.value) {
                Log.d(TAG, "No network available — delaying reconnect scheduling")
                _connectionStatus.value = ConnectionStatus.NO_NETWORK
                return
            }
            val reconnectDelay = currentBackoff
            currentBackoff =
                (currentBackoff * BACKOFF_MULTIPLIER)
                    .toLong()
                    .coerceAtMost(MAX_BACKOFF_MS)
            if (BuildConfig.DEBUG) Log.d(TAG, "Reconnecting in ${reconnectDelay}ms …")

            reconnectJob =
                wsScope.launch {
                    delay(reconnectDelay)
                    val owner = currentCoroutineContext()[Job]
                    val shouldOpen =
                        synchronized(outboundLock) {
                            if (reconnectJob !== owner) {
                                false
                            } else {
                                reconnectJob = null
                                !intentionalClose.get() &&
                                    !connected.get() &&
                                    _connectionStatus.value != ConnectionStatus.AUTH_EXPIRED
                            }
                        }
                    if (shouldOpen) openSocket()
                }
        }
    }

    // ── Listener ─────────────────────────────────────────────────────────

    private class WsListenerImpl(
        private val generation: Int,
        private val scope: ConnectionScope,
    ) : WebSocketListener() {
        private var advertisedServerRequests = false
        private var requestedTerminalCapabilities = false

        private fun isCurrent(): Boolean = isCurrentGeneration(generation)

        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            synchronized(outboundLock) {
                if (!isCurrent()) {
                    webSocket.close(1000, "Superseded")
                    return
                }
                HermesWsClient.webSocket = webSocket
                closingSocket = null
                outboundDrainJob?.cancel()
                outboundDrainJob = null
                Log.i(TAG, "WebSocket opened")
                connected.set(true)
                _connectionStatus.value = ConnectionStatus.CONNECTED
                currentBackoff = initialBackoffMs
                startHealthTracking()

                while (true) {
                    val msg = messageQueue.peek() ?: break
                    if (!isRetryableMessage(msg)) {
                        Log.w(TAG, "Dropping non-replayable queued request")
                        messageQueue.poll()
                        markQueuedMessageSent(msg)
                        continue
                    }
                    if (BuildConfig.DEBUG) Log.d(TAG, "WS queue send chars=${msg.length}")
                    if (!webSocket.send(msg)) {
                        recoverRejectedSocket(webSocket)
                        break
                    }
                    messageQueue.poll()
                    markQueuedMessageSent(msg)
                }
                triggerReplay()
            }
            disconnectIfIdleInBackground()
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (!isCurrent() || HermesWsClient.webSocket !== webSocket) return
            if (BuildConfig.DEBUG) Log.d(TAG, "WS receive chars=${text.length}")
            lastPongTimestamp = System.currentTimeMillis()
            var handledByScopedWrite = false
            // Resolve any in-flight `request()` awaiting this RPC result/error
            // (issue #526) before fanning the parsed event out to collectors.
            val event =
                try {
                    val rpc = OkHttpProvider.json.decodeFromString<JsonRpcResponse>(text)
                    if (rpc.id != null && rpc.method != null) {
                        val request = EventParser.parse(rpc)
                        if (request is WsEvent.VaultRequest || request is WsEvent.ApprovalRequest ||
                            request is WsEvent.ClarifyRequest
                        ) {
                            // Approvals stay open on the server until approval.respond settles them.
                            emitScoped(scope, request)
                        } else {
                            // Capability advertisement must not leave unsupported requests hanging.
                            webSocket.send(
                                OkHttpProvider.json.encodeToString(
                                    JsonRpcResponse(
                                        id = rpc.id,
                                        error = JsonRpcError(-32601, "Request unsupported on mobile"),
                                    ),
                                ),
                            )
                        }
                        return
                    }
                    if (!advertisedServerRequests && rpc.method == "event" &&
                        rpc.params?.get("type") == "gateway.ready".toJsonElement()
                    ) {
                        // The gateway otherwise declines masked prompts immediately without sending them.
                        advertisedServerRequests =
                            webSocket.send(
                                OkHttpProvider.json.encodeToString(
                                    JsonRpcRequest(
                                        id = "mobile-capabilities-${scope.generation}",
                                        method = "client.capabilities",
                                        params = mapOf("server_requests" to true.toJsonElement()),
                                    ),
                                ),
                            )
                    }
                    if (!requestedTerminalCapabilities && rpc.method == "event" &&
                        rpc.params?.get("type") == "gateway.ready".toJsonElement() && matchesCurrentScope(scope)
                    ) {
                        requestedTerminalCapabilities = true
                        // Existing read-only method, once per accepted socket; no submit-time polling/reconnect.
                        requestFixedScope(scope, "gateway.capabilities", emptyMap(), 5_000L)
                    }
                    // Resolve pending request() calls with the RAW JsonElement from
                    // rpc.result, BEFORE EventParser.parse() converts it via toAny().
                    // This preserves integer/float type fidelity needed by callers
                    // that re-deserialize the result into typed data classes.
                    val rpcId = rpc.id
                    handledByScopedWrite = rpcId != null && pendingCalls[rpcId]?.writeScope != null
                    if (rpcId != null && rpc.error == null && rpc.result != null) {
                        synchronized(outboundLock) { pendingPromptSubmits.remove(rpcId) }
                        removeQueuedMessage(rpcId)
                        resolvePending(rpcId, rpc.result, null)
                    }

                    if (rpc.id == null) {
                        @Suppress("UNCHECKED_CAST")
                        val params = rpc.params?.toAny() as? Map<String, Any?>
                        val sid =
                            (params?.get("session_id") as? String)
                                ?: ((params?.get("payload") as? Map<*, *>)?.get("session_id") as? String)
                        if (params?.get("type") == "session.replay_gap" && !sid.isNullOrBlank()) {
                            val payload = params["payload"] as? Map<*, *>
                            (payload?.get("replay_epoch") as? String)?.let { sessionReplayEpochs[sid] = it }
                            reportReplayGap(scope, sid, (payload?.get("latest_seq") as? Number)?.toInt())
                            return
                        }
                        val seq = (params?.get("seq") as? Number)?.toInt()
                        if (!sid.isNullOrBlank() && seq != null) {
                            val epoch = params["replay_epoch"] as? String
                            val previous = epoch?.let { sessionReplayEpochs.put(sid, it) }
                            if (previous != null && previous != epoch) reportReplayGap(scope, sid, seq - 1)
                            val prev = lastSeenSeq[sid] ?: 0
                            if (seq <= prev) {
                                return
                            }
                            val parsed = EventParser.parse(rpc, text)
                            val finalEvent =
                                if (parsed is WsEvent.MessageComplete) {
                                    parsed.copy(
                                        storedSessionId =
                                            ActiveSessionHolder.resolveStoredSessionId(
                                                scope,
                                                parsed.sessionId,
                                            ),
                                    )
                                } else {
                                    parsed
                                }
                            if (replayInFlight && replayHold.containsKey(sid)) {
                                replayHold[sid]?.add(seq to finalEvent)
                                return
                            }
                            lastSeenSeq[sid] = seq
                            parsedEvents.tryEmit(finalEvent)
                            emitScoped(scope, finalEvent)
                            return
                        }
                    }

                    val parsed = EventParser.parse(rpc, text)
                    if (parsed is WsEvent.MessageComplete) {
                        parsed.copy(
                            storedSessionId = ActiveSessionHolder.resolveStoredSessionId(scope, parsed.sessionId),
                        )
                    } else {
                        parsed
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WS parse failed: ${e.javaClass.simpleName}")
                    WsEvent.Unknown(text)
                }
            when (event) {
                is WsEvent.RpcResult -> {
                    synchronized(outboundLock) { pendingPromptSubmits.remove(event.id) }
                    removeQueuedMessage(event.id)
                    resolvePending(event.id, event.result, null)
                }

                is WsEvent.RpcError -> {
                    synchronized(outboundLock) {
                        if (pendingPromptSubmits.remove(event.id) &&
                            pendingPromptSubmits.isEmpty()
                        ) {
                            pendingReply = false
                        }
                    }
                    removeQueuedMessage(event.id)
                    resolvePending(event.id, null, event.error)
                    disconnectIfIdleInBackground()
                }

                else -> {}
            }
            // tryEmit on a DROP_OLDEST flow only returns false when the
            // buffer is full AND no subscriber is draining; with extraBuffer=512
            // and always-on init collectors this is unreachable in practice.
            // Typed submissions own their ACK/error UI. Do not also label them as legacy failures.
            if (!handledByScopedWrite) parsedEvents.tryEmit(event)
            emitScoped(scope, event)
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            synchronized(outboundLock) {
                if (!isCurrent()) {
                    webSocket.close(code, reason)
                    return
                }
                closingSocket = webSocket
                // Do NOT log [reason] — it may carry server-side context.
                Log.d(TAG, "WebSocket closing: $code")
                if (code == 4001 || code == 4401 ||
                    reason.contains("unauthorized", ignoreCase = true) ||
                    reason.startsWith("auth:", ignoreCase = true)
                ) {
                    _connectionStatus.value = ConnectionStatus.AUTH_EXPIRED
                }
                webSocket.close(code, reason)
            }
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            synchronized(outboundLock) {
                if (!isCurrent()) return
                connectionGeneration.incrementAndGet()
                closingSocket = null
                outboundDrainJob?.cancel()
                outboundDrainJob = null
                if (HermesWsClient.webSocket === webSocket) HermesWsClient.webSocket = null
                // Do NOT log [reason] — it may carry server-side context. The
                // reason is still inspected internally to detect auth failures.
                Log.i(TAG, "WebSocket closed: $code")
                connected.set(false)
                ActiveSessionHolder.clear()
                stopHealthTracking()
                if (code == 4001 || code == 4401 ||
                    reason.contains("unauthorized", ignoreCase = true) ||
                    reason.startsWith("auth:", ignoreCase = true)
                ) {
                    _connectionStatus.value = ConnectionStatus.AUTH_EXPIRED
                } else if (_connectionStatus.value != ConnectionStatus.AUTH_EXPIRED) {
                    _connectionStatus.value = ConnectionStatus.RECONNECTING
                    scheduleReconnect()
                }
            }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            synchronized(outboundLock) {
                if (!isCurrent()) return
                connectionGeneration.incrementAndGet()
                closingSocket = null
                outboundDrainJob?.cancel()
                outboundDrainJob = null
                if (HermesWsClient.webSocket === webSocket) HermesWsClient.webSocket = null
                // Log the exception class only — [Throwable.message] can leak URLs
                // or headers. The message is still inspected internally for auth
                // detection.
                Log.e(TAG, "WebSocket failure: ${t.javaClass.simpleName}")
                connected.set(false)
                ActiveSessionHolder.clear()
                stopHealthTracking()
                val code = response?.code ?: 0
                if (code == 401 || t.message?.contains(
                        "401",
                    ) == true || t.message?.contains("unauthorized", ignoreCase = true) == true
                ) {
                    _connectionStatus.value = ConnectionStatus.AUTH_EXPIRED
                } else if (_connectionStatus.value != ConnectionStatus.AUTH_EXPIRED) {
                    _connectionStatus.value = ConnectionStatus.RECONNECTING
                    scheduleReconnect()
                }
            }
        }
    }
}
