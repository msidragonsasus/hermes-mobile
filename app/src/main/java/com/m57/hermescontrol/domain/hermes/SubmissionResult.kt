package com.m57.hermescontrol.domain.hermes

/** requestId is the existing JSON-RPC correlation ID, never an idempotency key or server Run ID. */
sealed interface SubmissionResult<out T> {
    data class Confirmed<T>(
        val requestId: String,
        val value: T,
    ) : SubmissionResult<T>

    data object NotSent : SubmissionResult<Nothing>

    data object Unknown : SubmissionResult<Nothing>
}

enum class PromptAcceptance { STREAMING, QUEUED, REDIRECTED, STEERED }
