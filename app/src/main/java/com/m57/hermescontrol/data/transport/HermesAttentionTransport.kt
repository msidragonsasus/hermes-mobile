package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.domain.hermes.SubmissionResult

/** Responses always address the original request. No broad approval or protocol fallback. */
sealed interface ClarificationResult {
    data class Locked(
        val remaining: List<String>,
    ) : ClarificationResult

    data object Expired : ClarificationResult
}

interface HermesAttentionTransport {
    suspend fun lockClarification(
        ref: RequestRef,
        answer: String?,
        knownQuestionIds: Set<String>,
    ): SubmissionResult<ClarificationResult> = SubmissionResult.NotSent

    suspend fun respondApproval(
        ref: RequestRef,
        choice: String,
    ): SubmissionResult<Boolean>

    suspend fun acknowledgeApproval(ref: RequestRef): SubmissionResult<Boolean>

    suspend fun respondClarification(
        ref: RequestRef,
        answer: String?,
    ): SubmissionResult<Boolean>
}
