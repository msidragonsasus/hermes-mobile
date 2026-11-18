package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable
import java.time.Instant

/** Sanitized projection of Hermes' AccountUsageSnapshot; never contains credentials or raw payloads. */
@Serializable
data class AccountLimitsResponse(
    val available: Boolean = false,
    val provider: String? = null,
    val plan: String? = null,
    val fetched_at: String? = null,
    val windows: List<AccountLimitWindow> = emptyList(),
    val unavailable_reason: String? = null,
) {
    fun isFreshAt(now: Instant): Boolean {
        val checked = parseLimitInstant(fetched_at) ?: return false
        val age = now.epochSecond - checked.epochSecond
        return age in -30..300
    }
}

@Serializable
data class AccountLimitWindow(
    val label: String,
    val used_percent: Double? = null,
    val reset_at: String? = null,
) {
    val remainingPercent: Double?
        get() = used_percent?.takeIf { it.isFinite() && it in 0.0..100.0 }?.let { 100.0 - it }

    fun resetMinutesAt(now: Instant): Long? =
        parseLimitInstant(reset_at)?.let { reset ->
            val seconds = reset.epochSecond - now.epochSecond
            if (seconds <= 0) 0 else (seconds + 59) / 60
        }
}

internal fun parseLimitInstant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
