package com.m57.hermescontrol.ui.chat

/** Materialization only: the caller must first prove scoped preview/terminal identity. */
internal data class PreviewedFinalFold(
    val messages: List<ChatMessage>,
    val stagedMessages: List<ChatMessage>,
    val ledger: ResponseLedger,
    val finalizedMessage: ChatMessage,
)

/**
 * Retire only the already-proven exact canonical address, never an equal-text neighbour.
 * Unmergeable metadata or disputed versions keep both observations rather than lose data.
 */
internal fun foldPreviewedFinalCollision(
    messages: List<ChatMessage>,
    stagedMessages: List<ChatMessage>,
    ledger: ResponseLedger,
    candidateId: String,
    canonicalId: String,
): PreviewedFinalFold? {
    if (candidateId == canonicalId) return null
    val all = messages + stagedMessages
    val candidate = messages.singleOrNull { it.id == candidateId } ?: return null
    if (all.count { it.id == candidateId } != 1) return null
    val canonical = all.singleOrNull { it.id == canonicalId } ?: return null
    if (all.any { it.id.startsWith("disputed:$canonicalId:") }) return null
    // A canonical row already used as a response member/alias is not an unowned REST duplicate.
    // Refuse instead of deleting or silently rewriting independent coverage references.
    if (ledger.aliases.values.any { it == canonicalId } ||
        ledger.responses.any {
            canonicalId in it.messageIds || canonicalId in it.userIds ||
                canonicalId in
                it.authority.local
                    ?.memberIds
                    .orEmpty() ||
                canonicalId in
                it.authority.canonical
                    ?.memberIds
                    .orEmpty()
        }
    ) {
        return null
    }
    if (candidate.role != MessageRole.ASSISTANT || canonical.role != MessageRole.ASSISTANT ||
        candidate.isStreaming || canonical.isStreaming
    ) {
        return null
    }

    fun observedBody(message: ChatMessage): String? {
        val source = message.sourceBody ?: return message.content
        return source.takeIf { it == message.content || HostMediaExtractor.strip(it) == message.content }
    }
    val observed = observedBody(candidate) ?: return null
    if (observedBody(canonical) != observed) return null
    // Equal source does not make raw/stripped display interchangeable: importing REST cards
    // can make the first-terminal media effect return before it normalizes the raw body.
    if (candidate.content != canonical.content) return null
    if (candidate.hasServerDisplayProjection != canonical.hasServerDisplayProjection) return null
    // A projected row's intentionally empty fields are authoritative, not merge holes.
    if (canonical.hasServerDisplayProjection &&
        (
            candidate.sourceBody != canonical.sourceBody ||
                candidate.reasoningText != canonical.reasoningText || candidate.attachments != canonical.attachments
        )
    ) {
        return null
    }
    if (candidate.attachments != canonical.attachments &&
        (candidate.attachments.orEmpty() + canonical.attachments.orEmpty()).any {
            it.gatewayUrl != null || it.source == com.m57.hermescontrol.data.model.AttachmentSource.GATEWAY
        }
    ) {
        return null
    }
    if (candidate.reasoningText.isNotEmpty() && canonical.reasoningText.isNotEmpty() &&
        candidate.reasoningText != canonical.reasoningText
    ) {
        return null
    }
    if (!candidate.attachments.isNullOrEmpty() && !canonical.attachments.isNullOrEmpty() &&
        candidate.attachments != canonical.attachments
    ) {
        return null
    }
    if (canonical.finishTimestamp != null && canonical.finishTimestamp != candidate.finishTimestamp) return null
    // Compare every other current payload/display field. New independent metadata must not
    // disappear merely because a future ChatMessage field is added (data-class equality).
    if (canonical.copy(
            id = candidate.id,
            content = candidate.content,
            sourceBody = candidate.sourceBody,
            reasoningText = candidate.reasoningText,
            attachments = candidate.attachments,
            timestamp = candidate.timestamp,
            finishTimestamp = candidate.finishTimestamp,
        ) != candidate
    ) {
        return null
    }
    val merged =
        candidate.copy(
            sourceBody = candidate.sourceBody ?: canonical.sourceBody,
            reasoningText = candidate.reasoningText.ifEmpty { canonical.reasoningText },
            attachments =
                candidate.attachments?.takeIf { it.isNotEmpty() } ?: canonical.attachments ?: candidate.attachments,
        )
    // Keep the UUID and its original list position; do not replace raw content with stripped
    // REST display text: first-terminal AttachHostMedia still reads that raw content.
    return PreviewedFinalFold(
        messages = messages.filterNot { it.id == canonicalId }.map { if (it.id == candidateId) merged else it },
        stagedMessages = stagedMessages.filterNot { it.id == canonicalId },
        ledger = ledger,
        finalizedMessage = merged,
    )
}
