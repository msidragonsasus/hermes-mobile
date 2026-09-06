package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.domain.hermes.ConnectionScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide mirror of the active chat session id.
 *
 * The desktop `process.list` / `process.kill` RPCs are session-scoped — the
 * gateway returns `4001 "session not found"` without a valid `session_id`, and
 * mobile has no global "current session" holder (ChatViewModel keeps it
 * privately). This singleton holds the last-known active session id so
 * session-scoped drawer screens (e.g. the Processes screen, issue #532) can
 * issue those RPCs. ChatViewModel writes here on every switch/resume; it is a
 * best-effort mirror and may be null when no chat session has been opened yet.
 */
object ActiveSessionHolder {
    private val _activeSessionId = MutableStateFlow<String?>(null)
    private var activeStoredSessionId: String? = null
    private var activeScope: ConnectionScope? = null
    private val notificationSessions = LinkedHashMap<Pair<ConnectionScope, String>, String>()

    /** The currently active chat session id, or null if none is known yet. */
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    /** Update the active session id. Pass null to clear (e.g. on logout). */
    @Synchronized
    fun set(
        sessionId: String?,
        storedSessionId: String? = null,
    ) {
        _activeSessionId.value = sessionId
        activeStoredSessionId = storedSessionId
        activeScope = null
        if (sessionId == null) notificationSessions.clear()
    }

    @Synchronized
    fun setScoped(
        sessionId: String?,
        storedSessionId: String?,
        scope: ConnectionScope?,
    ) {
        set(sessionId, storedSessionId)
        activeScope = scope
    }

    /** Notifications cannot infer that a runtime ID is also a saved ID or belongs to another profile. */
    @Synchronized
    fun resolveStoredSessionId(
        scope: ConnectionScope,
        sessionId: String?,
    ): String? =
        activeStoredSessionId.takeIf {
            activeScope == scope && sessionId != null && sessionId == _activeSessionId.value
        } ?: sessionId?.let { notificationSessions[scope to it] }

    /** Remember a confirmed background resume without changing the user's active conversation. */
    @Synchronized
    fun rememberNotificationSession(
        scope: ConnectionScope,
        runtimeId: String,
        storedId: String,
    ) {
        notificationSessions[scope to runtimeId] = storedId
        while (notificationSessions.size > 32) notificationSessions.remove(notificationSessions.keys.first())
    }

    @Synchronized
    fun resolveStoredSessionId(sessionId: String?): String? =
        if (sessionId == _activeSessionId.value) activeStoredSessionId ?: sessionId else sessionId

    @Synchronized
    fun resolveRuntimeSessionId(sessionId: String): String? =
        _activeSessionId.value.takeIf { activeStoredSessionId == sessionId }

    @Synchronized
    fun clear() {
        _activeSessionId.value = null
        activeStoredSessionId = null
        activeScope = null
        notificationSessions.clear()
    }
}
