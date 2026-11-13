package com.m57.hermescontrol.domain.hermes

/** Shared monotonic time domain for live evidence and its history freshness deadline. */
fun sessionActivityNowMs(): Long = System.nanoTime() / 1_000_000L

/**
 * Per-conversation status shown in history, highest priority first.
 * Absence means idle or no evidence; history never claims success, failure or unread.
 * [BACKGROUND]: the foreground turn is idle, but this owner's own subagent or process still runs.
 */
enum class SessionActivity { WAITING_APPROVAL, NEEDS_INPUT, WAITING, RUNNING, STARTING, BACKGROUND, UNCERTAIN }

/** `session.active_list` row status. Anything else the server sends is [UNKNOWN]. */
enum class LiveStatus { WORKING, STARTING, WAITING, IDLE, UNKNOWN }

/**
 * One live turn owner. [runtimeId] is the gateway id; [savedId] the stored `session_key`.
 * [aliases] are earlier `session_key`s this same runtime reported in this exact scope while it
 * stayed busy (compression rotates the key before cached history catches up).
 */
data class LiveSession(
    val runtimeId: String,
    val savedId: String?,
    val status: LiveStatus,
    val aliases: Set<String> = emptySet(),
)

sealed interface LiveRead {
    data class Available(
        val scope: ConnectionScope,
        val sessions: List<LiveSession>,
    ) : LiveRead

    data class Unavailable(
        val reason: ReadFailure,
    ) : LiveRead
}

/** Owner-scoped `subagent.list` + `process.list` of one runtime: is any of its own child work running? */
sealed interface BackgroundRead {
    data class Available(
        val running: Boolean,
    ) : BackgroundRead

    data class Unavailable(
        val reason: ReadFailure,
    ) : BackgroundRead
}

/** Last child-work answer; null [confirmedAt] means no valid proof, not conclusive idle. */
data class BackgroundWork(
    val running: Boolean,
    val confirmedAt: Long?,
    val failure: ReadFailure? = null,
)

/**
 * Last roster for [scope]; [sessions] is the last successful read, [failure] the latest attempt.
 * [background] holds owner-scoped child-work evidence for idle runtimes of this exact scope.
 */
data class LiveRoster(
    val scope: ConnectionScope,
    val sessions: List<LiveSession>?,
    val readAt: Long,
    val failure: ReadFailure? = null,
    val background: Map<String, BackgroundWork> = emptyMap(),
    // A stopped/non-transient observation stays invalid through failed reads until a new success.
    val observed: Boolean = true,
)

/** A roster older than this is no longer liveness evidence (about three missed polls). */
const val LIVE_ROSTER_FRESH_MS = 12_000L

/**
 * History status for the drawer. [rechecking] IDs show evidence that is still fresh while the
 * latest refresh failed; [unverified] means the status feed for this destination cannot be
 * confirmed right now. [validUntil] is when freshness next expires and the projection changes.
 */
data class HistoryActivity(
    val activity: Map<String, SessionActivity> = emptyMap(),
    val rechecking: Set<String> = emptySet(),
    val unverified: Boolean = false,
    val validUntil: Long? = null,
)

fun projectSessionActivity(
    connection: TransportConnection,
    roster: LiveRoster?,
    attention: List<AttentionItem>,
    now: Long,
): Map<String, SessionActivity> = projectHistoryActivity(connection, roster, attention, now).activity

/**
 * Projects the live roster, owner-scoped background evidence and verified requests onto history
 * IDs (runtime, stored and the runtime's retained aliases).
 *
 * The roster is the liveness authority: `working` runs, `starting` is admitted but not yet
 * executing, `waiting` is a generic wait that an exact pending request of this socket generation
 * may refine, `idle`/absent are quiet even when a request was never settled locally. An idle
 * runtime whose own subagent/process was read as running is [SessionActivity.BACKGROUND].
 *
 * Freshness is separate from the latest read error: a single transient failure on the unchanged
 * connected scope keeps evidence younger than [LIVE_ROSTER_FRESH_MS], marked [HistoryActivity.rechecking].
 * Expired, stopped, non-transient, disconnected or older-generation evidence turns non-idle rows
 * [SessionActivity.UNCERTAIN] and the feed [HistoryActivity.unverified]; another server/profile's
 * roster is ignored. Only when the server has no roster at all do exact pending requests stand alone.
 */
fun projectHistoryActivity(
    connection: TransportConnection,
    roster: LiveRoster?,
    attention: List<AttentionItem>,
    now: Long,
): HistoryActivity {
    val current = connection.scope
    val live = connection.state == LinkState.CONNECTED && current != null
    val result = mutableMapOf<String, SessionActivity>()
    val rechecking = mutableSetOf<String>()

    fun put(
        ids: Collection<String>,
        activity: SessionActivity,
    ) = ids.forEach { id ->
        val existing = result[id]
        if (existing == null || activity < existing) result[id] = activity
    }

    fun pendingRequest(ids: Set<String>): SessionActivity? {
        val pending =
            attention.filter {
                live && it.status == AttentionStatus.PENDING && it.ref.scope == current &&
                    it.ref.session.runtimeId in ids
            }
        return when {
            pending.any { it.kind == AttentionKind.APPROVAL } -> SessionActivity.WAITING_APPROVAL
            pending.isNotEmpty() -> SessionActivity.NEEDS_INPUT
            else -> null
        }
    }

    val sameDestination =
        roster != null && (current == null || roster.scope.copy(generation = current.generation) == current)
    val sessions = roster?.sessions
    if (sessions == null) {
        if (roster == null || roster.failure == ReadFailure.UNSUPPORTED) {
            attention.map { it.ref.session.runtimeId }.distinct().forEach { id ->
                pendingRequest(setOf(id))?.let { put(listOf(id), it) }
            }
        }
        return HistoryActivity(result, unverified = sameDestination && roster?.failure != ReadFailure.UNSUPPORTED)
    }

    fun fresh(at: Long) = now - at in 0..LIVE_ROSTER_FRESH_MS
    val exact = live && roster.scope == current && roster.observed && fresh(roster.readAt)
    val confirmed = exact && roster.failure == null
    val retained = exact && roster.failure == ReadFailure.TIMEOUT_OR_UNAVAILABLE
    // Direct claims of the latest read always outrank an alias another row remembers.
    val direct = sessions.flatMapTo(mutableSetOf()) { listOfNotNull(it.runtimeId, it.savedId) }

    fun idsOf(session: LiveSession) = setOfNotNull(session.runtimeId, session.savedId) + (session.aliases - direct)
    if (!confirmed && !retained) {
        if (sameDestination) {
            sessions
                .filter {
                    val work = roster.background[it.runtimeId]
                    it.status != LiveStatus.IDLE || work?.running == true || work?.failure != null
                }.forEach { put(idsOf(it), SessionActivity.UNCERTAIN) }
        }
        return HistoryActivity(result, unverified = sameDestination)
    }
    var validUntil = roster.readAt + LIVE_ROSTER_FRESH_MS + 1
    sessions.forEach { session ->
        val ids = idsOf(session)
        val activity =
            when (session.status) {
                LiveStatus.IDLE -> {
                    val work = roster.background[session.runtimeId] ?: return@forEach
                    if (!work.running && work.failure == null && work.confirmedAt != null) return@forEach
                    val confirmedAt = work.confirmedAt
                    if (!work.running || confirmedAt == null || !fresh(confirmedAt) ||
                        (work.failure != null && work.failure != ReadFailure.TIMEOUT_OR_UNAVAILABLE)
                    ) {
                        SessionActivity.UNCERTAIN
                    } else {
                        validUntil = minOf(validUntil, confirmedAt + LIVE_ROSTER_FRESH_MS + 1)
                        if (work.failure != null) rechecking += ids
                        SessionActivity.BACKGROUND
                    }
                }

                LiveStatus.UNKNOWN -> {
                    SessionActivity.UNCERTAIN
                }

                LiveStatus.WORKING -> {
                    SessionActivity.RUNNING
                }

                // Admitted/queued: the agent has not started executing yet.
                LiveStatus.STARTING -> {
                    SessionActivity.STARTING
                }

                LiveStatus.WAITING -> {
                    pendingRequest(ids) ?: SessionActivity.WAITING
                }
            }
        put(ids, activity)
        if (retained && activity != SessionActivity.UNCERTAIN) rechecking += ids
    }
    return HistoryActivity(
        result,
        rechecking.filterTo(mutableSetOf()) { result[it] != null && result[it] != SessionActivity.UNCERTAIN },
        result.values.any { it == SessionActivity.UNCERTAIN },
        validUntil,
    )
}
