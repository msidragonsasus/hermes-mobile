package com.m57.hermescontrol.ui.chat

/** Keep live identity/metadata, but never let it replace an authoritative REST display body. */
internal fun preferServerDisplayProjection(
    incoming: ChatMessage,
    current: ChatMessage,
): ChatMessage =
    if (incoming.hasServerDisplayProjection) {
        current.copy(
            content = incoming.content,
            sourceBody = incoming.sourceBody.takeUnless { incoming.displayKind == "hidden" },
            reasoningText = incoming.reasoningText,
            attachments = incoming.attachments,
            displayKind = incoming.displayKind,
            isStreaming = incoming.isStreaming,
            hasServerDisplayProjection = true,
        )
    } else {
        current
    }
