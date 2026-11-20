package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.toolCallMilestones

internal data class RenderPlan(
    val turns: List<ChatTurn>,
    val replyFooters: List<ReplyFooter?>,
    val messageIdToLazyIndex: Map<String, Int>,
    val milestones: Map<Int, Int>,
)

/**
 * One composition's planning cache. Producers must publish immutable history/ledger snapshots.
 * Misses use the original pipeline; only an ordinary live assistant's content can change on a hit.
 * The fixed full-plan anchor and current output are retained, never a chain of streamed outputs.
 * Scope equality must include session/generation and selected/configured connection identities.
 */
internal class RenderPlanningCache {
    private var entry: Entry? = null

    fun clear() {
        entry = null
    }

    fun plan(
        messages: List<ChatMessage>,
        streamingMessage: ChatMessage?,
        lifecycle: ReplyLifecycle,
        scope: Any,
        leadingItems: Int = 0,
        expandedTurns: Set<String> = emptySet(),
    ): RenderPlan {
        val cached = entry
        if (cached != null && cached.scope != scope) clear()
        if (cached != null && entry === cached &&
            cached.messages === messages && cached.lifecycle.ledger === lifecycle.ledger &&
            cached.lifecycle == lifecycle && cached.leadingItems == leadingItems &&
            cached.expandedTurns == expandedTurns
        ) {
            if (cached.currentStreaming === streamingMessage) return cached.current
            if (streamingMessage != null && cached.liveTemplate != null &&
                streamingMessage.copy(content = "") == cached.liveTemplate
            ) {
                val agentIndex = cached.liveTurnIndex
                if (agentIndex >= 0) {
                    val agent = cached.anchor.turns[agentIndex] as ChatTurn.Agent
                    val prose = agent.entries.last() as AgentEntry.Prose
                    val replacement = AgentEntry.Prose(streamingMessage)
                    val replacementAgent =
                        ChatTurn.Agent(
                            ReplaceAt(agent.entries, agent.entries.lastIndex, replacement),
                        )
                    val nextTurns = ReplaceAt(cached.anchor.turns, agentIndex, replacementAgent)
                    val indices =
                        if (replacement.visibleWhen(false) == prose.visibleWhen(false)) {
                            cached.anchor.messageIdToLazyIndex
                        } else {
                            // The first visible token adds a real item where an empty stream had none.
                            messageIdToLazyIndex(nextTurns, leadingItems, expandedTurns, cached.anchor.replyFooters)
                        }
                    val output = cached.anchor.copy(turns = nextTurns, messageIdToLazyIndex = indices)
                    cached.currentStreaming = streamingMessage
                    cached.current = output
                    return output
                }
            }
        }

        val turns = groupIntoTurnsWithStreaming(messages, streamingMessage, lifecycle.ledger)
        val footers = turns.replyFooters(lifecycle)
        val output =
            RenderPlan(
                turns = turns,
                replyFooters = footers,
                messageIdToLazyIndex = messageIdToLazyIndex(turns, leadingItems, expandedTurns, footers),
                milestones = toolCallMilestones(messages),
            )
        // Build membership only on misses, including terminals that are not currently copy-ready.
        val historyIds = messages.mapTo(HashSet()) { it.id }
        val terminalIds = HashSet<String>()
        lifecycle.ledger.responses.forEach { response ->
            if (response.authority.terminals.isNotEmpty()) {
                terminalIds.addAll(response.messageIds)
                terminalIds.addAll(response.userIds)
                terminalIds.addAll(
                    response.authority.canonical
                        ?.memberIds
                        .orEmpty(),
                )
            }
        }
        val liveTemplate =
            streamingMessage
                ?.takeIf {
                    it.role == MessageRole.ASSISTANT && it.displayKind == null && it.isStreaming &&
                        lifecycle.ledger.activeHistoryAnswerId == null &&
                        it.id !in historyIds && it.id !in terminalIds
                }?.copy(content = "")
        entry = Entry(messages, streamingMessage, lifecycle, scope, leadingItems, expandedTurns, output, liveTemplate)
        return output
    }

    private class Entry(
        val messages: List<ChatMessage>,
        val anchorStreaming: ChatMessage?,
        val lifecycle: ReplyLifecycle,
        val scope: Any,
        val leadingItems: Int,
        val expandedTurns: Set<String>,
        val anchor: RenderPlan,
        val liveTemplate: ChatMessage?,
    ) {
        // Resolve exact object ownership once. ID/value equality is insufficient at commit races.
        val liveTurnIndex =
            if (liveTemplate == null) {
                -1
            } else {
                anchor.turns.indexOfLast { turn ->
                    val agent = turn as? ChatTurn.Agent ?: return@indexOfLast false
                    val prose = agent.entries.lastOrNull() as? AgentEntry.Prose ?: return@indexOfLast false
                    prose.message === anchorStreaming
                }
            }
        var currentStreaming = anchorStreaming
        var current = anchor
    }

    /** The replacement is allocated once by the caller; get/iterator share its identity. */
    private class ReplaceAt<T>(
        private val anchor: List<T>,
        private val index: Int,
        private val replacement: T,
    ) : AbstractList<T>(),
        RandomAccess {
        override val size: Int get() = anchor.size

        override fun get(index: Int): T = if (index == this.index) replacement else anchor[index]
    }
}
