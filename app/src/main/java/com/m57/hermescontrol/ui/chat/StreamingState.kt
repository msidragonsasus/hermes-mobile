package com.m57.hermescontrol.ui.chat

data class StreamingState(
    val streamingMessage: ChatMessage? = null,
    val isThinking: Boolean = false,
    val thinkingText: String = "",
    val isReasoning: Boolean = false,
    val reasoningText: String = "",
    /** Last terminal row in this turn; done/complete can both finalize the same reply. */
    val finalizedMessage: ChatMessage? = null,
    /** Raw complete payload, including any commentary stripped from the displayed row. */
    val completedText: String? = null,
    /**
     * Ids of assistant messages sealed at tool.start during the CURRENT turn
     * (interim commentary before a tool call). Consumed by
     * [com.m57.hermescontrol.ui.chat.ChatWsEventReducer.onMessageComplete] to
     * strip the repeated prefix from the final text (issue #842). Reset on
     * every fresh StreamingState (message.start / interrupt / session switch).
     */
    val sealedOrphanIds: List<String> = emptyList(),
    /**
     * True while reply text is the latest turn activity. Thinking, reasoning,
     * segment boundaries and tool frames clear it; the 「思考中」 indicator stays
     * hidden while it is set because the growing text already shows progress.
     */
    val isWritingText: Boolean = false,
    /**
     * Set by message.complete/done (or an acknowledged interrupt / idle status)
     * until the next message.start, so late frames cannot revive a finished turn.
     */
    val turnEnded: Boolean = false,
    /** Exact newly sealed interim delivery in this observation; never restored from prose. */
    val previewedFinalCandidateId: String? = null,
)
