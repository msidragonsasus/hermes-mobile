package com.m57.hermescontrol.ui.chat

/** Explicit v2 fixture: simulates guarded ordinary STREAMING admission, not legacy migration. */
fun acceptedAuthorityFixture(
    root: Long? = null,
    final: Long? = null,
    rows: List<Long> = emptyList(),
    time: Long? = 77L,
    members: List<String> = emptyList(),
    complete: Boolean = false,
    safe: Boolean = true,
    gap: Boolean = false,
): ReplyAuthorityFacts {
    val attemptUid =
        java.util.UUID
            .randomUUID()
            .toString()
    val record = ResponseRecord(id = "fixture", messageIds = members)
    val admitted =
        ResponseLedger(listOf(record))
            .acceptInput(
                attemptUid,
                record.id,
                null,
                true,
                "fixture-rpc",
                "STREAMING",
                "fixture-target",
                "fixture-session",
                1L,
                null,
                root,
                null,
            ).responses
            .single()
            .let { it.copy(authority = it.authority.copy(deliveryGap = gap)) }
    return if (time == null) {
        admitted.authority
    } else {
        admitted
            .admitTerminal(
                TerminalEvidence(time, 2L, rows, root, final, complete = complete, partial = !safe),
            ).authority
    }
}
