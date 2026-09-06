package com.m57.hermescontrol.domain.hermes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AttentionKind { APPROVAL, CLARIFICATION }

enum class AttentionStatus { PENDING, SUBMITTING, CONFIRMED, EXPIRED, UNKNOWN }

/** Transient observations. Replaying a request never grants permission to resend a response. */
data class AttentionItem(
    val ref: RequestRef,
    val kind: AttentionKind,
    val title: String,
    val expiresAt: Long? = null,
    val status: AttentionStatus = AttentionStatus.PENDING,
)

/** Exact identity includes server, both profiles, socket generation, session and question. */
class AttentionRepository {
    private val mutableItems = MutableStateFlow<List<AttentionItem>>(emptyList())
    val items = mutableItems.asStateFlow()

    @Synchronized
    fun observe(item: AttentionItem): AttentionItem {
        require(item.ref.requestId.isNotBlank())
        require(
            item.ref.session.runtimeId
                .isNotBlank(),
        )
        val existing = mutableItems.value.firstOrNull { it.ref == item.ref }
        if (existing != null) return existing
        val pending = item.copy(status = AttentionStatus.PENDING)
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
    private fun begin(
        ref: RequestRef,
        currentScope: ConnectionScope?,
        ready: Boolean,
        now: Long,
    ): Boolean {
        val item = mutableItems.value.firstOrNull { it.ref == ref } ?: return false
        if (item.status != AttentionStatus.PENDING) return false
        if (item.expiresAt?.let { it <= now } == true) {
            expire(ref)
            return false
        }
        if (!ready || currentScope != ref.scope) return false
        replace(ref) { it.copy(status = AttentionStatus.SUBMITTING) }
        return true
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
        val status =
            try {
                when (val result = send()) {
                    is SubmissionResult.Confirmed -> {
                        if (result.value) AttentionStatus.CONFIRMED else AttentionStatus.UNKNOWN
                    }

                    SubmissionResult.NotSent -> {
                        AttentionStatus.PENDING
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
        finish(ref, status)
    }

    @Synchronized
    private fun finish(
        ref: RequestRef,
        status: AttentionStatus,
    ) {
        replace(ref) { item ->
            // A late ACK cannot undo expiry or mutate a different request.
            if (item.status == AttentionStatus.SUBMITTING) item.copy(status = status) else item
        }
    }

    private fun replace(
        ref: RequestRef,
        transform: (AttentionItem) -> AttentionItem,
    ) {
        mutableItems.value = mutableItems.value.map { if (it.ref == ref) transform(it) else it }
    }
}
