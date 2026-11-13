package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatUiState
import com.m57.hermescontrol.ui.chat.ReplyAuthorityDecision
import com.m57.hermescontrol.ui.chat.ResponseLedger
import com.m57.hermescontrol.ui.chat.StreamingState
import com.m57.hermescontrol.ui.chat.authorityDecision
import com.m57.hermescontrol.ui.chat.awaitsUserInput
import com.m57.hermescontrol.ui.chat.blocksAuthority

/** One proven response, projected onto the existing footer without changing its design. */
data class ReplyFooter(
    val text: String,
    val finishedAt: Long,
    val responseId: String = "",
)

data class ReplyLifecycle(
    val responseEnded: Boolean = false,
    val activeMessageIds: Set<String> = emptySet(),
    val finalizedMessageId: String? = null,
    val boundariesReady: Boolean = true,
    val ledger: ResponseLedger = ResponseLedger(),
)

internal fun replyLifecycle(
    state: ChatUiState,
    streaming: StreamingState,
): ReplyLifecycle =
    ReplyLifecycle(
        ledger =
            if (state.awaitsUserInput()) {
                state.responseLedger.copy(
                    responses = state.responseLedger.responses.filterNot { it.id == state.responseLedger.activeId },
                )
            } else {
                state.responseLedger
            },
    )

internal fun List<ChatTurn>.replyFooters(lifecycle: ReplyLifecycle): List<ReplyFooter?> {
    val result = MutableList<ReplyFooter?>(size) { null }
    val blockedGroups = mutableSetOf<Int>()
    val ambiguousIds =
        lifecycle.ledger.responses
            .flatMap { it.messageIds }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    val entries =
        flatMapIndexed { group, turn ->
            (turn as? ChatTurn.Agent)?.entries.orEmpty().flatMap { entry ->
                when (entry) {
                    is AgentEntry.Prose -> listOf(group to entry.message)
                    is AgentEntry.ToolRow -> listOf(group to entry.message)
                    is AgentEntry.SystemEvent -> listOf(group to entry.message)
                    is AgentEntry.ConnectionEvents -> entry.messages.map { group to it }
                }
            }
        }
    // A duplicate physical ID is not a canonical lookup result. Keep its key
    // materialized, but retain no arbitrary first/last entry for that ID.
    val uniqueEntries = HashMap<String, Pair<Int, ChatMessage>?>()
    for (entry in entries) {
        val id = entry.second.id
        uniqueEntries[id] = if (uniqueEntries.containsKey(id)) null else entry
    }
    val materializedIds =
        (uniqueEntries.keys + filterIsInstance<ChatTurn.User>().map { it.message.id })
            .toSet()
    for (response in lifecycle.ledger.responses) {
        val time =
            response.authority.terminals
                .firstOrNull()
                ?.observedAt ?: continue
        val decision = response.authorityDecision(materializedIds, lifecycle.ledger.blocksAuthority(response.id))
        if (!decision.copyReady || response.messageIds.any { it in ambiguousIds }) continue
        val canonicalOrder =
            response.authority.canonical
                ?.memberIds
                .orEmpty()
        val visible =
            if (decision == ReplyAuthorityDecision.ReadyCanonical) {
                canonicalOrder.mapNotNull { id -> uniqueEntries[id] }
            } else {
                val memberIds = response.messageIds.toHashSet()
                val userIds = response.userIds.toHashSet()
                entries.filter {
                    it.second.id in memberIds ||
                        (it.second.id in userIds && it.second.displayKind != null)
                }
            }
        if (decision != ReplyAuthorityDecision.ReadyCanonical) {
            val visibleIds = visible.mapTo(HashSet()) { it.second.id }
            if (!visibleIds.containsAll(response.messageIds)) continue
        }
        val answers =
            visible.filter {
                it.second.role == com.m57.hermescontrol.ui.chat.MessageRole.ASSISTANT &&
                    it.second.content.isNotBlank()
            }
        if (answers.isEmpty()) continue
        val group = visible.last().first
        // Two replies in one presentation group cannot share one visible footer slot safely.
        if (group in blockedGroups) continue
        if (result[group] != null) {
            result[group] = null
            blockedGroups += group
            continue
        }
        result[group] = ReplyFooter(answers.joinToString("\n\n") { it.second.content }, time, response.id)
    }
    return result
}
