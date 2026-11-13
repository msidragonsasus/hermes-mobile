package com.m57.hermescontrol.data.ws

/** Optional current-lifetime correlation. Never an ACK, idempotency key or restart lookup. */
data class TerminalAcceptance(
    val originalRequestId: String,
    val clientAttemptNonce: String,
    val peerId: String,
    val runtimeSessionId: String,
    val targetSessionId: String,
    val transcriptSessionId: String,
    val originUserRowId: Long,
) {
    companion object {
        fun parse(value: Any?): TerminalAcceptance? {
            val fields = value as? Map<*, *> ?: return null
            if (fields["version"] != 1 || fields["method"] != WsMethods.PROMPT_SUBMIT ||
                fields["disposition"] != "streaming"
            ) {
                return null
            }

            fun id(key: String) = (fields[key] as? String)?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
            return TerminalAcceptance(
                id("original_rpc_request_id") ?: return null,
                (fields["client_attempt_nonce"] as? String)?.takeIf(::isTerminalUuid) ?: return null,
                (fields["peer_id"] as? String)?.takeIf(::isTerminalUuid) ?: return null,
                id("runtime_session_id") ?: return null,
                id("target_session_id") ?: return null,
                id("transcript_session_id") ?: return null,
                positiveRowId(fields["origin_user_row_id"]) ?: return null,
            )
        }
    }
}

internal fun isTerminalUuid(value: String): Boolean =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}").matches(value)

/** Retains the ORIGINAL full observation until the typed consumer has admitted it, even under backpressure. */
class TerminalAcceptanceProof internal constructor(
    val observation: ScopedWsEvent,
    val receipt: TerminalAcceptance,
    val authenticationEpoch: Long,
    internal val isCurrent: () -> Boolean,
)

internal fun WsEvent.MessageComplete.hasSafeTerminalAcceptance(receipt: TerminalAcceptance): Boolean {
    val manifest = persistedTurn ?: return false
    return sessionId == receipt.runtimeSessionId && text.isNotBlank() &&
        com.m57.hermescontrol.ui.chat.HostMediaExtractor
            .extract(text)
            .isEmpty() &&
        status == "complete" && partial == false && responseReused == false && responseTransformed == false &&
        responsePreviewed != null && failureReason == null &&
        manifest.complete && manifest.userRowId == receipt.originUserRowId &&
        manifest.userRowIds == listOf(receipt.originUserRowId) &&
        manifest.finalAssistantRowId != null && manifest.finalAssistantRowId != receipt.originUserRowId &&
        manifest.rowIds.firstOrNull() == receipt.originUserRowId &&
        manifest.rowIds.lastOrNull() == manifest.finalAssistantRowId &&
        receipt.originUserRowId in manifest.rowIds && manifest.finalAssistantRowId in manifest.rowIds
}
