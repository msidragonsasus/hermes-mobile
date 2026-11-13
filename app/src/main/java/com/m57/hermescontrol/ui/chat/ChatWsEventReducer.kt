package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.parseUsageSnapshot
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.toJsonElement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun List<ChatMessage>.upsertById(message: ChatMessage): List<ChatMessage> = (this + message).dedupeById()

/**
 * Pure state reducer for WebSocket events.
 *
 * Transforms [ChatUiState] in response to each [WsEvent] and returns a
 * [ReducerResult] containing the new state plus any side-effects the
 * ViewModel should execute (persistence, navigation, etc.).
 *
 * This is a pure function — no I/O, no mutable state, no dependencies on
 * Android or ViewModel classes. The default hydrator keeps this behavior; a caller may supply
 * a memo scoped to its own lifecycle.
 */
object ChatWsEventReducer {
    fun reduce(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent,
        currentSessionId: String? = null,
        hydrateTodos: (List<ChatMessage>) -> List<TodoItem> = ::hydrateTodosFromMessages,
    ): ReducerResult {
        val eventSessionId =
            when (event) {
                is WsEvent.MessageStart -> event.sessionId
                is WsEvent.MessageToken -> event.sessionId
                is WsEvent.ThinkingDelta -> event.sessionId
                is WsEvent.ReasoningDelta -> event.sessionId
                is WsEvent.ReasoningAvailable -> event.sessionId
                is WsEvent.MessageComplete -> event.sessionId
                is WsEvent.MessageDone -> event.sessionId
                is WsEvent.MessageInterim -> event.sessionId
                is WsEvent.MessageSegmentEnd -> event.sessionId
                is WsEvent.ToolStart -> event.sessionId
                is WsEvent.ToolComplete -> event.sessionId
                is WsEvent.ToolOutputRisk -> event.sessionId
                is WsEvent.ClarifyRequest -> event.sessionId
                is WsEvent.ClarifyExpire -> event.sessionId
                is WsEvent.ClarifyResolved -> event.sessionId
                is WsEvent.ToolProgress -> event.sessionId
                is WsEvent.ToolGenerating -> event.sessionId
                is WsEvent.SubagentEvent -> event.sessionId
                is WsEvent.ReviewSummary -> event.sessionId
                is WsEvent.BtwComplete -> event.sessionId
                is WsEvent.SessionUsage -> event.sessionId
                else -> null
            }
        if (eventSessionId != null && currentSessionId != null && eventSessionId != currentSessionId) {
            return ReducerResult(state = state, streamingState = streamingState)
        }
        val reduced = resumeObservedTurn(state, streamingState, event, reduceInternal(state, streamingState, event))
        val observedActivity =
            event is WsEvent.MessageStart || event is WsEvent.MessageToken ||
                event is WsEvent.ThinkingDelta || event is WsEvent.ReasoningDelta ||
                event is WsEvent.ToolStart || event is WsEvent.ToolComplete ||
                event is WsEvent.MessageInterim || event is WsEvent.MessageSegmentEnd
        val result =
            if (observedActivity || event is WsEvent.MessageDone || event is WsEvent.MessageComplete ||
                (state.isAgentTyping && !reduced.state.isAgentTyping)
            ) {
                reduced.copy(state = reduced.state.copy(responseStatusUnknown = false))
            } else {
                reduced
            }
        val hydratedTodos =
            if (result.state.todos.isEmpty() && result.state.messages.isNotEmpty()) {
                hydrateTodos(result.state.messages)
            } else {
                result.state.todos
            }
        return if (hydratedTodos !== result.state.todos) {
            result.copy(state = result.state.copy(todos = hydratedTodos))
        } else {
            result
        }
    }

    /**
     * Work frames of a turn that has not ended show it is running again, e.g.
     * after an approval was answered. Waiting prompts keep precedence, and a
     * late frame after message.complete/done never revives a finished turn.
     */
    private fun resumeObservedTurn(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent,
        reduced: ReducerResult,
    ): ReducerResult {
        val workFrame =
            (event is WsEvent.ThinkingDelta && event.token.isNotEmpty()) ||
                event is WsEvent.ReasoningDelta || event is WsEvent.ReasoningAvailable ||
                event is WsEvent.ToolGenerating || event is WsEvent.ToolStart || event is WsEvent.ToolComplete ||
                event is WsEvent.MessageInterim || event is WsEvent.MessageSegmentEnd
        if (!workFrame || reduced.state.isAgentTyping || streamingState.turnEnded || state.awaitsUserInput()) {
            return reduced
        }
        return reduced.copy(state = reduced.state.copy(isAgentTyping = true))
    }

    private fun reduceInternal(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent,
    ): ReducerResult =
        when (event) {
            is WsEvent.GatewayReady -> {
                onGatewayReady(state, streamingState)
            }

            is WsEvent.MessageStart -> {
                onMessageStart(state, streamingState, event)
            }

            is WsEvent.MessageToken -> {
                onMessageToken(state, streamingState, event)
            }

            is WsEvent.MessageInterim -> {
                onMessageInterim(state, streamingState, event)
            }

            is WsEvent.MessageSegmentEnd -> {
                ReducerResult(state = state, streamingState = streamingState.copy(isWritingText = false))
            }

            is WsEvent.ThinkingDelta -> {
                onThinkingDelta(state, streamingState, event)
            }

            is WsEvent.ReasoningDelta -> {
                onReasoningDelta(state, streamingState, event)
            }

            is WsEvent.ReasoningAvailable -> {
                onReasoningAvailable(state, streamingState, event)
            }

            is WsEvent.MessageComplete -> {
                onMessageComplete(state, streamingState, event)
            }

            is WsEvent.MessageDone -> {
                onMessageDone(state, streamingState)
            }

            is WsEvent.ToolStart -> {
                onToolStart(state, streamingState, event)
            }

            is WsEvent.ToolComplete -> {
                onToolComplete(state, streamingState, event)
            }

            is WsEvent.ToolOutputRisk -> {
                onToolOutputRisk(state, streamingState, event)
            }

            is WsEvent.ToolProgress -> {
                onToolProgress(state, streamingState, event)
            }

            is WsEvent.ToolGenerating -> {
                onToolGenerating(state, streamingState, event)
            }

            is WsEvent.SubagentEvent -> {
                onSubagentEvent(state, streamingState, event)
            }

            is WsEvent.ClarifyRequest -> {
                onClarifyRequest(state, streamingState, event)
            }

            is WsEvent.ClarifyExpire -> {
                closeClarification(state, streamingState, event.clarifyId)
            }

            is WsEvent.ClarifyResolved -> {
                closeClarification(state, streamingState, event.clarifyId)
            }

            is WsEvent.ReviewSummary -> {
                onReviewSummary(state, streamingState, event)
            }

            is WsEvent.BtwComplete -> {
                onBtwComplete(state, streamingState, event)
            }

            is WsEvent.SessionUsage -> {
                onSessionUsage(state, streamingState, event)
            }

            is WsEvent.TodoUpdated -> {
                onTodoUpdated(state, streamingState, event)
            }

            is WsEvent.RpcError -> {
                onRpcError(state, streamingState, event)
            }

            is WsEvent.GatewayError -> {
                onGatewayError(state, streamingState, event)
            }

            is WsEvent.BackgroundComplete -> {
                onBackgroundComplete(state, streamingState, event)
            }

            is WsEvent.SessionUpdated -> {
                onSessionUpdated(state, streamingState)
            }

            is WsEvent.StatusUpdate -> {
                onStatusUpdate(state, streamingState)
            }

            is WsEvent.Unknown -> {
                onUnknown(state, streamingState)
            }

            // SessionInfo is a no-op in the original code
            is WsEvent.SessionInfo -> {
                ReducerResult(state = state, streamingState = streamingState)
            }

            // RpcResult is handled by the ViewModel (needs pending request context)
            is WsEvent.RpcResult -> {
                ReducerResult(state = state, streamingState = streamingState)
            }

            // ApprovalRequest is handled by the ViewModel (needs active session + WS client)
            is WsEvent.ApprovalRequest -> {
                ReducerResult(state = state, streamingState = streamingState)
            }

            // SudoRequest / SecretRequest are handled by the ViewModel (issue #524)
            is WsEvent.SudoRequest -> {
                ReducerResult(state = state, streamingState = streamingState)
            }

            is WsEvent.SudoExpire -> {
                ReducerResult(state = state, streamingState = streamingState)
            }

            is WsEvent.SecretRequest -> {
                ReducerResult(state = state, streamingState = streamingState)
            }

            is WsEvent.SecretExpire -> {
                ReducerResult(state = state, streamingState = streamingState)
            }

            is WsEvent.VaultRequest,
            is WsEvent.ServerRequestCancel,
            is WsEvent.OpenRequestsSnapshot,
            is WsEvent.UnsupportedServerRequest,
            -> {
                ReducerResult(state = state, streamingState = streamingState)
            }

            // ReactionEvent is handled by the ViewModel — purely cosmetic animation
            is WsEvent.ReactionEvent -> {
                ReducerResult(state = state, streamingState = streamingState)
            }

            // Change events (issue #784) are consumed by their screens' ViewModels
            is WsEvent.ChangeEvent -> {
                ReducerResult(state = state, streamingState = streamingState)
            }
        }

    // ── GatewayReady ──────────────────────────────────────────────────

    private fun onGatewayReady(
        state: ChatUiState,
        streamingState: StreamingState,
    ): ReducerResult = ReducerResult(state = state, streamingState = streamingState)

    // ── MessageStart ──────────────────────────────────────────────────

    private fun onMessageStart(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.MessageStart,
    ): ReducerResult {
        val msg =
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                // Issue #755: never seed a new message with the previous
                // message's reasoning — the card must start blank and only
                // fill once real reasoning deltas arrive.
                reasoningText = "",
                timestamp = System.currentTimeMillis(),
                isStreaming = true,
            )
        val effects = mutableListOf<ReducerEffect>()

        val cleanedSubagents =
            if (state.subagentIndicators.all { it.isTerminal }) {
                emptyList()
            } else {
                state.subagentIndicators
            }

        // Build new state: finalize any orphan streaming message, then set the new one
        var orphan: ChatMessage? = null
        val preState =
            if (streamingState.streamingMessage?.content?.isNotEmpty() == true) {
                val finalized =
                    streamingState.streamingMessage.copy(
                        isStreaming = false,
                        finishTimestamp = System.currentTimeMillis(),
                    )
                orphan = finalized
                state.copy(
                    messages = state.messages.upsertById(finalized),
                    isAgentTyping = true,
                    subagentIndicators = cleanedSubagents,
                )
            } else {
                state.copy(
                    isAgentTyping = true,
                    subagentIndicators = cleanedSubagents,
                )
            }
        val newStreamingState =
            StreamingState(
                streamingMessage = msg,
                // Issue #755: the new message starts with a clean reasoning
                // slate — no isReasoning flag, no inherited reasoning text.
                isReasoning = false,
                reasoningText = "",
            )
        val newState = preState
        val sid = newState.currentSessionId
        if (orphan != null && sid != null) {
            effects.add(ReducerEffect.PersistMessage(orphan, sid))
        }

        return ReducerResult(state = newState, streamingState = newStreamingState, effects = effects)
    }

    // ── MessageToken ──────────────────────────────────────────────────

    private fun onMessageToken(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.MessageToken,
    ): ReducerResult {
        // Token accumulation is done by the ViewModel (streaming buffer + flush timer).
        // The reducer only signals that new content arrived — ViewModel owns the timer.
        return ReducerResult(
            state = state,
            streamingState =
                if (event.token.isNotEmpty() && streamingState.previewedFinalCandidateId != null) {
                    streamingState.copy(previewedFinalCandidateId = null)
                } else {
                    streamingState
                },
        )
    }

    // ── MessageInterim ────────────────────────────────────────────────

    /**
     * `message.interim` seals interim commentary as its own bubble while the
     * turn continues (with or without tools). Text that already streamed is
     * sealed from the live message; unstreamed text is added once. Sealed ids
     * retain the delivered segments' identities exactly like tool.start seals.
     */
    private fun onMessageInterim(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.MessageInterim,
    ): ReducerResult {
        val now = System.currentTimeMillis()
        val live = streamingState.streamingMessage?.takeIf { it.content.isNotBlank() }
        val reasoning = streamingState.reasoningText.ifBlank { live?.reasoningText.orEmpty() }
        val segments = mutableListOf<ChatMessage>()
        if (live != null) {
            val observedBody = live.observedPreviewBody()
            // The server's streamed flag does not prove that every delta reached this client.
            // Fill only this observed delivery's strict raw prefix, never stripped/equal prose.
            val sealedLive =
                if (!streamingState.turnEnded && event.alreadyStreamed && event.text.isNotBlank() &&
                    observedBody.length < event.text.length && event.text.startsWith(observedBody)
                ) {
                    live.copy(content = event.text, sourceBody = event.text)
                } else {
                    live
                }
            segments += sealedLive.copy(isStreaming = false, reasoningText = reasoning, finishTimestamp = now)
        }
        val text = event.text
        val turn = state.messages.drop(state.messages.indexOfLast { it.role == MessageRole.USER } + 1) + segments

        fun ChatMessage.shows(text: String) = role == MessageRole.ASSISTANT && content.trim() == text.trim()
        // Only explicit already-streamed evidence can reuse a delivered segment.
        // Equal unstreamed prose is an independent delivery, including next to a live stream.
        val alreadyShown = event.alreadyStreamed && (live != null || turn.any { it.shows(text) })
        if (text.isNotBlank() && !alreadyShown) {
            segments +=
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = text,
                    timestamp = now,
                    reasoningText = if (live == null) reasoning else "",
                    finishTimestamp = now,
                )
        }
        val sid = state.currentSessionId
        val effects: List<ReducerEffect> =
            if (sid == null) emptyList() else segments.map { ReducerEffect.PersistMessage(it, sid) }
        return ReducerResult(
            state = state.copy(messages = segments.fold(state.messages) { messages, m -> messages.upsertById(m) }),
            streamingState =
                streamingState.copy(
                    streamingMessage = if (segments.isNotEmpty()) null else streamingState.streamingMessage,
                    sealedOrphanIds = streamingState.sealedOrphanIds + segments.map { it.id },
                    isWritingText = false,
                    previewedFinalCandidateId =
                        segments.lastOrNull()?.id
                            ?: streamingState.previewedFinalCandidateId?.takeIf { candidateId ->
                                event.alreadyStreamed &&
                                    turn.singleOrNull { it.id == candidateId }?.observedPreviewBody()?.trim() ==
                                    text.trim()
                            },
                ),
            effects = effects,
        )
    }

    // ── ThinkingDelta ─────────────────────────────────────────────────

    private fun onThinkingDelta(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ThinkingDelta,
    ): ReducerResult {
        // An empty thinking.delta clears the status around an API call; it is
        // neither content nor the end of the turn.
        if (event.token.isEmpty()) {
            return ReducerResult(
                state = state,
                streamingState = streamingState.copy(isThinking = false, thinkingText = ""),
            )
        }
        val currentContent = streamingState.thinkingText + event.token
        return ReducerResult(
            state = state,
            streamingState =
                streamingState.copy(
                    isThinking = true,
                    thinkingText = currentContent,
                    isWritingText = false,
                ),
        )
    }

    // ── ReasoningDelta ────────────────────────────────────────────────
    private fun onReasoningDelta(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ReasoningDelta,
    ): ReducerResult {
        val currentContent = streamingState.reasoningText + event.token
        return ReducerResult(
            state = state,
            streamingState =
                streamingState.copy(
                    isReasoning = true,
                    reasoningText = currentContent,
                    isWritingText = false,
                ),
        )
    }

    // ── ReasoningAvailable ────────────────────────────────────────────
    private fun onReasoningAvailable(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ReasoningAvailable,
    ): ReducerResult = onReasoningBlock(state, streamingState.copy(isWritingText = false), event)

    private fun onReasoningBlock(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ReasoningAvailable,
    ): ReducerResult {
        // The gateway sends the FULL reasoning trace in reasoning.available
        // (payload `text`) once reasoning finishes streaming. When the text
        // is present, use it as the authoritative fill — it survives even if
        // per-token reasoning.delta events were dropped or wiped by a
        // mid-turn tool.start. Attach it to the live streaming message so
        // message.complete carries it into the finalized bubble.
        val text = event.text
        return if (!text.isNullOrBlank() && streamingState.streamingMessage != null) {
            // Issue #842: the gateway ALSO re-sends the agent's interim
            // NARRATION as reasoning.available ~40ms before each tool.start
            // (the commentary already rendered as the message body, echoed
            // verbatim). Attaching the echo as reasoning would make every
            // narration bubble render its own text twice (body + Reasoning
            // card). Real reasoning never equals the message content, so the
            // exact-content echo is skipped and the reasoning.delta trace
            // (already in streamingState) stays authoritative.
            val content = streamingState.streamingMessage.content
            if (text.trim() == content.trim()) {
                ReducerResult(
                    state = state,
                    streamingState = streamingState.copy(isReasoning = true),
                )
            } else {
                ReducerResult(
                    state = state,
                    streamingState =
                        streamingState.copy(
                            isReasoning = true,
                            reasoningText = text,
                            streamingMessage = streamingState.streamingMessage.copy(reasoningText = text),
                        ),
                )
            }
        } else {
            ReducerResult(
                state = state,
                streamingState = streamingState.copy(isReasoning = true),
            )
        }
    }

    // ── MessageComplete ───────────────────────────────────────────────

    /** Storage may synthesize edited prose plus older MEDIA hints; that is not observed delivery evidence. */
    private fun ChatMessage.observedPreviewBody(): String {
        val source = sourceBody
        return if (source != null &&
            (source == content || HostMediaExtractor.strip(source) == content)
        ) {
            source
        } else {
            content
        }
    }

    private fun finalizedMessageInCurrentTurn(
        state: ChatUiState,
        streamingState: StreamingState,
    ): ChatMessage? {
        val finalized = streamingState.finalizedMessage ?: return null
        val turn = state.messages.drop(state.messages.indexOfLast { it.role == MessageRole.USER } + 1)
        // Hydration may have replaced the local UUID with its REST counterpart.
        // Only match within this turn so a later identical answer stays distinct.
        return turn.firstOrNull { it.id == finalized.id }
    }

    private fun onMessageComplete(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.MessageComplete,
    ): ReducerResult {
        if (event.responseReused == true) {
            return onMessageComplete(
                state,
                streamingState,
                event.copy(
                    text = streamingState.streamingMessage?.content.orEmpty(),
                    responseReused = false,
                ),
            )
        }
        val streaming = streamingState.streamingMessage
        // The wire flag proves prior delivery, but does not select a row by equal prose.
        // Select only the actual current-observation interim, then verify its source body.
        val previewed =
            if (event.responsePreviewed == true && !streamingState.turnEnded &&
                streaming?.content.isNullOrBlank()
            ) {
                state.messages
                    .drop(state.messages.indexOfLast { it.role == MessageRole.USER } + 1)
                    .singleOrNull {
                        it.id == streamingState.previewedFinalCandidateId &&
                            it.id in streamingState.sealedOrphanIds &&
                            it.role == MessageRole.ASSISTANT && !it.isStreaming &&
                            it.observedPreviewBody().trim() == event.text.trim()
                    }
            } else {
                null
            }
        if (streamingState.turnEnded && streaming == null && streamingState.finalizedMessage != null &&
            state.messages.none { it.id == streamingState.finalizedMessage.id }
        ) {
            // Canonical replacement retired the whole local manifest. A terminal replay without
            // an explicit surviving row alias cannot select a new row by equal prose.
            return ReducerResult(state, streamingState)
        }
        val finalized =
            previewed ?: if (streaming == null &&
                (streamingState.completedText == null || streamingState.completedText.trim() == event.text.trim())
            ) {
                finalizedMessageInCurrentTurn(state, streamingState)
            } else {
                null
            }
        // Prefer the authoritative reasoning carried in the message.complete
        // payload (the gateway's assembled full trace), then fall back to
        // whatever reasoning.delta deltas accumulated during streaming.
        // Fixes: a mid-turn tool.start wiping the streaming reasoning used
        // to leave the finalized bubble with no reasoning card at all.
        val reasoning =
            event.reasoning
                ?.takeIf { it.isNotBlank() }
                ?: streamingState.reasoningText.ifBlank {
                    streaming?.reasoningText ?: finalized?.reasoningText.orEmpty()
                }
        // A distinct final body may repeat sealed commentary verbatim. Only
        // response_reused=true or an explicitly previewed selected interim proves prior delivery; equal
        // prose or a shared prefix does not establish segment identity.
        val text =
            if (finalized != null && streamingState.completedText != null) {
                // A replay targets the already-finalized row, not a new segment.
                finalized.content
            } else {
                event.text
            }
        // A reused delivery with no live stream has no new final body.
        // An empty completion likewise must not create an empty final bubble.
        if (text.isBlank()) {
            return ReducerResult(
                state = state.copy(isAgentTyping = false, clarifyRequest = null),
                streamingState =
                    StreamingState(
                        sealedOrphanIds = streamingState.sealedOrphanIds,
                        finalizedMessage =
                            finalized ?: state.messages.lastOrNull { it.id in streamingState.sealedOrphanIds },
                        completedText = event.text,
                        turnEnded = true,
                    ),
            )
        }
        val msg =
            (previewed ?: streaming ?: finalized)?.copy(
                content = text,
                isStreaming = false,
                reasoningText = reasoning,
                finishTimestamp = finalized?.finishTimestamp ?: System.currentTimeMillis(),
            ) ?: ChatMessage(
                role = MessageRole.ASSISTANT,
                content = text,
                reasoningText = reasoning,
                timestamp = System.currentTimeMillis(),
                finishTimestamp = System.currentTimeMillis(),
            )
        val terminalState =
            StreamingState(
                finalizedMessage = msg,
                completedText = event.text,
                sealedOrphanIds = streamingState.sealedOrphanIds,
                turnEnded = true,
            )
        if (streamingState.turnEnded && msg == finalized) {
            return ReducerResult(
                state = state.copy(isAgentTyping = false),
                streamingState = terminalState,
            )
        }
        val effects = mutableListOf<ReducerEffect>()
        val sid = state.currentSessionId
        if (sid != null) {
            effects.add(ReducerEffect.PersistMessage(msg, sid))
        }
        effects.add(ReducerEffect.RefreshSessions)
        // Refresh the context meter immediately on completion instead of
        // waiting for the next 5s session-sync poll.
        effects.add(ReducerEffect.RefreshContextUsage)
        // Mobile-only media fix (issue #724): the WS stream delivers the raw
        // `MEDIA:<host-path>` directive. Turn it into real attachments (images
        // inline, every other file tappable) via the gateway /api/files/download
        // endpoint — works on a remote phone too.
        if (msg.role == MessageRole.ASSISTANT && sid != null) {
            effects.add(ReducerEffect.AttachHostMedia(sid, msg.id))
        }
        return ReducerResult(
            state =
                state.copy(
                    messages = state.messages.upsertById(msg),
                    isAgentTyping = false,
                    clarifyRequest = null,
                ),
            streamingState = terminalState,
            effects = effects,
        )
    }

    // ── MessageDone ───────────────────────────────────────────────────

    private fun onMessageDone(
        state: ChatUiState,
        streamingState: StreamingState,
    ): ReducerResult {
        val streaming =
            streamingState.streamingMessage ?: return ReducerResult(
                // A terminal event ends waiting even when a tool boundary
                // already sealed the last assistant bubble. Do not invent a
                // message or discard drafts when no text remains to persist.
                state = state.copy(isAgentTyping = false, clarifyRequest = null),
                streamingState =
                    StreamingState(
                        finalizedMessage = streamingState.finalizedMessage,
                        completedText = streamingState.completedText,
                        sealedOrphanIds = streamingState.sealedOrphanIds,
                        turnEnded = true,
                    ),
            )
        val reasoning =
            if (streamingState.reasoningText.isNotBlank()) {
                streamingState.reasoningText
            } else {
                streaming.reasoningText
            }
        val msg =
            streaming.copy(
                isStreaming = false,
                reasoningText = reasoning,
                finishTimestamp = System.currentTimeMillis(),
            )
        val effects = mutableListOf<ReducerEffect>()
        val sid = state.currentSessionId
        if (sid != null) {
            effects.add(ReducerEffect.PersistMessage(msg, sid))
        }
        // Mobile-only media fix (issue #724): attach any `MEDIA:<path>` file
        // carried in the finalized assistant message as a real attachment. See
        // the ReducerEffect.AttachHostMedia KDoc for the rationale.
        if (msg.role == MessageRole.ASSISTANT && sid != null) {
            effects.add(ReducerEffect.AttachHostMedia(sid, msg.id))
        }
        return ReducerResult(
            state =
                state.copy(
                    messages = state.messages.upsertById(msg),
                    isAgentTyping = false,
                    clarifyRequest = null,
                ),
            streamingState =
                StreamingState(
                    finalizedMessage = msg,
                    sealedOrphanIds = streamingState.sealedOrphanIds,
                    turnEnded = true,
                ),
            effects = effects,
        )
    }

    // ── ToolStart ─────────────────────────────────────────────────────

    private fun onToolStart(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ToolStart,
    ): ReducerResult {
        val contentJson =
            event.data?.let {
                OkHttpProvider.json.encodeToString(it.toJsonElement())
            } ?: ""
        val toolMessage =
            ChatMessage(
                role = MessageRole.TOOL,
                content = contentJson,
                toolName = event.name,
                toolStatus = ToolStatus.RUNNING,
                // Issue #842: keep the gateway's call id so REST transcript
                // rows (which carry the same `tool_call_id`) can be matched
                // 1:1 against this live bubble during merges.
                toolCallId = event.data?.get("tool_id") as? String ?: "",
            )

        // Finalize any orphan streaming message (issue #771 + #842): interim
        // text streamed BEFORE the tool is sealed as its own bubble (desktop
        // parity). Track the sealed id in StreamingState as delivery identity,
        // without using its prose to strip a distinct final message.
        var orphanToPersist: ChatMessage? = null
        val newState =
            if (streamingState.streamingMessage?.content?.isNotEmpty() == true) {
                val reasoning =
                    if (streamingState.reasoningText.isNotBlank()) {
                        streamingState.reasoningText
                    } else {
                        streamingState.streamingMessage.reasoningText
                    }
                val finalized =
                    streamingState.streamingMessage.copy(
                        isStreaming = false,
                        reasoningText = reasoning,
                        finishTimestamp = System.currentTimeMillis(),
                    )
                orphanToPersist = finalized
                state.copy(
                    messages = state.messages.upsertById(finalized),
                )
            } else {
                state
            }
        val sid = newState.currentSessionId
        val effects = mutableListOf<ReducerEffect>()
        if (sid != null) {
            if (orphanToPersist != null) {
                effects.add(ReducerEffect.PersistMessage(orphanToPersist, sid))
            }
            effects.add(ReducerEffect.PersistMessage(toolMessage, sid))
        }

        // Issue #771: KEEP the streaming state across a tool call when the
        // stream has nothing to seal yet (a reasoning-model turn streams its
        // thinking BEFORE the tool starts) — wiping it made the reasoning
        // bubble vanish mid-turn. When an orphan WAS sealed (interim text
        // before the tool), the streaming message is done — null it, keep the
        // reasoning text in shared state, and remember the sealed orphan's id.
        val finalStreamingState =
            if (orphanToPersist != null) {
                streamingState.copy(
                    streamingMessage = null,
                    sealedOrphanIds = streamingState.sealedOrphanIds + orphanToPersist.id,
                    isWritingText = false,
                    previewedFinalCandidateId = null,
                )
            } else {
                streamingState.copy(isWritingText = false)
            }

        val parsedTodos = if (event.name == "todo") extractTodosFromMap(event.data) else null
        val nextTodos = parsedTodos ?: newState.todos

        return ReducerResult(
            state = newState.copy(messages = newState.messages + toolMessage, todos = nextTodos),
            streamingState = finalStreamingState,
            effects = effects,
        )
    }

    // ── ToolComplete ──────────────────────────────────────────────────

    private fun onToolComplete(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ToolComplete,
    ): ReducerResult {
        val contentJson =
            event.data?.let {
                OkHttpProvider.json.encodeToString(it.toJsonElement())
            } ?: ""
        val messages = state.messages.toMutableList()
        val toolIdx =
            messages.indexOfLast {
                it.role == MessageRole.TOOL &&
                    it.toolName == event.name &&
                    it.toolStatus == ToolStatus.RUNNING
            }
        if (toolIdx < 0) return ReducerResult(state = state, streamingState = streamingState)

        val updated =
            messages[toolIdx].copy(
                toolStatus = ToolStatus.COMPLETED,
                content = contentJson,
            )
        messages[toolIdx] = updated

        val effects = mutableListOf<ReducerEffect>()
        val sid = state.currentSessionId
        if (sid != null) {
            effects.add(ReducerEffect.PersistMessage(updated, sid))
        }

        val parsedTodos = if (event.name == "todo") extractTodosFromMap(event.data) else null
        val nextTodos = parsedTodos ?: state.todos

        return ReducerResult(
            state = state.copy(messages = messages, todos = nextTodos),
            streamingState = streamingState,
            effects = effects,
        )
    }

    // ── ToolOutputRisk ────────────────────────────────────────────────

    /**
     * Attaches [ToolOutputRiskData] to the matching tool message.
     *
     * Finds the last RUNNING tool message with matching [WsEvent.ToolOutputRisk.name].
     * If no RUNNING message exists (tool already completed), falls back to the last
     * COMPLETED tool with that name. This covers the case where the risk event arrives
     * after tool.complete.
     */
    private fun onToolOutputRisk(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ToolOutputRisk,
    ): ReducerResult {
        val riskData =
            ToolOutputRiskData(
                risk = event.risk,
                findings = event.findings,
                redacted = event.redacted,
            )

        val messages = state.messages.toMutableList()

        // Prefer RUNNING tool, fall back to COMPLETED
        var toolIdx =
            messages.indexOfLast {
                it.role == MessageRole.TOOL &&
                    it.toolName == event.name &&
                    it.toolStatus == ToolStatus.RUNNING
            }
        if (toolIdx < 0) {
            toolIdx =
                messages.indexOfLast {
                    it.role == MessageRole.TOOL &&
                        it.toolName == event.name &&
                        it.toolStatus == ToolStatus.COMPLETED
                }
        }
        if (toolIdx < 0) return ReducerResult(state = state, streamingState = streamingState)

        messages[toolIdx] = messages[toolIdx].copy(toolOutputRiskData = riskData)

        // No persist — risk data is transient (not stored in SQLite)
        return ReducerResult(
            state = state.copy(messages = messages),
            streamingState = streamingState,
        )
    }

    // ── ClarifyRequest ────────────────────────────────────────────────

    private fun onClarifyRequest(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ClarifyRequest,
    ): ReducerResult {
        val questions =
            if (event.questions.isNotEmpty()) {
                event.questions.map {
                    ClarifyQuestionUi(
                        qid = it.qid,
                        question = it.question,
                        choices = it.choices,
                        multiSelect = it.multiSelect,
                    )
                }
            } else {
                // Legacy single-question payload: keep questions empty so the wire
                // layer can tell legacy apart from a true batch. UI synthesizes
                // via ClarifyUi.resolvedQuestions; wire uses text/options/questionId.
                emptyList()
            }

        return ReducerResult(
            state =
                state.copy(
                    clarifyRequest =
                        ClarifyUi(
                            text = event.text.orEmpty(),
                            options = event.options.orEmpty(),
                            clarifyId = event.clarifyId,
                            questionId = event.questionId,
                            multiSelect = event.multiSelect,
                            questions = questions,
                        ),
                    isAgentTyping = false,
                ),
        )
    }

    private fun closeClarification(
        state: ChatUiState,
        streamingState: StreamingState,
        clarifyId: String?,
    ): ReducerResult =
        if (clarifyId == null || state.clarifyRequest?.clarifyId == clarifyId) {
            ReducerResult(
                state = state.copy(clarifyRequest = null),
                streamingState = streamingState,
            )
        } else {
            ReducerResult(state = state, streamingState = streamingState)
        }

    // ── ReviewSummary (Self-improvement background review) ──────────────

    private fun onReviewSummary(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ReviewSummary,
    ): ReducerResult {
        if (event.text.isBlank()) return ReducerResult(state = state, streamingState = streamingState)
        val systemMessage =
            ChatMessage(
                role = MessageRole.SYSTEM,
                content = event.text,
            )
        return ReducerResult(
            state = state.copy(messages = state.messages + systemMessage),
            streamingState = streamingState,
        )
    }

    // ── BtwComplete (Side question completed) ─────────────────────────────

    private fun onBtwComplete(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.BtwComplete,
    ): ReducerResult {
        val currentBtw = state.btwState
        val updatedBtw =
            if (currentBtw != null) {
                currentBtw.copy(
                    taskId = event.taskId.ifBlank { currentBtw.taskId },
                    question = event.question.ifBlank { currentBtw.question },
                    answer = event.text,
                    isLoading = false,
                )
            } else {
                BtwUiState(
                    taskId = event.taskId,
                    question = event.question,
                    answer = event.text,
                    isLoading = false,
                )
            }
        return ReducerResult(
            state = state.copy(btwState = updatedBtw),
            streamingState = streamingState,
        )
    }

    // ── RpcError ──────────────────────────────────────────────────────

    private fun onRpcError(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.RpcError,
    ): ReducerResult =
        ReducerResult(
            state =
                state.copy(
                    isLoading = false,
                    errorMessage = "Error: ${event.error}",
                ),
            streamingState = streamingState,
        )

    // ── GatewayError ──────────────────────────────────────────────────
    // Backend/unhandled failure surfaced by the gateway (issue #527).
    // Mirrors onRpcError: surfaces the message in the existing error banner.
    private fun onGatewayError(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.GatewayError,
    ): ReducerResult =
        ReducerResult(
            state =
                state.copy(
                    isLoading = false,
                    errorMessage = "Backend error: ${event.message ?: "Unknown gateway error"}",
                ),
            streamingState = streamingState,
        )

    // ── BackgroundComplete ────────────────────────────────────────────
    // A scheduled/background job finished (issue #527). The ViewModel turns
    // this into a non-blocking snackbar. No state mutation beyond passing it
    // through; the ViewModel owns the snackbar trigger.
    private fun onBackgroundComplete(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.BackgroundComplete,
    ): ReducerResult {
        val message =
            when (val label = event.data?.get("label")) {
                is String -> label
                else -> event.data?.get("name") as? String
            }?.let { "Background job finished: $it" }
                ?: "Background job finished"
        return ReducerResult(
            state = state.copy(backgroundCompleteMessage = message),
            streamingState = streamingState,
        )
    }

    // ── SessionUpdated / StatusUpdate / Unknown ───────────────────────

    private fun onSessionUpdated(
        state: ChatUiState,
        streamingState: StreamingState,
    ): ReducerResult = ReducerResult(state = state, streamingState = streamingState)

    private fun onStatusUpdate(
        state: ChatUiState,
        streamingState: StreamingState,
    ): ReducerResult = ReducerResult(state = state, streamingState = streamingState)

    private fun onUnknown(
        state: ChatUiState,
        streamingState: StreamingState,
    ): ReducerResult = ReducerResult(state = state, streamingState = streamingState)

    private fun onToolProgress(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ToolProgress,
    ): ReducerResult {
        val messages = state.messages.toMutableList()
        val toolIdx =
            messages.indexOfLast {
                it.role == MessageRole.TOOL &&
                    it.toolName == event.name &&
                    it.toolStatus == ToolStatus.RUNNING
            }
        if (toolIdx < 0) return ReducerResult(state = state, streamingState = streamingState)

        messages[toolIdx] = messages[toolIdx].copy(progressPreview = event.preview ?: "")
        return ReducerResult(
            state = state.copy(messages = messages),
            streamingState = streamingState,
        )
    }

    private fun onToolGenerating(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ToolGenerating,
    ): ReducerResult {
        // The model is writing tool arguments: the reply segment has ended.
        val working = streamingState.copy(isWritingText = false)
        val messages = state.messages.toMutableList()
        val toolIdx =
            messages.indexOfLast {
                it.role == MessageRole.TOOL &&
                    it.toolName == event.name &&
                    it.toolStatus == ToolStatus.RUNNING
            }
        if (toolIdx < 0) return ReducerResult(state = state, streamingState = working)

        messages[toolIdx] = messages[toolIdx].copy(progressPreview = "")
        return ReducerResult(
            state = state.copy(messages = messages),
            streamingState = working,
        )
    }

    private fun onSubagentEvent(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.SubagentEvent,
    ): ReducerResult {
        val goal = (event.payload?.get("goal") as? String)?.takeIf { it.isNotBlank() }
        val taskIndex = (event.payload?.get("task_index") as? Number)?.toInt()
        val taskCount = (event.payload?.get("task_count") as? Number)?.toInt()
        val text = event.payload?.get("text") as? String
        val status = event.payload?.get("status") as? String
        val summary = event.payload?.get("summary") as? String
        val durationSeconds = (event.payload?.get("duration_seconds") as? Number)?.toDouble()
        val model = event.payload?.get("model") as? String
        val childSessionId = (event.payload?.get("child_session_id") as? String)?.takeIf { it.isNotBlank() }
        val subagentId = (event.payload?.get("subagent_id") as? String)?.takeIf { it.isNotBlank() }

        val indicators = state.subagentIndicators.toMutableList()
        val idx =
            indicators
                .indexOfLast {
                    (subagentId != null && it.subagentId == subagentId) ||
                        (
                            childSessionId != null &&
                                (it.childSessionId == childSessionId || it.subagentId == childSessionId)
                        )
                }.takeIf { it >= 0 } ?: indicators.indices
                .filter { index ->
                    val existing = indicators[index]
                    !existing.isTerminal &&
                        (
                            subagentId == null || existing.subagentId == null ||
                                existing.subagentId == existing.childSessionId
                        ) &&
                        (
                            childSessionId == null || existing.childSessionId == null ||
                                existing.childSessionId == childSessionId
                        ) &&
                        (goal != null || taskIndex != null) &&
                        (goal == null || existing.goal == goal) &&
                        (taskIndex == null || existing.taskIndex == taskIndex)
                }.singleOrNull() ?: -1

        // Late progress/start frames cannot move a finished child back into the active count.
        if (idx >= 0 && indicators[idx].isTerminal && event.type != "subagent.complete") {
            return ReducerResult(state = state, streamingState = streamingState)
        }

        val existingLogs = if (idx >= 0) indicators[idx].logs else emptyList()
        val newLogs = existingLogs.toMutableList()
        if (!text.isNullOrBlank() && newLogs.lastOrNull()?.text != text) {
            newLogs.add(SubagentLogLine(text = text, isError = status == "failed"))
        }
        if (event.type == "subagent.complete" && !summary.isNullOrBlank() && newLogs.lastOrNull()?.text != summary) {
            newLogs.add(SubagentLogLine(text = summary, isSummary = true))
        }
        val trimmedLogs = newLogs.takeLast(30)

        val finalStatus =
            if (event.type == "subagent.complete") {
                status ?: "completed"
            } else {
                status ?: (if (idx >= 0) indicators[idx].status else "running")
            }

        val indicator =
            SubagentIndicator(
                type = event.type,
                goal = goal ?: (if (idx >= 0) indicators[idx].goal else null),
                taskIndex = taskIndex ?: (if (idx >= 0) indicators[idx].taskIndex else null),
                taskCount = taskCount ?: (if (idx >= 0) indicators[idx].taskCount else null),
                text = summary ?: text ?: (if (idx >= 0) indicators[idx].text else null),
                status = finalStatus,
                summary = summary ?: (if (idx >= 0) indicators[idx].summary else null),
                subagentId = subagentId ?: (if (idx >= 0) indicators[idx].subagentId else null) ?: childSessionId,
                logs = trimmedLogs,
                durationSeconds = durationSeconds ?: (if (idx >= 0) indicators[idx].durationSeconds else null),
                model = model ?: (if (idx >= 0) indicators[idx].model else null),
                childSessionId = childSessionId ?: (if (idx >= 0) indicators[idx].childSessionId else null),
            )

        if (idx >= 0) {
            indicators[idx] = indicator
        } else {
            indicators.add(indicator)
        }

        return ReducerResult(
            state = state.copy(subagentIndicators = indicators),
            streamingState = streamingState,
        )
    }

    private fun onTodoUpdated(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.TodoUpdated,
    ): ReducerResult {
        if (event.sessionId != null && state.currentSessionId != null && event.sessionId != state.currentSessionId) {
            return ReducerResult(state = state, streamingState = streamingState)
        }
        return ReducerResult(
            state = state.copy(todos = event.todos),
            streamingState = streamingState,
        )
    }

    private fun onSessionUsage(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.SessionUsage,
    ): ReducerResult {
        val snapshot =
            parseUsageSnapshot(event.data)
                ?: return ReducerResult(state = state, streamingState = streamingState)
        var updatedState = state
        if (snapshot.compressions != null) {
            updatedState = updatedState.copy(compressionCount = snapshot.compressions)
        }
        if (snapshot.contextUsed != null && snapshot.contextUsed > 0L) {
            updatedState = updatedState.copy(usedContextTokens = snapshot.contextUsed)
        }
        if (snapshot.contextMax != null && snapshot.contextMax > 0L) {
            updatedState = updatedState.copy(fullContextTokens = snapshot.contextMax)
        }
        return ReducerResult(state = updatedState, streamingState = streamingState)
    }
}

/**
 * Result of reducing one event.
 *
 * @param state The new UI state after applying the event.
 * @param streamingState The new streaming state after applying the event.
 * @param effects Side-effects the ViewModel should execute.
 */
data class ReducerResult(
    val state: ChatUiState,
    val streamingState: StreamingState = StreamingState(),
    val effects: List<ReducerEffect> = emptyList(),
)

/**
 * Side-effects that the reducer cannot perform itself (needs I/O or ViewModel context).
 */
sealed class ReducerEffect {
    data class PersistMessage(
        val message: ChatMessage,
        val sessionId: String,
    ) : ReducerEffect()

    data object CreateNewSession : ReducerEffect()

    data object LoadSessions : ReducerEffect()

    data object RefreshSessions : ReducerEffect()

    data object RefreshContextUsage : ReducerEffect()

    /**
     * Ask the ViewModel to turn any `MEDIA:<host-path>` directives in the
     * just-finalized assistant message into real [Attachment]s (images inline,
     * every other file type tappable) and strip the directives from the text.
     *
     * Why: the gateway's WebSocket stream delivers the raw `MEDIA:<path>`
     * directive (the same one the desktop app resolves via `mediaExternalUrl`
     * to the authenticated `/api/files/download` endpoint, which streams the
     * host file over HTTP). Attaching them lets the renderer show images inline
     * and offer every other file as a fetchable attachment — even on a remote
     * phone (real HTTP, unlike a host-local file read). Mirrors desktop's
     * `mediaExternalUrl`; mobile-only, backend untouched. See issue #724.
     */
    data class AttachHostMedia(
        val sessionId: String,
        val messageId: String,
    ) : ReducerEffect()
}

@Suppress("UNCHECKED_CAST")
fun extractTodosFromMap(data: Map<String, Any?>?): List<TodoItem>? {
    if (data == null) return null
    val rawList =
        (data["todos"] as? List<*>)
            ?: ((data["args"] as? Map<String, Any?>)?.get("todos") as? List<*>)
            ?: ((data["parameters"] as? Map<String, Any?>)?.get("todos") as? List<*>)
            ?: ((data["result"] as? Map<String, Any?>)?.get("todos") as? List<*>)
            ?: return null

    val items = mutableListOf<TodoItem>()
    for (elem in rawList) {
        val map = elem as? Map<String, Any?> ?: continue
        val id = (map["id"] as? String) ?: continue
        val content = (map["content"] as? String) ?: (map["text"] as? String) ?: ""
        val status = (map["status"] as? String) ?: "pending"
        val parent = (map["parent"] as? String)?.takeIf { it.isNotBlank() }
        items.add(TodoItem(id = id, content = content, status = status, parent = parent))
    }
    return items.takeIf { it.isNotEmpty() }
}

fun extractTodosFromJson(content: String): List<TodoItem>? {
    if (content.isBlank()) return null
    return try {
        val element = OkHttpProvider.json.parseToJsonElement(content)
        val obj = element as? JsonObject ?: return null
        val todosArray =
            (obj["todos"] as? JsonArray)
                ?: ((obj["args"] as? JsonObject)?.get("todos") as? JsonArray)
                ?: ((obj["result"] as? JsonObject)?.get("todos") as? JsonArray)
                ?: ((obj["parameters"] as? JsonObject)?.get("todos") as? JsonArray)
                ?: return null

        todosArray
            .mapNotNull { item ->
                val itemObj = item as? JsonObject ?: return@mapNotNull null
                val id = (itemObj["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val contentStr =
                    (itemObj["content"] as? JsonPrimitive)?.content
                        ?: (itemObj["text"] as? JsonPrimitive)?.content ?: ""
                val status = (itemObj["status"] as? JsonPrimitive)?.content ?: "pending"
                val parent = (itemObj["parent"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                TodoItem(id = id, content = contentStr, status = status, parent = parent)
            }.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}

fun hydrateTodosFromMessages(messages: List<ChatMessage>): List<TodoItem> {
    for (msg in messages.asReversed()) {
        if (msg.role == MessageRole.TOOL && (msg.toolName == "todo" || msg.content.contains("\"todos\""))) {
            val todos = extractTodosFromJson(msg.content)
            if (!todos.isNullOrEmpty()) {
                return todos
            }
        }
    }
    return emptyList()
}
