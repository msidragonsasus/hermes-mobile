package com.m57.hermescontrol.data.transport

import com.m57.hermescontrol.domain.hermes.AttentionSignal
import kotlinx.coroutines.flow.Flow

/** Read-only incoming requests. Starting collection never grants or submits a response. */
interface HermesAttentionSource {
    val attentionSignals: Flow<AttentionSignal>
}
