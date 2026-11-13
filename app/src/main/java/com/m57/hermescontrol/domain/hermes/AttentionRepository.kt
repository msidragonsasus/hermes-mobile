package com.m57.hermescontrol.domain.hermes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AttentionKind { APPROVAL, CLARIFICATION }

enum class AttentionStatus { PENDING, SUBMITTING, CONFIRMED, EXPIRED, UNKNOWN }

data class ClarificationLock(
    val answer: String?,
)

/** Transient observations. Replaying a request never grants permission to resend a response. */
data class AttentionItem(
    val ref: RequestRef,
    val kind: AttentionKind,
    val title: String,
    val expiresAt: Long? = null,
    val status: AttentionStatus = AttentionStatus.PENDING,
    val details: AttentionDetails? = null,
    val clarificationLock: ClarificationLock? = null,
)

/** Exact identity includes server, both profiles, socket generation, session and question. */
class AttentionRepository {
    private val mutableItems = MutableStateFlow<List<AttentionItem>>(emptyList())
    val items = mutableItems.asStateFlow()
    private val responseAttempts = mutableSetOf<RequestRef>()
    private val observedAt = mutableMapOf<RequestRef, Long>()

    private data class OuterKey(
        val scope: ConnectionScope,
        val runtimeId: String,
        val outer: String,
    )

    private val tombstones = mutableSetOf<OuterKey>()
    private val resolvedInFlight = mutableSetOf<RequestRef>()
    private val aliases = mutableMapOf<RequestRef, RequestRef>()

    private fun key(
        scope: ConnectionScope,
        runtimeId: String,
        outer: String,
    ) = OuterKey(scope.copy(generation = 0), runtimeId, outer)

    private fun key(ref: RequestRef): OuterKey? = ref.serverRequestId?.let { key(ref.scope, ref.session.runtimeId, it) }

    private fun coalesces(
        a: RequestRef,
        b: RequestRef,
    ): Boolean =
        a.scope.copy(generation = b.scope.generation) == b.scope &&
            a.session.runtimeId == b.session.runtimeId && a.requestId == b.requestId &&
            a.questionId == b.questionId &&
            (
                a.serverRequestId == b.serverRequestId ||
                    (a.questionId == null && (a.serverRequestId == null || b.serverRequestId == null))
            )

    @Synchronized
    fun apply(signal: AttentionSignal) {
        when (signal) {
            is AttentionSignal.Gap -> {
                markScopeUnknown(signal.scope, signal.runtimeId)
            }

            is AttentionSignal.Requested -> {
                signal.items.forEach { item ->
                    val admitted = observe(item)
                    observedAt[admitted.ref] = maxOf(observedAt[admitted.ref] ?: 0, signal.ordinal)
                }
            }

            is AttentionSignal.Expired -> {
                settleClarification(signal.scope, signal.runtimeId, signal.requestId, resolved = false)
            }

            is AttentionSignal.Resolved -> {
                settleClarification(signal.scope, signal.runtimeId, signal.requestId, resolved = true)
            }

            is AttentionSignal.Cancelled -> {
                val outerKey = key(signal.scope, signal.runtimeId, signal.serverRequestId)
                val targets = mutableItems.value.filter { key(it.ref) == outerKey }
                val expectedKind =
                    when (signal.method) {
                        "approval" -> AttentionKind.APPROVAL
                        "clarify" -> AttentionKind.CLARIFICATION
                        null -> null
                        else -> return
                    }
                if (expectedKind != null && targets.any { it.kind != expectedKind }) return
                tombstones.add(outerKey)
                targets.forEach { item ->
                    if (signal.reason == "resolved" && item.ref in responseAttempts &&
                        (item.status == AttentionStatus.SUBMITTING || item.ref in resolvedInFlight)
                    ) {
                        resolvedInFlight.add(item.ref)
                    } else {
                        resolvedInFlight.remove(item.ref)
                    }
                    expire(item.ref)
                }
            }

            is AttentionSignal.Snapshot -> {
                signal.items.forEach { observe(it) }
                mutableItems.value
                    .filter { item ->
                        item.ref.scope.copy(generation = signal.scope.generation) == signal.scope &&
                            item.ref.session.runtimeId == signal.runtimeId &&
                            item.ref.serverRequestId != null && item.ref.serverRequestId !in signal.serverRequestIds &&
                            (observedAt[item.ref] ?: 0) <= signal.startedAt
                    }.forEach { item ->
                        key(item.ref)?.let(tombstones::add)
                        expire(item.ref)
                    }
            }
        }
    }

    fun actionable(
        ref: RequestRef,
        scope: ConnectionScope?,
    ): Boolean =
        ref.scope == scope &&
            mutableItems.value.any {
                it.ref == ref && it.status == AttentionStatus.PENDING && it.clarificationLock == null &&
                    !wasAttempted(ref)
            }

    private fun sameRequest(
        a: RequestRef,
        b: RequestRef,
    ): Boolean =
        a.scope.copy(generation = b.scope.generation) == b.scope &&
            a.session.runtimeId == b.session.runtimeId && a.requestId == b.requestId && a.questionId == b.questionId &&
            a.serverRequestId == b.serverRequestId

    private fun wasAttempted(ref: RequestRef): Boolean = responseAttempts.any { coalesces(it, ref) }

    @Synchronized
    fun observe(item: AttentionItem): AttentionItem {
        require(item.ref.requestId.isNotBlank())
        require(
            item.ref.session.runtimeId
                .isNotBlank(),
        )
        val coalesced =
            mutableItems.value.firstOrNull {
                it.kind == AttentionKind.APPROVAL && item.kind == AttentionKind.APPROVAL &&
                    it.ref.scope == item.ref.scope && coalesces(it.ref, item.ref)
            }
        if (coalesced != null && coalesced.ref != item.ref) {
            if (item.ref.serverRequestId == null) return coalesced
            aliases[coalesced.ref] = item.ref
            if (coalesced.ref in responseAttempts) responseAttempts.add(item.ref)
            if (coalesced.ref in resolvedInFlight) resolvedInFlight.add(item.ref)
            observedAt[item.ref] = observedAt[coalesced.ref] ?: 0
            mutableItems.value =
                mutableItems.value.map {
                    if (it ===
                        coalesced
                    ) {
                        it.copy(ref = item.ref, details = item.details ?: it.details)
                    } else {
                        it
                    }
                }
        }
        val retainedLock =
            mutableItems.value.firstNotNullOfOrNull {
                if (it.kind == item.kind && sameRequest(it.ref, item.ref)) it.clarificationLock else null
            } ?: item.clarificationLock
        val existing = mutableItems.value.firstOrNull { it.ref == item.ref }
        if (existing != null) {
            // A fresh authoritative request can restore an unanswered observation after a gap.
            // A response whose outcome is unknown must never become sendable through replay.
            val refreshed =
                existing.copy(
                    details =
                        if (retainedLock != null) {
                            (existing.details ?: item.details)?.copy(lockedAnswer = retainedLock.answer)
                        } else if (item.status == AttentionStatus.CONFIRMED) {
                            item.details ?: existing.details
                        } else {
                            existing.details ?: item.details
                        },
                    clarificationLock = retainedLock,
                    status =
                        if (retainedLock != null || item.status == AttentionStatus.CONFIRMED) {
                            AttentionStatus.CONFIRMED
                        } else if (key(item.ref) in tombstones) {
                            if (existing.status ==
                                AttentionStatus.CONFIRMED
                            ) {
                                existing.status
                            } else {
                                AttentionStatus.EXPIRED
                            }
                        } else if (existing.status == AttentionStatus.UNKNOWN && !wasAttempted(existing.ref) &&
                            mutableItems.value.none {
                                sameRequest(it.ref, existing.ref) &&
                                    it.status in setOf(AttentionStatus.CONFIRMED, AttentionStatus.EXPIRED)
                            }
                        ) {
                            AttentionStatus.PENDING
                        } else {
                            existing.status
                        },
                )
            replace(item.ref) { refreshed }
            return refreshed
        }
        val previousAttempt =
            mutableItems.value.firstOrNull {
                it.kind == item.kind && coalesces(it.ref, item.ref) &&
                    (
                        it.ref in responseAttempts ||
                            it.status in setOf(AttentionStatus.CONFIRMED, AttentionStatus.EXPIRED)
                    )
            }
        val inheritedStatus =
            previousAttempt?.status?.let {
                if (it == AttentionStatus.SUBMITTING) AttentionStatus.UNKNOWN else it
            }
        val pending =
            item.copy(
                clarificationLock = retainedLock,
                details =
                    if (retainedLock != null) {
                        item.details?.copy(lockedAnswer = retainedLock.answer)
                    } else {
                        item.details
                    },
                status =
                    when {
                        retainedLock != null || item.status == AttentionStatus.CONFIRMED -> AttentionStatus.CONFIRMED
                        key(item.ref) in tombstones -> AttentionStatus.EXPIRED
                        else -> inheritedStatus ?: item.status
                    },
            )
        mutableItems.value = mutableItems.value + pending
        return pending
    }

    @Synchronized
    fun expire(ref: RequestRef) {
        replace(ref) { item ->
            if (item.status == AttentionStatus.CONFIRMED) item else item.copy(status = AttentionStatus.EXPIRED)
        }
    }

    @Synchronized
    fun settleClarification(
        scope: ConnectionScope,
        runtimeId: String,
        requestId: String,
        resolved: Boolean,
    ) {
        mutableItems.value =
            mutableItems.value.map { item ->
                val ref = item.ref
                // Modern request.cancel is withdrawal, not an answer ACK. Its exact
                // in-flight attempt is settled through Cancelled/ACK handling only.
                if (item.kind == AttentionKind.CLARIFICATION && ref.serverRequestId == null &&
                    ref.scope == scope && ref.session.runtimeId == runtimeId && ref.requestId == requestId &&
                    item.status != AttentionStatus.CONFIRMED
                ) {
                    item.copy(status = if (resolved) AttentionStatus.CONFIRMED else AttentionStatus.EXPIRED)
                } else {
                    item
                }
            }
    }

    @Synchronized
    fun markScopeUnknown(
        scope: ConnectionScope,
        runtimeId: String? = null,
    ) {
        mutableItems.value =
            mutableItems.value.map { item ->
                if (item.ref.scope == scope &&
                    (runtimeId == null || item.ref.session.runtimeId == runtimeId) &&
                    item.status in setOf(AttentionStatus.PENDING, AttentionStatus.SUBMITTING)
                ) {
                    item.copy(status = AttentionStatus.UNKNOWN)
                } else {
                    item
                }
            }
    }

    @Synchronized
    private fun begin(
        ref: RequestRef,
        currentScope: ConnectionScope?,
        ready: Boolean,
        now: Long,
    ): Boolean {
        val item = mutableItems.value.firstOrNull { it.ref == ref } ?: return false
        if (item.status != AttentionStatus.PENDING || wasAttempted(ref)) return false
        if (item.expiresAt?.let { it <= now } == true) {
            expire(ref)
            return false
        }
        if (!ready || currentScope != ref.scope) return false
        responseAttempts.add(ref)
        replace(ref) { it.copy(status = AttentionStatus.SUBMITTING) }
        return true
    }

    /** Shared modern/legacy routing; a typed expiry is not a successful answer. */
    suspend fun respondClarification(
        ref: RequestRef,
        answer: String?,
        currentScope: ConnectionScope?,
        ready: Boolean,
        now: Long,
        transport: com.m57.hermescontrol.data.transport.HermesAttentionTransport,
    ) {
        var locked = false
        respond(ref, currentScope, ready, now) {
            if (ref.serverRequestId == null || ref.questionId == null) {
                transport.respondClarification(ref, answer)
            } else {
                val qids =
                    items.value
                        .filter { key(it.ref) == key(ref) }
                        .mapNotNull { it.ref.questionId }
                        .toSet()
                when (val result = transport.lockClarification(ref, answer, qids)) {
                    is SubmissionResult.Confirmed -> {
                        when (result.value) {
                            is com.m57.hermescontrol.data.transport.ClarificationResult.Locked -> {
                                locked = true
                                SubmissionResult.Confirmed(result.requestId, true)
                            }

                            com.m57.hermescontrol.data.transport.ClarificationResult.Expired -> {
                                expireOuter(ref)
                                SubmissionResult.Unknown
                            }
                        }
                    }

                    SubmissionResult.NotSent -> {
                        SubmissionResult.NotSent
                    }

                    SubmissionResult.Expired -> {
                        expireOuter(ref)
                        SubmissionResult.Expired
                    }

                    SubmissionResult.Unknown -> {
                        SubmissionResult.Unknown
                    }
                }
            }
        }
        if (locked) recordLock(ref, answer)
    }

    @Synchronized
    private fun recordLock(
        ref: RequestRef,
        answer: String?,
    ) {
        replace(ref) { item ->
            if (item.status == AttentionStatus.CONFIRMED && item.clarificationLock == null) {
                item.copy(
                    clarificationLock = ClarificationLock(answer),
                    details = item.details?.copy(lockedAnswer = answer),
                )
            } else {
                item
            }
        }
    }

    @Synchronized
    private fun expireOuter(ref: RequestRef) {
        key(ref)?.let(tombstones::add)
        mutableItems.value.filter { key(it.ref) == key(ref) }.forEach {
            resolvedInFlight.remove(it.ref)
            expire(it.ref)
        }
    }

    /** NotSent permits another explicit tap. Accepted-but-unconfirmed never does. */
    suspend fun respond(
        ref: RequestRef,
        currentScope: ConnectionScope?,
        ready: Boolean,
        now: Long,
        send: suspend () -> SubmissionResult<Boolean>,
    ) {
        if (!begin(ref, currentScope, ready, now)) return
        var notSent = false
        val status =
            try {
                when (val result = send()) {
                    is SubmissionResult.Confirmed -> {
                        if (result.value) AttentionStatus.CONFIRMED else AttentionStatus.UNKNOWN
                    }

                    SubmissionResult.NotSent -> {
                        notSent = true
                        AttentionStatus.PENDING
                    }

                    SubmissionResult.Expired -> {
                        settleClarification(ref.scope, ref.session.runtimeId, ref.requestId, resolved = false)
                        AttentionStatus.EXPIRED
                    }

                    SubmissionResult.Unknown -> {
                        AttentionStatus.UNKNOWN
                    }
                }
            } catch (e: CancellationException) {
                finish(ref, AttentionStatus.UNKNOWN)
                throw e
            } catch (_: Exception) {
                AttentionStatus.UNKNOWN
            }
        finish(ref, status, notSent)
    }

    @Synchronized
    private fun finish(
        ref: RequestRef,
        status: AttentionStatus,
        notSent: Boolean = false,
    ) {
        val target = aliases[ref] ?: ref
        if (notSent) {
            responseAttempts.remove(ref)
            responseAttempts.remove(target)
        }
        val resolvedAttempt = resolvedInFlight.remove(target)
        replace(target) { item ->
            // Only the exact already-sent resolved attempt may finish after withdrawal.
            if (item.status == AttentionStatus.SUBMITTING ||
                (resolvedAttempt && status == AttentionStatus.CONFIRMED && item.status == AttentionStatus.EXPIRED)
            ) {
                item.copy(status = status)
            } else {
                item
            }
        }
    }

    private fun replace(
        ref: RequestRef,
        transform: (AttentionItem) -> AttentionItem,
    ) {
        mutableItems.value = mutableItems.value.map { if (it.ref == ref) transform(it) else it }
    }
}
