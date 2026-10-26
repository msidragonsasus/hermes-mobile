package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.domain.hermes.ConnectionScope

/** Scope is captured by the socket listener, never sampled by a downstream collector. */
data class ScopedWsEvent(
    val scope: ConnectionScope,
    val ordinal: Long,
    val event: WsEvent?,
    val gap: Boolean = false,
)
