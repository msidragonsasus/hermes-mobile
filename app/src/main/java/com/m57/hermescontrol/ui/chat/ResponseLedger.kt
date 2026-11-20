package com.m57.hermescontrol.ui.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

/** Durable identity is independent of presentation groups and socket/RPC identities. */
@Serializable
data class ResponseRecord(
    val id: String = UUID.randomUUID().toString(),
    val messageIds: List<String> = emptyList(),
    val userIds: List<String> = emptyList(),
    val submissionIds: List<String> = emptyList(),
    val userRowIds: List<Long> = emptyList(),
    val canonicalRowIds: List<Long> = emptyList(),
    val canonicalComplete: Boolean = false,
    val canonicalFinalAssistantRowId: Long? = null,
    val canonicalWindowAllowed: Boolean = false,
    val canonicalWindowVerified: Boolean = false,
    val finishedAt: Long? = null,
    val coverage: Boolean = true,
    val authority: ReplyAuthorityFacts = ReplyAuthorityFacts(),
)

@Serializable
data class ApprovalPlacement(
    val responseId: String,
    val afterToolCallId: String? = null,
)

data class ApprovalHistoryAnswer(
    val responseId: String,
    val messageId: String,
)

@Serializable
data class ResponseLedger(
    val responses: List<ResponseRecord> = emptyList(),
    val activeId: String? = null,
    /** More than one response has existed: uncorrelated terminals are ambiguous forever. */
    val requiresCorrelation: Boolean = false,
    val aliases: Map<String, String> = emptyMap(),
    val canonicalRoles: Map<Long, String> = emptyMap(),
    /** Server `message_uid` per observed row; a rewritten row keeps its uid under a new row id. */
    val canonicalUids: Map<Long, String> = emptyMap(),
    val canonicalVisibleRows: List<Long> = emptyList(),
    val pendingInputs: Map<String, String> = emptyMap(),
    val pendingSubmissions: Map<String, String> = emptyMap(),
    val possibleContinuations: Map<String, String> = emptyMap(),
    val unboundControls: List<ControlInputEvidence> = emptyList(),
    /** REST transcript links used only to present recovered tools alongside their answer. */
    val toolAnswerIds: Map<String, String> = emptyMap(),
    /** Approval rows are local activity and do not establish transcript or reply authority. */
    val approvalPlacements: Map<String, ApprovalPlacement> = emptyMap(),
    /** REST may observe the same final answer before the live terminal is delivered. */
    @Transient val approvalHistoryAnswer: ApprovalHistoryAnswer? = null,
) {
    val activeHistoryAnswerId: String?
        get() = approvalHistoryAnswer?.takeIf { it.responseId == activeId }?.messageId

    fun open(): ResponseLedger {
        val response = ResponseRecord()
        return copy(
            responses = responses + response,
            activeId = response.id,
            requiresCorrelation = requiresCorrelation || responses.isNotEmpty(),
            approvalHistoryAnswer = null,
        )
    }

    fun update(
        id: String?,
        transform: (ResponseRecord) -> ResponseRecord,
    ): ResponseLedger = copy(responses = responses.map { if (it.id == id) transform(it) else it })

    /** Only a definitely-unsent, untouched provisional is not real response history. */
    fun retireUnusedProvisional(id: String?): ResponseLedger {
        val unused = responses.singleOrNull { it.id == id } ?: return this
        if (unused != ResponseRecord(id = unused.id) ||
            id in pendingSubmissions.values || id in pendingInputs.values || id in possibleContinuations.values
        ) {
            return this
        }
        // Do not reset the monotonic history fence or delete any displayed/cached body.
        return copy(
            responses = responses.filterNot { it.id == id },
            activeId = if (activeId == id) null else activeId,
        )
    }

    fun observe(ids: List<String>): ResponseLedger =
        update(activeId) { it.copy(messageIds = (it.messageIds + ids).distinct()) }

    fun gap(): ResponseLedger =
        update(activeId) {
            if (it.finishedAt ==
                null
            ) {
                it.copy(coverage = false, authority = it.authority.copy(deliveryGap = true, local = null))
            } else {
                it
            }
        }
}
