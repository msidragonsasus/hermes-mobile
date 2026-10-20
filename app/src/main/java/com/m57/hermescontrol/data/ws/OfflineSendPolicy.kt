package com.m57.hermescontrol.data.ws

/** Only explicit read contracts may survive a disconnected socket. Unknown methods are writes. */
object OfflineSendPolicy {
    const val NOT_SENT = -32098

    private val replayableReads =
        setOf(
            WsMethods.PING,
            WsMethods.SESSION_LIST,
            WsMethods.SESSION_ACTIVE_LIST,
            WsMethods.SESSION_STATUS,
            WsMethods.SESSION_HISTORY,
            WsMethods.SESSION_EVENTS_SINCE,
            WsMethods.SESSION_EVENTS_STATS,
            WsMethods.SESSION_USAGE,
            WsMethods.SESSION_CONTEXT_BREAKDOWN,
            WsMethods.APPROVAL_PENDING,
            WsMethods.PROCESS_LIST,
            WsMethods.PROFILES_LIST,
            WsMethods.COMMANDS_CATALOG,
        )

    fun mayQueue(method: String): Boolean = method in replayableReads
}
