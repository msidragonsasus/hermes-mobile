package com.m57.hermescontrol.pcpower

import kotlinx.serialization.Serializable

@Serializable
data class PcPowerStatus(
    val api_version: Int,
    val target_name: String,
    val power_state: String,
    val wake_available: Boolean,
    val sleep_available: Boolean,
    val sleep_error: String? = null,
)

@Serializable
data class PcPowerActionResponse(
    val api_version: Int,
    val target_name: String,
    val result: String,
    val operation_id: String? = null,
)

@Serializable
data class PcPowerSleepRequest(
    val operation_id: String,
    val confirm: Boolean,
)
