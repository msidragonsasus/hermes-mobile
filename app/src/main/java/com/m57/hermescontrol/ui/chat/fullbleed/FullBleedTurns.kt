package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.ui.chat.ApprovalPlacement
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.brainstorm.BrainstormParser
import com.m57.hermescontrol.ui.chat.isApprovalTimelineRow
import com.m57.hermescontrol.ui.chat.isLivePreviewOf

/**
 * Turn model for the full-bleed chat renderer (issue #866).
 *
 * A turn is a unit of conversation for spacing + header purposes:
 * - [ChatTurn.User]: one user message — always its own turn (bubble anchor).
 * - [ChatTurn.Agent]: everything between user messages — assistant prose,
 *   tool rows, and system events, in original order.
 */
sealed interface ChatTurn {
    /** User message — always its own turn (bubble anchor). */
    data class User(
        val message: ChatMessage,
    ) : ChatTurn

    /** One agent turn: prose, tool rows, and system events in order. */
    data class Agent(
        val entries: List<AgentEntry>,
    ) : ChatTurn
}

sealed interface AgentEntry {
    data class Prose(
        val message: ChatMessage,
    ) : AgentEntry

    data class ToolRow(
        val message: ChatMessage,
    ) : AgentEntry

    data class ConnectionEvents(
        val messages: List<ChatMessage>,
    ) : AgentEntry

    data class SystemEvent(
        val message: ChatMessage,
    ) : AgentEntry
}

/**
 * Stable content prefix the backend uses for its max-iterations runtime nudge
 * (`handle_max_iterations` in run_agent.py → chat_completion_helpers.py, text
 * sourced from `agent.context_compressor.MAX_ITERATIONS_SUMMARY_REQUEST`). The
 * backend persists it as a plain `role="user"` row with NO `display_kind`
 * (SessionDB projection strips underscore metadata flags), so on the mobile
 * side it would otherwise render as a fake user bubble. Treat it as a system
 * event so it gets the distinct system design instead.
 */
private const val MAX_ITERATIONS_SYSTEM_MARKER =
    "You've reached the maximum number of tool-calling iterations allowed."

private fun ChatMessage.isModelSwitchControl(): Boolean =
    role == MessageRole.USER && (displayKind == "model_switch" || displayKind == "model_switch_request")

private fun ChatMessage.isSyntheticSystemRow(): Boolean =
    role == MessageRole.USER &&
        displayKind == null &&
        content.startsWith(MAX_ITERATIONS_SYSTEM_MARKER)

/**
 * Split a flat message list into turns for the full-bleed renderer.
 *
 * Each USER message closes the current agent turn (if any) and opens a User
 * turn; all non-user messages belong to the surrounding agent turn. A new
 * agent turn starts after each user turn or at the start of the list.
 */
fun groupIntoTurns(
    messages: List<ChatMessage>,
    ledger: com.m57.hermescontrol.ui.chat.ResponseLedger =
        com.m57.hermescontrol.ui.chat
            .ResponseLedger(),
): List<ChatTurn> = groupIntoTurnsForPresentation(messages, ledger)

private fun groupIntoTurnsForPresentation(
    messages: List<ChatMessage>,
    ledger: com.m57.hermescontrol.ui.chat.ResponseLedger,
    presentationOwners: Map<String, String> = emptyMap(),
): List<ChatTurn> {
    val turns = mutableListOf<ChatTurn>()
    val agentEntries = mutableListOf<AgentEntry>()
    val owners =
        ledger.responses
            .flatMap { response ->
                (response.messageIds + response.userIds).map { it to response.id }
            }.toMap() + ledger.approvalPlacements.mapValues { it.value.responseId } + presentationOwners
    val (ordered, recoveredOwners) = recoverActivityOrder(messages, ledger.toolAnswerIds, owners)
    var previousOwner: String? = null

    fun flushAgent() {
        if (agentEntries.isNotEmpty()) {
            turns += ChatTurn.Agent(compactConnectionEvents(placeApprovals(agentEntries, ledger.approvalPlacements)))
            agentEntries.clear()
        }
    }

    ordered.forEach { message ->
        // A local configuration control has no response owner of its own.
        // Keep its row without treating the absent owner as a new response.
        val owner =
            owners[message.id] ?: recoveredOwners[message.id]
                ?: previousOwner.takeIf { message.isModelSwitchControl() || message.isRoutineConnectionEvent() }
        if (owner != previousOwner && (owner != null || previousOwner != null)) flushAgent()
        previousOwner = owner
        when {
            // Timeline markers (display_kind) ride as role=user rows but are
            // NOT user turns — group them as system-style timeline entries so
            // they render as centered chips, not fake user bubbles (issue #904).
            // The backend's max-iterations nudge is a role=user row with NO
            // display_kind (it's stripped on persistence); detect it by its
            // stable content prefix and route it as a system event too, tagging
            // it so the timeline chip can name it.
            message.displayKind != null -> {
                agentEntries += AgentEntry.SystemEvent(message)
            }

            message.isSyntheticSystemRow() -> {
                agentEntries +=
                    AgentEntry.SystemEvent(message.copy(displayKind = "max_iterations_reached"))
            }

            message.role == MessageRole.USER -> {
                flushAgent()
                turns += ChatTurn.User(message)
            }

            MessageRole.ASSISTANT == message.role -> {
                agentEntries += AgentEntry.Prose(message)
            }

            MessageRole.TOOL == message.role -> {
                agentEntries += AgentEntry.ToolRow(message)
            }

            MessageRole.SYSTEM == message.role -> {
                agentEntries += AgentEntry.SystemEvent(message)
            }
        }
    }
    flushAgent()
    return turns
}

/**
 * A local approval or reconnect notice can split a recovered tool run. Treat those rows
 * as part of the activity only when the tools point to the same nearby answer.
 */
private fun recoverActivityOrder(
    messages: List<ChatMessage>,
    toolAnswerIds: Map<String, String>,
    owners: Map<String, String>,
): Pair<List<ChatMessage>, Map<String, String>> {
    if (toolAnswerIds.isEmpty()) return messages to emptyMap()
    val recoveredOwners = mutableMapOf<String, String>()
    val movedAnswers = mutableMapOf<Int, ChatMessage>()
    val movedIds = mutableSetOf<String>()
    var start = 0
    while (start < messages.size) {
        if (!messages[start].isActivityRow()) {
            start++
            continue
        }
        var end = start + 1
        while (end < messages.size && messages[end].isActivityRow()) end++
        val activity = messages.subList(start, end)
        val linkedAnswers = activity.mapNotNull { toolAnswerIds[it.id] }.toSet()
        val answer =
            listOfNotNull(messages.getOrNull(start - 1), messages.getOrNull(end)).singleOrNull {
                it.role == MessageRole.ASSISTANT && it.displayKind == null && it.id in linkedAnswers
            }
        val owner = answer?.id?.let(owners::get)
        if (answer != null && owner != null &&
            activity.all { row ->
                val knownOwner = owners[row.id]
                when {
                    knownOwner != null -> knownOwner == owner
                    row.role == MessageRole.TOOL -> toolAnswerIds[row.id] == answer.id
                    else -> true
                }
            }
        ) {
            activity.forEach { recoveredOwners[it.id] = owner }
            if (answer == messages.getOrNull(start - 1)) {
                movedIds += answer.id
                movedAnswers[end - 1] = answer
            }
        }
        start = end
    }
    val ordered =
        if (movedIds.isEmpty()) {
            messages
        } else {
            buildList {
                messages.forEachIndexed { index, row ->
                    if (row.id !in movedIds) add(row)
                    movedAnswers[index]?.let(::add)
                }
            }
        }
    return ordered to recoveredOwners
}

private fun ChatMessage.isActivityRow(): Boolean =
    (role == MessageRole.TOOL && displayKind == null) || isApprovalTimelineRow() || isRoutineConnectionEvent()

/** Keep a local approval beside its tool when a complete REST manifest replaces the live rows. */
private fun placeApprovals(
    entries: List<AgentEntry>,
    placements: Map<String, ApprovalPlacement>,
): List<AgentEntry> {
    if (placements.isEmpty()) return entries
    val tools =
        entries.filterIsInstance<AgentEntry.ToolRow>().groupingBy { it.message.toolCallId }.eachCount()
    val anchored =
        entries.filterIsInstance<AgentEntry.SystemEvent>().mapNotNull { entry ->
            placements[entry.message.id]?.afterToolCallId?.takeIf { tools[it] == 1 }?.let { it to entry }
        }
    if (anchored.isEmpty()) return entries
    val byTool = anchored.groupBy({ it.first }, { it.second })
    val moved = anchored.mapTo(HashSet()) { it.second }
    return buildList {
        for (entry in entries) {
            if (entry in moved) continue
            add(entry)
            if (entry is AgentEntry.ToolRow) addAll(byTool[entry.message.toolCallId].orEmpty())
        }
    }
}

/**
 * Like [groupIntoTurns] but folds the in-flight streaming assistant message
 * into the current agent turn, so it renders as part of the turn (reasoning
 * hoist, turn headers, spacing) instead of as a detached tail item.
 *
 * Defensive: if the streaming message's id is already present in [messages]
 * (commit race — the message landed while the UI still held the streaming
 * copy), it is not appended again; a duplicate prose entry would produce a
 * LazyColumn duplicate-key crash.
 */
fun groupIntoTurnsWithStreaming(
    messages: List<ChatMessage>,
    streamingMessage: ChatMessage?,
    ledger: com.m57.hermescontrol.ui.chat.ResponseLedger =
        com.m57.hermescontrol.ui.chat
            .ResponseLedger(),
): List<ChatTurn> {
    val historyAnswer = messages.firstOrNull { it.id == ledger.activeHistoryAnswerId }
    val showsLivePreview = historyAnswer != null && streamingMessage?.isLivePreviewOf(historyAnswer) == true
    val visible = if (showsLivePreview) messages.filterNot { it.id == historyAnswer.id } else messages
    val rows =
        if (streamingMessage == null || visible.any { it.id == streamingMessage.id }) {
            visible
        } else {
            visible + streamingMessage
        }
    // The UI ledger does not yet own the live segment. Infer adjacency only for
    // presentation; observing it in the ledger here would also grant copy authority.
    val active = ledger.responses.singleOrNull { it.id == ledger.activeId }
    val tail =
        visible.lastOrNull { message ->
            (!message.isModelSwitchControl() && !message.isRoutineConnectionEvent()) ||
                ledger.responses.any { message.id in it.messageIds || message.id in it.userIds }
        }
    val tailOwners =
        ledger.responses.filter {
            tail?.id in it.messageIds || tail?.id in it.userIds ||
                ledger.approvalPlacements[tail?.id]?.responseId == it.id
        }
    val liveOwner =
        active?.takeIf {
            streamingMessage != null &&
                streamingMessage.isStreaming && streamingMessage.role == MessageRole.ASSISTANT &&
                messages.none { row -> row.id == streamingMessage.id } &&
                ledger.responses.none { response ->
                    streamingMessage.id in response.messageIds || streamingMessage.id in response.userIds
                } &&
                it.finishedAt == null && it.authority.terminals.isEmpty() &&
                tail != null && tail.role != MessageRole.USER && tailOwners.singleOrNull()?.id == it.id
        }
    val presentationOwners =
        buildMap {
            if (active != null && historyAnswer != null) put(historyAnswer.id, active.id)
            if (streamingMessage != null && (showsLivePreview || liveOwner != null)) {
                (liveOwner ?: active)?.let { put(streamingMessage.id, it.id) }
            }
        }
    return groupIntoTurnsForPresentation(rows, ledger, presentationOwners)
}

/**
 * Map every visible chat message id to its LazyColumn item index in the
 * full-bleed renderer.
 *
 * The lazy list does NOT have one item per message: each agent turn with a
 * reasoning block emits an extra `reasoning-<id>` item BEFORE its prose, and
 * tool rows / system events are items too. Scrolling a search match by raw
 * message index therefore lands on the WRONG item whenever reasoning or tool
 * rows precede the target — the classic "match is above/below the view" bug.
 *
 * Mirrors the item emission order in [FullBleedChatList] exactly:
 * user turn → 1 item; agent turn → optional activity row, expanded reasoning
 * rows, visible entries, then one footer when [replyFooters] has a non-null value
 * at that turn index. [leadingItems] accounts for fixed items emitted before
 * the turns (e.g. the `loading-older` spinner when paging). The default empty
 * footer plan preserves callers that do not emit response footer rows.
 *
 * @return messageId → LazyColumn item index of the message's content item
 *   (prose items for agent messages; user items for user messages).
 */
fun messageIdToLazyIndex(
    turns: List<ChatTurn>,
    leadingItems: Int = 0,
    expandedTurns: Set<String> = emptySet(),
    replyFooters: List<ReplyFooter?> = emptyList(),
): Map<String, Int> {
    val map = mutableMapOf<String, Int>()
    val duplicateBrainstormIds = BrainstormParser.duplicateBrainstormMessageIds(turns)
    var itemIndex = leadingItems
    turns.forEachIndexed { turnIndex, turn ->
        when (turn) {
            is ChatTurn.User -> {
                map[turn.message.id] = itemIndex
                itemIndex++
            }

            is ChatTurn.Agent -> {
                val hasActivity = turn.hasActivity()
                val expanded = hasActivity && turn.tabKey() in expandedTurns
                if (hasActivity) itemIndex++
                if (expanded) {
                    itemIndex +=
                        turn.entries.filterIsInstance<AgentEntry.Prose>().count {
                            it.message.reasoningText.isNotBlank()
                        }
                }
                turn.entries.forEach { entry ->
                    if (entry is AgentEntry.ToolRow && entry.message.id in duplicateBrainstormIds) return@forEach
                    if (!entry.visibleWhen(expanded)) return@forEach
                    when (entry) {
                        is AgentEntry.Prose -> {
                            map[entry.message.id] = itemIndex
                            itemIndex++
                        }

                        is AgentEntry.ToolRow -> {
                            if (BrainstormParser.isBrainstormToolMessage(entry.message)) {
                                map[entry.message.id] = itemIndex
                            }
                            itemIndex++
                        }

                        is AgentEntry.SystemEvent -> {
                            map[entry.message.id] = itemIndex
                            itemIndex++
                        }

                        // Routine connection notices have no rendered item or search destination.
                        // The original entries remain intact for lifecycle and tool bookkeeping.
                        is AgentEntry.ConnectionEvents -> {}
                    }
                }
                if (replyFooters.getOrNull(turnIndex) != null) itemIndex++
            }
        }
    }
    return map
}

/**
 * Resolve the message id of the CURRENT search match once, so per-item
 * highlight lookups are O(1) id comparisons instead of O(n) linear scans
 * (`messages.indexOfFirst` per rendered bubble was O(n²) per search update).
 */
fun currentMatchMessageId(
    messages: List<ChatMessage>,
    searchMatchIndices: List<Int>,
    currentSearchMatchIndex: Int,
): String? {
    if (currentSearchMatchIndex < 0 || currentSearchMatchIndex >= searchMatchIndices.size) return null
    val messageIndex = searchMatchIndices[currentSearchMatchIndex]
    if (messageIndex < 0 || messageIndex >= messages.size) return null
    return messages[messageIndex].id
}

/**
 * Message ids that contain at least one search match. Bubbles outside this
 * set skip their highlight scan entirely (they used to re-run it on every
 * search-state change even with zero hits).
 */
fun matchedMessageIds(
    messages: List<ChatMessage>,
    searchMatchIndices: List<Int>,
): Set<String> =
    searchMatchIndices
        .mapNotNull { messages.getOrNull(it)?.id }
        .toSet()

/** Fold only adjacent, known benign connection events. Keep every original message. */
internal fun compactConnectionEvents(entries: List<AgentEntry>): List<AgentEntry> {
    val result = mutableListOf<AgentEntry>()
    val connectionEvents = mutableListOf<ChatMessage>()

    fun flush() {
        if (connectionEvents.isNotEmpty()) {
            result += AgentEntry.ConnectionEvents(connectionEvents.toList())
            connectionEvents.clear()
        }
    }
    entries.forEach { entry ->
        val message = (entry as? AgentEntry.SystemEvent)?.message
        if (message?.isRoutineConnectionEvent() == true) {
            connectionEvents += message
        } else {
            flush()
            result += entry
        }
    }
    flush()
    return result
}

private fun ChatMessage.isRoutineConnectionEvent(): Boolean =
    role == MessageRole.SYSTEM && (content == "Connected to Hermes" || content == "Session resumed") &&
        approvalInfo == null && clarifyInfo == null && displayKind == null && attachments.isNullOrEmpty()
