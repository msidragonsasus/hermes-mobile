package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalChatFontScale
import com.m57.hermescontrol.ui.chat.ChatBubble
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatSearchState
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.ClarifyUi
import com.m57.hermescontrol.ui.chat.ImageViewerModel
import com.m57.hermescontrol.ui.chat.LocalNativeUiMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.NativeUiProtocol
import com.m57.hermescontrol.ui.chat.RichAnswerPrompt
import com.m57.hermescontrol.ui.chat.ToolCallDivider
import com.m57.hermescontrol.ui.chat.components.ChatScrollController
import com.m57.hermescontrol.ui.chat.components.ClarifyBubble
import com.m57.hermescontrol.ui.chat.components.ReasoningCard
import com.m57.hermescontrol.ui.chat.components.ThinkingIndicator
import com.m57.hermescontrol.ui.chat.nativeUiKey
import com.m57.hermescontrol.ui.chat.toolCallMilestones
import com.m57.hermescontrol.ui.common.EmptyState
import kotlinx.coroutines.flow.first

/**
 * The chat message list for FULL-BLEED style (issue #866) — the single chat
 * surface since the bubble renderer was removed. User messages keep their
 * bubble (the universal anchor), agent turns render full-bleed with a turn
 * header, and tool rows / system events render as distinct compact cards.
 * Spacing contract:
 * - intra-turn: entries separated by 6.dp (Column padding on agent turn items)
 * - inter-turn: 12.dp bottom padding after each turn's last item
 *
 * COMPOSE GOTCHA (verified): LazyColumn `item {}` content lambdas execute
 * LAZILY at item-composition time, not during this DSL-building loop. Loop
 * locals that are read inside item lambdas must be captured as immutable
 * vals FIRST (eagerly), or every item sees the loop's final value — the
 * turn header would never render and milestones would be misindexed.
 */
@Composable
fun FullBleedChatList(
    messages: List<ChatMessage>,
    streamingMessage: ChatMessage?,
    searchState: ChatSearchState,
    typingEffectEnabled: Boolean,
    typingEffectDelayMs: Int,
    maxToolCallsPerTurn: Int? = null,
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    isDark: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scrollController: ChatScrollController,
    lastAnimatedMessageId: String?,
    onLastAnimatedMessageIdChange: (String?) -> Unit,
    viewModel: ChatViewModel,
    clarifyRequest: ClarifyUi? = null,
    onRespondClarify: ((String) -> Unit)? = null,
    onRespondClarifyBatch: ((Map<String, String>) -> Unit)? = null,
    onDismissClarify: (() -> Unit)? = null,
    onSaveAttachment: (com.m57.hermescontrol.data.model.Attachment) -> Unit = {},
    savingAttachmentPath: String? = null,
    openingAttachmentPath: String? = null,
    onImageClick: (ImageViewerModel) -> Unit = {},
    sessionKey: String? = null,
    showThinkingIndicator: Boolean = false,
) {
    var expandedTurns by remember(sessionKey) { mutableStateOf(emptySet<String>()) }
    if (messages.isEmpty() && !isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                title = stringResource(R.string.chat_empty_title),
                subtitle = stringResource(R.string.chat_empty_subtitle),
            )
        }
    } else {
        val toolMilestones = toolCallMilestones(messages)
        val turns =
            remember(messages, streamingMessage) {
                groupIntoTurnsWithStreaming(messages, streamingMessage)
            }
        // messageId → LazyColumn item index. The lazy list has EXTRA items
        // vs the message list (reasoning hoists, tool rows), so search-match
        // scrolling must resolve the lazy index, not use the message index.
        val lazyIndexById =
            remember(turns, isLoadingOlder, expandedTurns) {
                messageIdToLazyIndex(turns, leadingItems = if (isLoadingOlder) 1 else 0, expandedTurns = expandedTurns)
            }

        val searchTarget = messages.getOrNull(searchState.matchIndices.getOrNull(searchState.currentIndex) ?: -1)
        // Identity token, not an equal-by-value key: A→B→A must acknowledge a fresh layout.
        val searchLayoutToken =
            remember(
                sessionKey,
                searchState.isActive,
                searchState.currentIndex,
                searchState.query,
                searchState.matchOffsets,
                searchState.currentMatchId,
                searchTarget?.id,
                searchTarget?.content,
                expandedTurns,
                isLoadingOlder,
            ) { Any() }
        var laidOutSearchToken by remember(sessionKey) { mutableStateOf<Any?>(null) }

        // Search positioning stays in this effect so a new query cancels every
        // old scroll. USER targets must compose and finish their forced-open
        // layout before the controller reads the item's height.
        LaunchedEffect(searchLayoutToken) {
            if (searchState.isActive && searchState.query.isNotBlank() && searchTarget != null) {
                val lazyIndex = lazyIndexById[searchTarget.id] ?: return@LaunchedEffect
                val contentOffset = searchState.matchOffsets.getOrElse(searchState.currentIndex) { 0 }
                if (searchTarget.role == MessageRole.USER) {
                    if (searchState.currentMatchId != searchTarget.id) return@LaunchedEffect
                    listState.scrollToItem(lazyIndex)
                    snapshotFlow { laidOutSearchToken }.first { it === searchLayoutToken }
                }
                scrollController.scrollToSearchMatch(lazyIndex, contentOffset, searchTarget.content.length)
            }
        }

        val currentDensity = LocalDensity.current
        val chatFontScale = LocalChatFontScale.current
        val chatDensity =
            remember(currentDensity, chatFontScale) {
                Density(
                    density = currentDensity.density,
                    fontScale = currentDensity.fontScale * chatFontScale,
                )
            }

        CompositionLocalProvider(LocalDensity provides chatDensity) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (isLoadingOlder) {
                    item(key = "loading-older") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }

                var entryIndex = 0
                var nativeTurnAnchor = "leading"
                turns.forEach { turn ->
                    when (turn) {
                        is ChatTurn.User -> {
                            // Eager captures: item lambda reads these at
                            // composition time (lazy), so capture now.
                            val userMessage = turn.message
                            nativeTurnAnchor =
                                nativeUiKey(
                                    userMessage.timestamp.toString(),
                                    RichAnswerPrompt.visibleText(userMessage.content),
                                )
                            val milestone = toolMilestones[entryIndex]
                            item(key = "user-${userMessage.id}") {
                                Column(
                                    modifier =
                                        Modifier.padding(bottom = 12.dp).onGloballyPositioned {
                                            if (searchState.isActive && searchState.query.isNotBlank() &&
                                                searchState.currentMatchId == userMessage.id &&
                                                searchTarget?.id == userMessage.id &&
                                                searchTarget.content == userMessage.content
                                            ) {
                                                laidOutSearchToken = searchLayoutToken
                                            }
                                        },
                                ) {
                                    renderChatBubble(
                                        message = userMessage,
                                        searchQuery = if (searchState.isActive) searchState.query else "",
                                        isCurrentMatch =
                                            searchState.currentMatchId != null &&
                                                searchState.currentMatchId == userMessage.id,
                                        onOpenAttachment = viewModel::openAttachment,
                                        onSaveAttachment = onSaveAttachment,
                                        savingAttachmentPath = savingAttachmentPath,
                                        openingAttachmentPath = openingAttachmentPath,
                                        onImageClick = onImageClick,
                                    )
                                    milestone?.let { count ->
                                        ToolCallDivider(count = count, maxPerTurn = maxToolCallsPerTurn)
                                    }
                                }
                            }
                            entryIndex++
                        }

                        is ChatTurn.Agent -> {
                            var firstProseSeen = false
                            val hasActivity = turn.hasActivity()
                            val turnKey = if (hasActivity) turn.tabKey() else ""
                            val activityExpanded = turnKey in expandedTurns
                            val reasoningMessages =
                                turn.entries
                                    .filterIsInstance<AgentEntry.Prose>()
                                    .map { it.message }
                                    .filter { it.reasoningText.isNotBlank() }
                            if (hasActivity) {
                                val headerMessage =
                                    turn.entries.first().let { entry ->
                                        when (entry) {
                                            is AgentEntry.Prose -> entry.message
                                            is AgentEntry.ToolRow -> entry.message
                                            is AgentEntry.SystemEvent -> entry.message
                                            is AgentEntry.ConnectionEvents -> entry.messages.first()
                                        }
                                    }
                                item(key = "activity-$turnKey") {
                                    Column {
                                        AssistantTurnHeader(
                                            timestamp = headerMessage.timestamp,
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                        )
                                        AgentTurnActivityRow(
                                            expanded = activityExpanded,
                                            hasReasoning = reasoningMessages.isNotEmpty(),
                                            toolCount = turn.entries.count { it is AgentEntry.ToolRow },
                                            toolRunning =
                                                turn.entries.any {
                                                    it is AgentEntry.ToolRow &&
                                                        it.message.toolStatus ==
                                                        com.m57.hermescontrol.ui.chat.ToolStatus.RUNNING
                                                },
                                            onToggle = {
                                                expandedTurns =
                                                    if (activityExpanded) {
                                                        expandedTurns - turnKey
                                                    } else {
                                                        expandedTurns + turnKey
                                                    }
                                            },
                                        )
                                    }
                                }
                            }
                            if (activityExpanded) {
                                reasoningMessages.forEach { reasoning ->
                                    item(key = "reasoning-${reasoning.id}") {
                                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                            ReasoningCard(
                                                reasoningText = reasoning.reasoningText,
                                                isStreaming = reasoning.isStreaming,
                                            )
                                        }
                                    }
                                }
                            }
                            turn.entries.forEach entries@{ entry ->
                                if (!entry.visibleWhen(activityExpanded)) {
                                    entryIndex += if (entry is AgentEntry.ConnectionEvents) entry.messages.size else 1
                                    return@entries
                                }
                                when (entry) {
                                    is AgentEntry.Prose -> {
                                        val proseMessage = entry.message
                                        val nativeIdentity =
                                            nativeUiKey(
                                                nativeTurnAnchor,
                                                turn.entries
                                                    .filterIsInstance<AgentEntry.Prose>()
                                                    .indexOf(entry)
                                                    .toString(),
                                            )
                                        val showTurnHeader = !firstProseSeen && !hasActivity
                                        val hoistedReasoning = hasActivity
                                        val milestone = toolMilestones[entryIndex]
                                        item(key = "prose-${proseMessage.id}") {
                                            CompositionLocalProvider(LocalNativeUiMessage provides nativeIdentity) {
                                                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                                                    if (proseMessage.isStreaming && typingEffectEnabled &&
                                                        !NativeUiProtocol.hasFence(proseMessage.content)
                                                    ) {
                                                        StreamingFullBleedWithTypingEffect(
                                                            streaming = proseMessage,
                                                            typingDelayMs = typingEffectDelayMs,
                                                            isDark = isDark,
                                                            showTurnHeader = showTurnHeader,
                                                            showReasoning = !hoistedReasoning,
                                                        )
                                                    } else {
                                                        FullBleedAgentMessage(
                                                            message = proseMessage,
                                                            showTurnHeader = showTurnHeader,
                                                            isDarkTheme = isDark,
                                                            // Highlight only bubbles that actually contain a match —
                                                            // the rest skip the highlight scan entirely.
                                                            searchQuery =
                                                                if (searchState.isActive &&
                                                                    proseMessage.id in searchState.matchedIds
                                                                ) {
                                                                    searchState.query
                                                                } else {
                                                                    ""
                                                                },
                                                            isCurrentMatch =
                                                                searchState.currentMatchId != null &&
                                                                    searchState.currentMatchId == proseMessage.id,
                                                            showReasoning = !hoistedReasoning,
                                                            onOpenAttachment = viewModel::openAttachment,
                                                            onSaveAttachment = onSaveAttachment,
                                                            savingAttachmentPath = savingAttachmentPath,
                                                            openingAttachmentPath = openingAttachmentPath,
                                                            canSaveAttachment = savingAttachmentPath == null,
                                                            onImageClick = onImageClick,
                                                        )
                                                    }
                                                    milestone?.let { count ->
                                                        ToolCallDivider(count = count, maxPerTurn = maxToolCallsPerTurn)
                                                    }
                                                }
                                            }
                                        }
                                        firstProseSeen = true
                                        entryIndex++
                                    }

                                    is AgentEntry.ToolRow -> {
                                        val toolMessage = entry.message
                                        val milestone = toolMilestones[entryIndex]
                                        item(key = "tool-${toolMessage.id}") {
                                            Column(modifier = Modifier.padding(bottom = 2.dp)) {
                                                FullBleedToolRow(toolMessage)
                                                milestone?.let { count ->
                                                    ToolCallDivider(count = count, maxPerTurn = maxToolCallsPerTurn)
                                                }
                                            }
                                        }
                                        entryIndex++
                                    }

                                    is AgentEntry.ConnectionEvents -> {
                                        // Routine connect/resume notices stay in the data and keep
                                        // their entry indices, but render no Lazy item.
                                        entryIndex += entry.messages.size
                                    }

                                    is AgentEntry.SystemEvent -> {
                                        val sysMessage = entry.message
                                        item(key = "sys-${sysMessage.id}") {
                                            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                                                val clarify = sysMessage.clarifyInfo
                                                if (clarify != null) {
                                                    ClarifyBubble(
                                                        clarifyRequest = clarify,
                                                        onRespondSingle = { viewModel.respondToClarify(clarify, it) },
                                                        onRespondBatch = {
                                                            viewModel.respondToClarifyBatch(
                                                                clarify,
                                                                it,
                                                            )
                                                        },
                                                        onDismiss = { viewModel.dismissClarify(clarify) },
                                                    )
                                                } else if (sysMessage.displayKind != null) {
                                                    // Timeline marker (issue #904):
                                                    // model/personality switches and
                                                    // auto-continues render as a chip.
                                                    TimelineMarkerChip(message = sysMessage)
                                                } else {
                                                    FullBleedSystemEvent(
                                                        message = sysMessage,
                                                        onRespondApproval = viewModel::respondToApproval,
                                                    )
                                                }
                                            }
                                        }
                                        entryIndex++
                                    }
                                }
                            }
                        }
                    }
                }

                // 「思考中」 after everything the turn already shows, including
                // interim commentary and folded tool rows, while it still works.
                if (showThinkingIndicator) {
                    item(key = "thinking-indicator") {
                        ThinkingIndicator(modifier = Modifier.padding(bottom = 6.dp))
                    }
                }

                // Clarify bubble — rendered at the very bottom
                if (clarifyRequest != null && clarifyRequest.ref == null &&
                    messages.none { it.clarifyInfo == clarifyRequest }
                ) {
                    item(key = "clarify_bubble") {
                        ClarifyBubble(
                            clarifyRequest = clarifyRequest,
                            onRespondSingle = { option -> onRespondClarify?.invoke(option) },
                            onRespondBatch = { answers -> onRespondClarifyBatch?.invoke(answers) },
                            onDismiss = { onDismissClarify?.invoke() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun renderChatBubble(
    message: ChatMessage,
    searchQuery: String,
    isCurrentMatch: Boolean,
    onOpenAttachment: (com.m57.hermescontrol.data.model.Attachment) -> Unit,
    onSaveAttachment: (com.m57.hermescontrol.data.model.Attachment) -> Unit,
    savingAttachmentPath: String?,
    openingAttachmentPath: String?,
    onImageClick: (ImageViewerModel) -> Unit,
) {
    ChatBubble(
        message = message,
        searchQuery = searchQuery,
        isCurrentMatch = isCurrentMatch,
        onOpenAttachment = onOpenAttachment,
        onSaveAttachment = onSaveAttachment,
        savingAttachmentPath = savingAttachmentPath,
        openingAttachmentPath = openingAttachmentPath,
        canSaveAttachment = savingAttachmentPath == null,
        onImageClick = onImageClick,
    )
}
