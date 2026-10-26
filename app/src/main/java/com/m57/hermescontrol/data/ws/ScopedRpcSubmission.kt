package com.m57.hermescontrol.data.ws

import kotlinx.coroutines.Deferred

/** Socket acceptance is not a server ACK or proof of execution. */
data class ScopedRpcSubmission(
    val requestId: String?,
    val accepted: Boolean,
    val response: Deferred<Any?>,
)
