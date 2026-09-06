package com.m57.hermescontrol.navigation

import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.domain.hermes.RequestRef
import kotlinx.serialization.Serializable

/** Navigation saves identifiers, never the request body or an authorization decision. */
@Serializable
data class AttentionDetailKey(
    val ref: RequestRef,
) : NavKey
