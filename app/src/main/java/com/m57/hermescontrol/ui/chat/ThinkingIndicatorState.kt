package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.domain.hermes.AttentionStatus

/**
 * Whether the conversation tail shows the 「思考中」 indicator.
 *
 * Shown while the turn is authoritatively active ([ChatUiState.isAgentTyping],
 * set on send, on message.start/delta and on later work frames of an unfinished
 * turn) and the latest activity is not reply text. Hidden while the user must
 * answer a prompt, while the connection or the response status is unknown, and
 * after message.complete/done, interrupt or a session switch clear the turn;
 * an ended turn stays hidden until a legitimate send/start opens a new one.
 * Never carries reasoning text: the label is fixed.
 */
internal fun showsThinkingIndicator(
    state: ChatUiState,
    streaming: StreamingState,
): Boolean =
    state.isAgentTyping && state.isConnected && !state.responseStatusUnknown &&
        !streaming.turnEnded && !streaming.isWritingText && !state.awaitsUserInput()

private val resolvedAttention = setOf(AttentionStatus.CONFIRMED, AttentionStatus.EXPIRED)

/**
 * True while the current turn waits for the user: a clarification, sudo,
 * secret or vault prompt, or an approval that is not yet confirmed or expired.
 */
internal fun ChatUiState.awaitsUserInput(): Boolean {
    if (clarifyRequest != null || sudoPrompt != null || secretPrompt != null || vaultPrompt != null) return true
    val turn = messages.drop(messages.indexOfLast { it.role == MessageRole.USER } + 1)
    return turn.any { message ->
        message.approvalInfo?.let { it.status !in resolvedAttention } == true ||
            message.clarifyInfo?.let { clarify ->
                clarify.questionStates.isEmpty() || clarify.questionStates.values.any { it !in resolvedAttention }
            } == true
    }
}
