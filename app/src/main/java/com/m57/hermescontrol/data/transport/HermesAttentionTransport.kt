package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.domain.hermes.SubmissionResult

/** Responses always address the original request. No broad approval or protocol fallback. */
interface HermesAttentionTransport {
    suspend fun respondApproval(
        ref: RequestRef,
        choice: String,
    ): SubmissionResult<Boolean>

    suspend fun acknowledgeApproval(ref: RequestRef): SubmissionResult<Boolean>

    suspend fun respondClarification(
        ref: RequestRef,
        answer: String,
    ): SubmissionResult<Boolean>
}
