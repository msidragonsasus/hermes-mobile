package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull

data class GroupTurnEvidence(
    val delivery: GroupDelivery,
    val reply: String? = null,
)

/** Absence, partial pages and tool-call rows never prove a failure or completion. */
internal fun reconcileGroupTurn(
    turn: GroupTurnRecord,
    history: List<SessionMessage>,
): GroupTurnEvidence {
    val matches =
        history.indices.filter {
            history[it].role == "user" &&
                groupPromptHash(history[it].contentText) == turn.promptHash
        }
    val requestIndex = matches.singleOrNull() ?: return GroupTurnEvidence(GroupDelivery.UNKNOWN)
    val response = history.drop(requestIndex + 1).takeWhile { it.role != "user" }.lastOrNull()
    val calls = response?.tool_calls
    return if (response?.role == "assistant" && response.contentText.isNotBlank() &&
        (calls == null || calls == JsonNull || (calls is JsonArray && calls.isEmpty()))
    ) {
        GroupTurnEvidence(GroupDelivery.COMPLETE, response.contentText)
    } else {
        GroupTurnEvidence(GroupDelivery.ACCEPTED)
    }
}
