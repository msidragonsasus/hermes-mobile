package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent

/** These frames are admitted only from the socket's original scoped observation. */
internal fun isTranscriptEvent(event: WsEvent?): Boolean =
    when (event) {
        is WsEvent.MessageStart, is WsEvent.MessageToken, is WsEvent.MessageComplete,
        is WsEvent.MessageDone, is WsEvent.MessageInterim, is WsEvent.MessageSegmentEnd,
        is WsEvent.ThinkingDelta, is WsEvent.ReasoningDelta, is WsEvent.ReasoningAvailable,
        is WsEvent.ToolStart, is WsEvent.ToolComplete, is WsEvent.ToolProgress,
        is WsEvent.ToolGenerating,
        -> true

        else -> false
    }
