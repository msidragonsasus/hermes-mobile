package com.m57.hermescontrol.domain.hermes

import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** Earlier `session_key`s remembered per busy runtime owner; compression rarely rotates more. */
const val MAX_LIVE_ALIASES = 8

/** Idle owners checked for their own child work per batch, and how many of those read at once. */
const val MAX_BACKGROUND_TARGETS = 4
const val BACKGROUND_READ_CONCURRENCY = 2

/** Recently busy runtime owners remembered per scope as background-check candidates. */
private const val MAX_BUSY_OWNERS = 16

/**
 * Polls the single live roster for whoever is looking at it (the history drawer).
 *
 * [poll] runs only while the caller's coroutine is active, one read at a time, for the exact
 * connected scope. A scope change or disconnect cancels the in-flight read, so a late result can
 * never land on another scope; failures keep the last rows but mark them failed, and the first
 * failure after a success retries after [retryDelayMs] instead of the full cadence.
 * Stopping, and starting a new observation, also mark the last rows unobserved until a new read.
 *
 * A runtime keeps the earlier `session_key`s it reported while it stayed busy in this exact scope
 * (compression rotation). Idle, removal, a direct claim by another row or a new scope settles them.
 *
 * Background: after a successful read, at most [MAX_BACKGROUND_TARGETS] idle owners — the current
 * chat ([currentRuntime]), owners seen busy in this scope, and owners whose child work was running —
 * are checked with the owner-scoped [readBackground], one batch at a time and
 * [BACKGROUND_READ_CONCURRENCY] reads at once, beside (never in front of) the roster loop.
 */
class LiveRosterPoller(
    private val read: suspend (ConnectionScope) -> LiveRead,
    private val now: () -> Long = ::sessionActivityNowMs,
    private val intervalMs: Long = 4_000L,
    private val timeoutMs: Long = 6_000L,
    private val retryDelayMs: Long = 1_000L,
    private val readBackground: suspend (ConnectionScope, String) -> BackgroundRead = { _, _ ->
        BackgroundRead.Unavailable(ReadFailure.UNSUPPORTED)
    },
    private val currentRuntime: (ConnectionScope) -> String? = { null },
) {
    private val mutableRoster = MutableStateFlow<LiveRoster?>(null)
    val roster: StateFlow<LiveRoster?> = mutableRoster.asStateFlow()

    private val owners = AtomicInteger()

    // Per-scope background bookkeeping, guarded by this instance's lock.
    private var stateScope: ConnectionScope? = null
    private val busyOwners = LinkedHashSet<String>()
    private var backgroundUnsupported = false
    private val backgroundReadOrder = mutableMapOf<String, Long>()
    private var backgroundReadSequence = 0L

    suspend fun poll(connection: Flow<TransportConnection>) {
        // The latest caller owns the roster; an older owner's late result or cleanup is ignored.
        val owner = owners.incrementAndGet()
        try {
            connection
                .map { it.scope.takeIf { _ -> it.state == LinkState.CONNECTED } }
                .distinctUntilChanged()
                .collectLatest { scope ->
                    if (scope == null) return@collectLatest
                    // Rows read before this observation began are not fresh until this read returns.
                    markUnobserved(owner)
                    coroutineScope {
                        var background: Job? = null
                        var failures = 0
                        while (true) {
                            val result =
                                withTimeoutOrNull(timeoutMs) { read(scope) }
                                    ?: LiveRead.Unavailable(ReadFailure.TIMEOUT_OR_UNAVAILABLE)
                            val targets = accept(owner, scope, result)
                            if (targets.isNotEmpty() && background?.isActive != true) {
                                background = launch { checkBackground(owner, scope, targets) }
                            }
                            failures = if (result is LiveRead.Available && result.scope == scope) 0 else failures + 1
                            delay(if (failures == 1) retryDelayMs else intervalMs)
                        }
                    }
                }
        } finally {
            // Closed drawer or stopped app: keep the rows, but only as uncertain evidence.
            markUnobserved(owner)
        }
    }

    private suspend fun checkBackground(
        owner: Int,
        scope: ConnectionScope,
        targets: List<String>,
    ) {
        val gate = Semaphore(BACKGROUND_READ_CONCURRENCY)
        coroutineScope {
            targets.forEach { runtimeId ->
                launch {
                    gate.withPermit {
                        if (!beginBackgroundRead(owner, scope, runtimeId)) return@withPermit
                        val result =
                            withTimeoutOrNull(timeoutMs) { readBackground(scope, runtimeId) }
                                ?: BackgroundRead.Unavailable(ReadFailure.TIMEOUT_OR_UNAVAILABLE)
                        acceptBackground(owner, scope, runtimeId, result)
                    }
                }
            }
        }
    }

    @Synchronized
    private fun beginBackgroundRead(
        owner: Int,
        scope: ConnectionScope,
        runtimeId: String,
    ): Boolean {
        val current = mutableRoster.value
        if (owner != owners.get() || stateScope != scope || current?.scope != scope || !current.observed) return false
        if (current.sessions?.any { it.runtimeId == runtimeId && it.status == LiveStatus.IDLE } != true) return false
        backgroundReadOrder[runtimeId] = ++backgroundReadSequence
        return true
    }

    @Synchronized
    private fun markUnobserved(owner: Int) {
        val current = mutableRoster.value
        if (owner != owners.get() || current == null) return
        mutableRoster.value =
            current.copy(
                failure = ReadFailure.OFFLINE,
                background =
                    current.background.mapValues { (_, work) ->
                        work.copy(confirmedAt = null, failure = ReadFailure.OFFLINE)
                    },
                observed = false,
            )
    }

    /** Publishes the read and returns the idle owners whose child work should be checked now. */
    @Synchronized
    private fun accept(
        owner: Int,
        scope: ConnectionScope,
        result: LiveRead,
    ): List<String> {
        if (owner != owners.get()) return emptyList()
        if (stateScope != scope) {
            stateScope = scope
            busyOwners.clear()
            backgroundUnsupported = false
            backgroundReadOrder.clear()
            backgroundReadSequence = 0L
        }
        val previous = mutableRoster.value?.takeIf { it.scope == scope }
        if (result !is LiveRead.Available || result.scope != scope) {
            val reason = (result as? LiveRead.Unavailable)?.reason ?: ReadFailure.SCOPE_CHANGED
            val transient = reason == ReadFailure.TIMEOUT_OR_UNAVAILABLE
            val background =
                previous?.background.orEmpty().mapValues { (_, work) ->
                    if (transient) work else work.copy(confirmedAt = null, failure = reason)
                }
            mutableRoster.value =
                LiveRoster(
                    scope,
                    previous?.sessions,
                    previous?.readAt ?: 0L,
                    reason,
                    background,
                    previous?.observed == true && transient,
                )
            return emptyList()
        }
        val sessions = withAliases(previous?.sessions, result.sessions)
        val byRuntime = sessions.associateBy { it.runtimeId }
        busyOwners.retainAll(byRuntime.keys)
        // Busy/idle churn must not erase attempt age; only actual removal forgets an owner.
        backgroundReadOrder.keys.retainAll(byRuntime.keys)
        sessions.filter { it.status != LiveStatus.IDLE }.forEach {
            busyOwners.remove(it.runtimeId)
            busyOwners.add(it.runtimeId)
        }
        while (busyOwners.size > MAX_BUSY_OWNERS) busyOwners.remove(busyOwners.first())
        // A busy foreground supersedes child-work evidence and removal settles it; idle rechecks.
        val background = previous?.background.orEmpty().filterKeys { byRuntime[it]?.status == LiveStatus.IDLE }
        mutableRoster.value = LiveRoster(scope, sessions, now(), null, background)
        if (backgroundUnsupported) return emptyList()
        val active = currentRuntime(scope)
        val recent = busyOwners.toList().asReversed()
        return sessions
            .filter { it.status == LiveStatus.IDLE }
            .map { it.runtimeId }
            .filter { it == active || it in busyOwners || background[it]?.running == true }
            .sortedWith(
                compareBy(
                    { if (it == active) 0 else 1 },
                    // Oldest attempted first: permanently running or failing owners cannot starve the rest.
                    { backgroundReadOrder[it] ?: Long.MIN_VALUE },
                    { if (background[it]?.running == true) 0 else 1 },
                    { recent.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } },
                ),
            ).take(MAX_BACKGROUND_TARGETS)
    }

    /** Same busy runtime, consecutive reads of one scope: carry its earlier keys, bounded. */
    private fun withAliases(
        previous: List<LiveSession>?,
        rows: List<LiveSession>,
    ): List<LiveSession> {
        val direct = rows.flatMapTo(mutableSetOf()) { listOfNotNull(it.runtimeId, it.savedId) }
        return rows.map { row ->
            val prior = previous?.firstOrNull { it.runtimeId == row.runtimeId }
            if (prior == null || prior.status == LiveStatus.IDLE || row.status == LiveStatus.IDLE) {
                row.copy(aliases = emptySet())
            } else {
                val remembered = (prior.aliases + listOfNotNull(prior.savedId)) - direct
                row.copy(aliases = remembered.toList().takeLast(MAX_LIVE_ALIASES).toSet())
            }
        }
    }

    @Synchronized
    private fun acceptBackground(
        owner: Int,
        scope: ConnectionScope,
        runtimeId: String,
        result: BackgroundRead,
    ) {
        val current = mutableRoster.value
        if (owner != owners.get() || current == null || current.scope != scope || stateScope != scope) return
        // Late answer for an owner that turned busy or disappeared meanwhile: nothing to apply.
        if (current.sessions?.any { it.runtimeId == runtimeId && it.status == LiveStatus.IDLE } != true) return
        val prior = current.background[runtimeId]
        val next =
            when (result) {
                is BackgroundRead.Available -> {
                    if (!result.running) busyOwners.remove(runtimeId)
                    BackgroundWork(result.running, now())
                }

                is BackgroundRead.Unavailable -> {
                    if (result.reason == ReadFailure.UNSUPPORTED && prior == null) backgroundUnsupported = true
                    // Failed/unsupported with no prior answer is explicit unknown, never conclusive none.
                    val proof = prior?.confirmedAt?.takeIf { result.reason == ReadFailure.TIMEOUT_OR_UNAVAILABLE }
                    BackgroundWork(prior?.running ?: false, proof, result.reason)
                }
            }
        mutableRoster.value = current.copy(background = current.background + (runtimeId to next))
    }
}
