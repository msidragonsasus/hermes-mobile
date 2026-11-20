package com.m57.hermescontrol.ui.chat

import kotlinx.serialization.Serializable

/** Admission facts are produced by guarded callbacks, never by rendered rows. */
@Serializable
data class OpeningEvidence(
    val attemptUid: String,
    val requestId: String,
    val disposition: String,
    val target: String,
    val session: String,
    val observation: Long,
    val userRowId: Long? = null,
    val submissionId: String? = null,
    val connectionGeneration: Int? = null,
    val rowBindingSource: String? = null,
    val observationProcessId: String? = null,
)

@Serializable
data class ControlInputEvidence(
    val attemptUid: String,
    val requestId: String,
    val disposition: String,
    val userRowId: Long? = null,
    val submissionId: String? = null,
)

@Serializable
data class TerminalEvidence(
    val observedAt: Long,
    val observation: Long,
    val rowIds: List<Long> = emptyList(),
    val userRowId: Long? = null,
    val finalRowId: Long? = null,
    val submissionId: String? = null,
    val complete: Boolean? = null,
    val partial: Boolean? = null,
    val transformed: Boolean? = null,
    val status: String? = null,
    val connectionGeneration: Int? = null,
) {
    val safe: Boolean get() = partial != true && transformed != true && (status == null || status == "complete")
}

@Serializable
data class CoverageEvidence(
    val source: String,
    val memberIds: List<String> = emptyList(),
    val rowIds: List<Long> = emptyList(),
)

@Serializable
data class AuthorityRevocation(
    val reason: String,
    val references: List<String> = emptyList(),
)

@Serializable
data class ReplyAuthorityFacts(
    val opening: OpeningEvidence? = null,
    val openingObservations: List<OpeningEvidence> = emptyList(),
    val controls: List<ControlInputEvidence> = emptyList(),
    val queued: OpeningEvidence? = null,
    val terminals: List<TerminalEvidence> = emptyList(),
    val local: CoverageEvidence? = null,
    val canonical: CoverageEvidence? = null,
    val deliveryGap: Boolean = false,
    val canonicalSuspended: Boolean = false,
    val revocations: List<AuthorityRevocation> = emptyList(),
) {
    fun revoke(
        reason: String,
        vararg refs: String,
    ): ReplyAuthorityFacts =
        copy(
            revocations = (revocations + AuthorityRevocation(reason, refs.toList())).distinct(),
        )
}

enum class ReplyAuthorityDecision {
    Unproven,
    Collecting,
    TerminalIneligible,
    AwaitingCoverage,
    ReadyLocal,
    ReadyCanonical,
    Quarantined,
    ;

    val copyReady: Boolean get() = this == ReadyLocal || this == ReadyCanonical
}

/** The only permission predicate for footer AND atomic canonical replacement. */
fun ResponseRecord.authorityDecision(
    materializedIds: Set<String>,
    blocked: Boolean = false,
): ReplyAuthorityDecision {
    val facts = authority
    return when {
        facts.revocations.isNotEmpty() -> ReplyAuthorityDecision.Quarantined

        facts.opening == null -> ReplyAuthorityDecision.Unproven

        facts.terminals.isEmpty() -> ReplyAuthorityDecision.Collecting

        facts.terminals.any { !it.safe } -> ReplyAuthorityDecision.TerminalIneligible

        blocked -> ReplyAuthorityDecision.AwaitingCoverage

        !facts.canonicalSuspended &&
            facts.canonical?.let {
                it.memberIds.isNotEmpty() && materializedIds.containsAll(it.memberIds)
            } == true -> ReplyAuthorityDecision.ReadyCanonical

        !facts.deliveryGap &&
            facts.local?.let {
                it.memberIds == messageIds && materializedIds.containsAll(it.memberIds)
            } == true -> ReplyAuthorityDecision.ReadyLocal

        else -> ReplyAuthorityDecision.AwaitingCoverage
    }
}

fun ResponseLedger.blocksAuthority(id: String): Boolean =
    id in pendingSubmissions.values || id in pendingInputs.values || id in possibleContinuations.values

/** An exhaustive manifest must include every already accepted exact input claim, in either ACK/terminal order. */
private fun ReplyAuthorityFacts.validateExhaustiveness(): ReplyAuthorityFacts {
    var facts = this
    val inputs = listOfNotNull(opening?.userRowId) + controls.mapNotNull { it.userRowId }
    for (terminal in terminals.filter { it.complete == true }) {
        val missing = inputs.filterNot { it in terminal.rowIds }
        if (missing.isNotEmpty()) {
            facts =
                facts.revoke("complete-exhaustiveness", missing.distinct().sorted().toString())
        }
    }
    return facts
}

/** Compare antecedents before appending an observation. Wire complete is never rewritten. */
fun ResponseRecord.admitTerminal(evidence: TerminalEvidence): ResponseRecord {
    var facts = authority
    val queued = facts.queued
    if (facts.opening == null && queued?.submissionId != null && queued.submissionId == evidence.submissionId) {
        val execution = queued.copy(disposition = "QUEUED_EXECUTION", userRowId = null, rowBindingSource = null)
        facts =
            facts.copy(
                opening = execution,
                openingObservations = (facts.openingObservations + execution).distinct(),
                deliveryGap = true,
            )
    }
    val previous = facts.terminals
    for (old in previous) {
        val sharedOld = old.rowIds.filter { it in evidence.rowIds }
        val sharedIncoming = evidence.rowIds.filter { it in old.rowIds }
        if (sharedOld != sharedIncoming) {
            facts = facts.revoke("manifest-order", old.rowIds.toString(), evidence.rowIds.toString())
        }
        if (old.finalRowId != null && evidence.finalRowId != null && old.finalRowId != evidence.finalRowId) {
            facts = facts.revoke("terminal-final", old.finalRowId.toString(), evidence.finalRowId.toString())
        }
        if ((old.status != null && evidence.status != null && old.status != evidence.status) ||
            (old.partial != null && evidence.partial != null && old.partial != evidence.partial) ||
            (old.transformed != null && evidence.transformed != null && old.transformed != evidence.transformed)
        ) {
            facts = facts.revoke("terminal-outcome")
        }
        if (old.complete == true && evidence.complete == true && old.rowIds != evidence.rowIds) {
            facts = facts.revoke("complete-manifest", old.rowIds.toString(), evidence.rowIds.toString())
        }
        val exhaustive =
            if (evidence.complete ==
                true
            ) {
                evidence.rowIds
            } else if (old.complete == true) {
                old.rowIds
            } else {
                null
            }
        if (exhaustive != null) {
            val partial = if (evidence.complete == true) old.rowIds else evidence.rowIds
            if (!exhaustive.containsAll(partial) || exhaustive.filter { it in partial } != partial) {
                facts = facts.revoke("manifest-order", old.rowIds.toString(), evidence.rowIds.toString())
            }
        }
    }
    facts = facts.copy(terminals = (previous + evidence).distinct())
    if (facts.opening != null && !facts.deliveryGap && facts.local == null) {
        facts = facts.copy(local = CoverageEvidence("local-delivery", messageIds))
    }
    return copy(
        authority = facts.validateExhaustiveness(),
        finishedAt =
            previous.firstOrNull()?.observedAt ?: evidence.observedAt,
    )
}

/** Compare the union before either owner may replace a body or expose a footer. */
fun ResponseLedger.revokeExclusiveClaims(): ResponseLedger {
    val claims =
        responses.filter { it.authority.opening != null }.associate { r ->
            r.id to
                (
                    listOfNotNull(r.authority.opening?.userRowId) + r.authority.controls.mapNotNull { it.userRowId } +
                        r.authority.openingObservations.mapNotNull { it.userRowId } +
                        r.authority.terminals.flatMap { it.rowIds } +
                        r.authority.canonical
                            ?.rowIds
                            .orEmpty()
                ).toSet()
        }
    val disputed =
        claims.values
            .flatten()
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    return copy(
        responses =
            responses.map { r ->
                val overlap = claims[r.id].orEmpty().intersect(disputed)
                if (overlap.isEmpty()) {
                    r
                } else {
                    r.copy(
                        authority = r.authority.revoke("exclusive-row", overlap.sorted().toString()),
                    )
                }
            },
    )
}

/** A2 acceptance, shared by every submitPrompt entry path including no USER bubble. */
fun ResponseLedger.acceptInput(
    attemptUid: String,
    provisionalId: String?,
    continuationId: String?,
    ordinary: Boolean,
    requestId: String,
    disposition: String,
    target: String,
    session: String,
    observation: Long,
    userId: String?,
    rowId: Long?,
    submissionId: String?,
    connectionGeneration: Int? = null,
    observationProcessId: String? = null,
): ResponseLedger =
    acceptInput(
        attemptUid,
        provisionalId,
        continuationId,
        ordinary,
        requestId,
        disposition,
        target,
        session,
        observation,
        userId,
        rowId,
        submissionId,
        connectionGeneration,
        observationProcessId,
        com.m57.hermescontrol.domain.hermes.SubmissionProofSource.RPC_ACK,
    )

fun ResponseLedger.acceptInput(
    attemptUid: String,
    provisionalId: String?,
    continuationId: String?,
    ordinary: Boolean,
    requestId: String,
    disposition: String,
    target: String,
    session: String,
    observation: Long,
    userId: String?,
    rowId: Long?,
    submissionId: String?,
    connectionGeneration: Int?,
    observationProcessId: String?,
    proofSource: com.m57.hermescontrol.domain.hermes.SubmissionProofSource,
): ResponseLedger {
    var ledger = this
    var p = ledger.responses.singleOrNull { it.authority.queued?.attemptUid == attemptUid }?.id ?: provisionalId
    if (disposition == "QUEUED" && (p == null || p == continuationId)) {
        val fresh = ResponseRecord()
        ledger = ledger.copy(responses = ledger.responses + fresh, requiresCorrelation = true)
        p = fresh.id
    }
    val row = rowId?.takeIf { it > 0 }
    val submission = submissionId?.takeIf { it.isNotBlank() }
    val evidence =
        OpeningEvidence(
            attemptUid,
            requestId,
            disposition,
            target,
            session,
            observation,
            row,
            submission,
            connectionGeneration,
            if (row ==
                null
            ) {
                null
            } else if (disposition == "QUEUED") {
                "queued-candidate"
            } else if (proofSource == com.m57.hermescontrol.domain.hermes.SubmissionProofSource.TERMINAL_RECEIPT) {
                "terminal-receipt"
            } else {
                "guarded-ack"
            },
            observationProcessId,
        )
    val owner =
        when (disposition) {
            "STEERED", "REDIRECTED" -> continuationId
            else -> p
        }
    ledger =
        ledger.update(owner) { record ->
            var facts = record.authority
            when (disposition) {
                "STREAMING" -> {
                    if ((ordinary || record.serverFollowUp) && p != null) {
                        facts = facts.copy(openingObservations = (facts.openingObservations + evidence).distinct())
                        val old = facts.opening
                        facts =
                            when {
                                old == null -> {
                                    facts.copy(opening = evidence)
                                }

                                old.attemptUid != attemptUid ||
                                    (old.userRowId != null && row != null && old.userRowId != row) ||
                                    (
                                        old.submissionId != null && submission != null &&
                                            old.submissionId != submission
                                    ) -> {
                                    facts.revoke("opening-binding", old.attemptUid, attemptUid)
                                }

                                else -> {
                                    facts.copy(
                                        opening =
                                            old.copy(
                                                userRowId = old.userRowId ?: row,
                                                submissionId =
                                                    old.submissionId ?: submission,
                                                rowBindingSource =
                                                    old.rowBindingSource ?: evidence.rowBindingSource,
                                            ),
                                    )
                                }
                            }
                    }
                }

                "STEERED", "REDIRECTED" -> {
                    facts =
                        facts.copy(
                            controls =
                                (
                                    facts.controls +
                                        ControlInputEvidence(attemptUid, requestId, disposition, row, submission)
                                ).distinct(),
                        )
                }

                "QUEUED" -> {
                    facts = facts.copy(queued = evidence)
                }
            }
            record.copy(
                authority = facts.validateExhaustiveness(),
                userIds = (record.userIds + listOfNotNull(userId)).distinct(),
                userRowIds = (record.userRowIds + listOfNotNull(row)).distinct(),
                submissionIds = (record.submissionIds + listOfNotNull(submission)).distinct(),
            )
        }
    if (disposition == "STREAMING" && owner != null && owner != ledger.activeId &&
        ledger.responses.any { it.id == owner && it.serverFollowUp && it.finishedAt == null }
    ) {
        val activeFinishedOrAbsent =
            ledger.activeId == null ||
                ledger.responses.any { it.id == ledger.activeId && it.finishedAt != null }
        if (activeFinishedOrAbsent) {
            ledger = ledger.copy(activeId = owner)
        }
    }
    if (disposition in listOf("STEERED", "REDIRECTED") && owner != null && p != null && p != owner) {
        val unused =
            ledger.responses.singleOrNull {
                it.id == p && it.authority.opening == null &&
                    it.authority.terminals.isEmpty()
            }
        if (unused != null) {
            if (unused.messageIds.isNotEmpty()) {
                ledger =
                    ledger.update(owner) { it.copy(authority = it.authority.copy(deliveryGap = true, local = null)) }
            }
            ledger = ledger.update(p) { it.copy(messageIds = emptyList(), userIds = emptyList()) }
            if (ledger.activeId == p) ledger = ledger.copy(activeId = owner)
        }
    }
    return ledger
        .copy(
            requiresCorrelation = ledger.requiresCorrelation || disposition == "QUEUED",
            unboundControls =
                if (owner == null && disposition in listOf("STEERED", "REDIRECTED")) {
                    (
                        ledger.unboundControls +
                            ControlInputEvidence(
                                attemptUid,
                                requestId,
                                disposition,
                                row,
                                submission,
                            )
                    ).distinct()
                } else {
                    ledger.unboundControls
                },
            pendingSubmissions = ledger.pendingSubmissions - attemptUid,
            pendingInputs = ledger.pendingInputs - listOfNotNull(userId).toSet(),
            possibleContinuations = ledger.possibleContinuations - attemptUid,
            aliases =
                if (row != null &&
                    userId != null
                ) {
                    ledger.aliases + (row.toString() to userId)
                } else {
                    ledger.aliases
                },
        ).revokeExclusiveClaims()
}
