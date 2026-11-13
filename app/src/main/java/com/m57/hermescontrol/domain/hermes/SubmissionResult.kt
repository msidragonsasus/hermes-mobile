package com.m57.hermescontrol.domain.hermes

/** requestId is the existing JSON-RPC correlation ID, never an idempotency key or server Run ID. */
sealed interface SubmissionResult<out T> {
    data class Confirmed<T>(
        val requestId: String,
        val value: T,
        val promptEvidence: PromptEvidence? = null,
        val proofSource: SubmissionProofSource = SubmissionProofSource.RPC_ACK,
        val terminalObservation: com.m57.hermescontrol.data.ws.ScopedWsEvent? = null,
        val terminalAuthenticationEpoch: Long? = null,
    ) : SubmissionResult<T>

    data object NotSent : SubmissionResult<Nothing>

    data object Expired : SubmissionResult<Nothing>

    data object Unknown : SubmissionResult<Nothing>
}

data class PromptEvidence(
    val userRowId: Long? = null,
    val submissionId: String? = null,
)

enum class SubmissionProofSource { RPC_ACK, TERMINAL_RECEIPT }

enum class PromptAcceptance { STREAMING, QUEUED, REDIRECTED, STEERED }
