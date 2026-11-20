package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.data.model.BrainstormSnapshot
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.ResponseLedger
import com.m57.hermescontrol.ui.chat.RichAnswerPrompt
import com.m57.hermescontrol.ui.chat.SPECIALIST_SETUP_REVIEW
import com.m57.hermescontrol.ui.chat.brainstorm.BrainstormParser
import com.m57.hermescontrol.ui.chat.components.messageActionText
import com.m57.hermescontrol.ui.chat.components.parseQuotedReply
import com.m57.hermescontrol.ui.chat.isApprovalTimelineRow
import com.m57.hermescontrol.ui.chat.nativeUiKey

internal data class MainChatRow(
    val key: String,
    val message: ChatMessage? = null,
    val live: Boolean = false,
    val nativeIdentity: String? = null,
    val reply: ReplyFooter? = null,
    val brainstorm: BrainstormSnapshot? = null,
)

internal const val MAIN_CHAT_WAITING_KEY = "main-chat-waiting"

/** A lazy slot changes only when live prose first appears, commits or moves to a new segment. */
internal fun ChatMessage.mainChatStreamingSlot(): ChatMessage? =
    if (displayKind == null && role == MessageRole.ASSISTANT &&
        (content.isNotBlank() || !attachments.isNullOrEmpty())
    ) {
        ChatMessage(id = id, role = role, content = "", timestamp = 0L, isStreaming = isStreaming)
    } else {
        null
    }

internal fun ChatMessage.isMainChatSearchable(): Boolean =
    when (role) {
        MessageRole.USER -> (displayKind == null && !isSyntheticSystemRow()) || displayKind == "steer"
        MessageRole.ASSISTANT -> displayKind == null && content.isNotBlank()
        else -> false
    }

/**
 * Use the canonical turn planner to align picks from the transcript with their user prompts.
 * Build turns in reading order, then return newest-first lazy indices for the reversed column.
 * The live slot carries identity only. The visible lazy item reads the latest text itself.
 */
internal fun planMainChatTimeline(
    messages: List<ChatMessage>,
    streamingSlot: ChatMessage?,
    ledger: ResponseLedger,
    waitingForFirstText: Boolean,
    allowReplyActions: Boolean = true,
    showsLivePreview: Boolean = false,
    brainstormsById: Map<String, BrainstormSnapshot> = emptyMap(),
): List<MainChatRow> {
    val turns = groupIntoTurnsWithStreaming(messages, streamingSlot, ledger, showsLivePreview)
    val duplicateBrainstormIds = BrainstormParser.duplicateBrainstormMessageIds(turns)
    val mergedBrainstorms = BrainstormParser.collectMergedFromMessages(messages, brainstormsById)
    val actionLedger =
        if (allowReplyActions) {
            ledger
        } else {
            ledger.copy(
                responses = ledger.responses.filterNot { it.id == ledger.activeId },
            )
        }
    val footers = turns.replyFooters(ReplyLifecycle(ledger = actionLedger))
    val waitingAfter =
        if (waitingForFirstText && streamingSlot == null) {
            activeTurnIndexForThinking(turns, ledger) ?: turns.lastIndex
        } else {
            null
        }
    return buildList {
        var nativeTurnAnchor = "leading"
        turns.forEachIndexed { index, turn ->
            when (turn) {
                is ChatTurn.User -> {
                    val message = turn.message
                    nativeTurnAnchor =
                        nativeUiKey(message.timestamp.toString(), RichAnswerPrompt.visibleText(message.content))
                    add(MainChatRow("user-${message.id}", message))
                }

                is ChatTurn.Agent -> {
                    var proseIndex = 0
                    for (entry in turn.entries) {
                        when (entry) {
                            is AgentEntry.Prose -> {
                                val message = entry.message
                                val nativeIdentity = nativeUiKey(nativeTurnAnchor, (proseIndex++).toString())
                                val live = message === streamingSlot
                                if (live || message.content.isNotBlank() || !message.attachments.isNullOrEmpty()) {
                                    add(
                                        MainChatRow(
                                            "prose-${message.id}",
                                            message,
                                            live,
                                            nativeIdentity,
                                            footers[index],
                                        ),
                                    )
                                }
                            }

                            is AgentEntry.SystemEvent -> {
                                val message = entry.message
                                if (message.role == MessageRole.USER && message.displayKind == "steer") {
                                    add(MainChatRow("user-${message.id}", message))
                                } else if (message.id == SPECIALIST_SETUP_REVIEW || message.isApprovalTimelineRow() ||
                                    message.clarifyInfo != null
                                ) {
                                    add(MainChatRow("sys-${message.id}", message))
                                }
                            }

                            is AgentEntry.ToolRow -> {
                                val message = entry.message
                                if (message.id !in duplicateBrainstormIds) {
                                    val snapshot = BrainstormParser.resolveForToolMessage(message, mergedBrainstorms)
                                    if (snapshot != null) {
                                        add(MainChatRow("brainstorm-" + message.id, message, brainstorm = snapshot))
                                    }
                                }
                            }

                            is AgentEntry.ConnectionEvents -> {
                                continue
                            }
                        }
                    }
                }
            }
            if (waitingAfter == index) add(MainChatRow(MAIN_CHAT_WAITING_KEY))
        }
        if (waitingAfter == -1) add(MainChatRow(MAIN_CHAT_WAITING_KEY))
    }.asReversed()
}

private data class QuotedMessageSource(
    val identity: String,
    val index: Int,
)

/** The wire quote has no source ID. Resolve it against unambiguous earlier messages only. */
internal fun List<MainChatRow>.resolveQuotedReplySources(): Map<String, Int> {
    val quotes = mutableMapOf<String, String>()
    for (row in this) {
        val message = row.message?.takeIf { it.role == MessageRole.USER } ?: continue
        parseQuotedReply(message.content)?.let { quotes[row.key] = it.quote }
    }
    if (quotes.isEmpty()) return emptyMap()

    val wanted = quotes.values.toHashSet()
    val sources = mutableMapOf<String, QuotedMessageSource?>()
    val resolved = mutableMapOf<String, Int>()

    fun indexText(
        text: String,
        source: QuotedMessageSource,
    ) {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized !in wanted) return
        val previous = sources[normalized]
        if (previous?.identity == source.identity) return
        sources[normalized] = if (normalized in sources) null else source
    }

    for (index in indices.reversed()) {
        val row = this[index]
        val message = row.message ?: continue
        quotes[row.key]?.let { sources[it] }?.let { resolved[row.key] = it.index }
        if (row.live || message.isStreaming ||
            (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT)
        ) {
            continue
        }
        val source = QuotedMessageSource(row.reply?.responseId?.takeIf { it.isNotBlank() } ?: message.id, index)
        indexText(messageActionText(message), source)
        row.reply?.let { indexText(it.text, source) }
    }
    return resolved
}
