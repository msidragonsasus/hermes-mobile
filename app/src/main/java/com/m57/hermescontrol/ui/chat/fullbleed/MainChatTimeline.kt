package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.theme.LocalChatFontScale
import com.m57.hermescontrol.ui.chat.ChatBubble
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatSearchState
import com.m57.hermescontrol.ui.chat.ChatUiState
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.ClarifyUi
import com.m57.hermescontrol.ui.chat.ImageViewerModel
import com.m57.hermescontrol.ui.chat.LocalNativeUiMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.SPECIALIST_SETUP_REVIEW
import com.m57.hermescontrol.ui.chat.SpecialistChatState
import com.m57.hermescontrol.ui.chat.StreamingState
import com.m57.hermescontrol.ui.chat.awaitsUserInput
import com.m57.hermescontrol.ui.chat.brainstorm.BrainstormCard
import com.m57.hermescontrol.ui.chat.components.ChatScrollController
import com.m57.hermescontrol.ui.chat.components.ClarifyBubble
import com.m57.hermescontrol.ui.chat.isApprovalTimelineRow
import com.m57.hermescontrol.ui.chat.isLivePreviewOf
import com.m57.hermescontrol.ui.chat.isSpecialistSetupText
import com.m57.hermescontrol.ui.common.EmptyState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.abs

private const val PENDING_CLARIFY_KEY = "clarify_bubble"
private const val SPECIALIST_SETTINGS_KEY = "specialist-settings"

@Composable
internal fun MainChatTimeline(
    state: ChatUiState,
    messages: List<ChatMessage>,
    streaming: State<StreamingState>,
    headerHeight: Dp,
    scrollController: ChatScrollController,
    scrollPosition: MainChatScrollPosition,
    search: ChatSearchState,
    viewModel: ChatViewModel,
    onSaveAttachment: (Attachment) -> Unit,
    savingAttachmentPath: String?,
    onImageClick: (ImageViewerModel) -> Unit,
    specialistReview: (@Composable () -> Unit)? = null,
    specialistFooter: (@Composable () -> Unit)? = null,
    specialistCardsState: SpecialistChatState? = null,
) {
    val listState = scrollPosition.listState
    val slot by remember(streaming) {
        derivedStateOf(structuralEqualityPolicy()) { streaming.value.streamingMessage?.mainChatStreamingSlot() }
    }
    val awaitingInput = state.awaitsUserInput()
    val activeLedger = state.responseLedger
    val historyAnswer =
        remember(messages, activeLedger.activeHistoryAnswerId) {
            messages.firstOrNull { it.id == activeLedger.activeHistoryAnswerId }
        }
    val showsLivePreview by remember(streaming, historyAnswer) {
        derivedStateOf(structuralEqualityPolicy()) {
            historyAnswer != null && streaming.value.streamingMessage?.isLivePreviewOf(historyAnswer) == true
        }
    }
    val scope = viewModel.renderPlanningScope()
    val activeHasText =
        remember(messages, activeLedger) {
            val active = activeLedger.responses.singleOrNull { it.id == activeLedger.activeId }
            active != null &&
                messages.any {
                    it.role == MessageRole.ASSISTANT && it.isMainChatSearchable() && it.id in active.messageIds
                }
        }
    val canWait =
        state.isAgentTyping && state.isConnected && !state.responseStatusUnknown && !awaitingInput && !activeHasText
    val waiting by remember(streaming, canWait) {
        derivedStateOf(structuralEqualityPolicy()) {
            canWait && !streaming.value.turnEnded && streaming.value.sealedOrphanIds.isEmpty()
        }
    }
    val rows =
        remember(
            scope,
            state.currentSessionId,
            messages,
            slot,
            activeLedger,
            awaitingInput,
            waiting,
            showsLivePreview,
            state.brainstormsById,
        ) {
            planMainChatTimeline(
                messages,
                slot,
                activeLedger,
                waiting,
                allowReplyActions = !awaitingInput,
                showsLivePreview = showsLivePreview,
                brainstormsById = state.brainstormsById,
            )
        }
    val clarify = state.clarifyRequest
    val pendingClarify =
        remember(clarify, messages) {
            clarify?.takeIf { it.ref == null && messages.none { message -> message.clarifyInfo == it } }
        }
    val messageCount = remember(rows) { rows.count { it.message != null && !it.live } }
    val hasSpecialistFooter = specialistFooter != null
    val leadingRows = (if (pendingClarify != null) 1 else 0) + (if (hasSpecialistFooter) 1 else 0)
    val latestKey =
        when {
            pendingClarify != null -> PENDING_CLARIFY_KEY
            hasSpecialistFooter -> SPECIALIST_SETTINGS_KEY
            else -> rows.firstOrNull()?.key
        }
    SideEffect {
        scrollPosition.onRowsChanged(latestKey, state.isLoading) { key ->
            when {
                key == PENDING_CLARIFY_KEY && pendingClarify != null -> 0
                key == SPECIALIST_SETTINGS_KEY && hasSpecialistFooter -> leadingRows - 1
                else -> rows.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.plus(leadingRows)
            }
        }
    }
    LaunchedEffect(scrollController, rows, pendingClarify, specialistCardsState) {
        scrollController.onTailChanged(
            listOf(rows.firstOrNull()?.key, pendingClarify, specialistCardsState),
            messageCount,
        )
    }

    val topPadding = headerHeight + 8.dp
    val topPaddingPx = with(LocalDensity.current) { topPadding.roundToPx() }
    val matchId = search.currentMatchId
    val matchOffset = search.matchOffsets.getOrNull(search.currentIndex)
    val matchIndex = remember(rows, matchId) { matchId?.let { id -> rows.indexOfFirst { it.message?.id == id } } ?: -1 }
    // Scroll on match selection or when it first loads. Incoming rows can shift its index.
    LaunchedEffect(listState, matchId, matchOffset, search.query, search.isActive, topPaddingPx, matchIndex >= 0) {
        if (!search.isActive || matchOffset == null || matchId == null) return@LaunchedEffect
        val message = rows.getOrNull(matchIndex)?.message ?: return@LaunchedEffect
        scrollController.scrollToSearchMatch(
            matchIndex + leadingRows,
            matchOffset,
            message.content.length,
            obscuredTopPx = topPaddingPx,
        )
    }

    val density = LocalDensity.current
    val textScale = LocalChatFontScale.current
    val chatDensity = remember(density, textScale) { Density(density.density, density.fontScale * textScale) }
    val openingAttachmentPath = state.openingAttachmentPath
    val isLoadingOlder = state.isLoadingOlder
    val canPage by rememberUpdatedState(!state.isLoadingOlder && !state.isLoading)
    LaunchedEffect(listState, state.hasOlderMessages) {
        if (!state.hasOlderMessages) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val oldestVisible = layout.visibleItemsInfo.lastOrNull()
            if (canPage && layout.viewportSize.height > 0 && oldestVisible != null) {
                layout.totalItemsCount to (oldestVisible.index >= layout.totalItemsCount - 3)
            } else {
                null
            }
        }.filterNotNull()
            .distinctUntilChanged()
            .collect { (_, nearTop) ->
                if (nearTop && canPage) viewModel.loadOlderMessages()
            }
    }
    CompositionLocalProvider(LocalDensity provides chatDensity) {
        if (rows.isEmpty() && pendingClarify == null && specialistFooter == null && !state.isLoading &&
            !state.hasOlderMessages
        ) {
            Box(Modifier.fillMaxSize().padding(top = headerHeight), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.chat_empty_title),
                    subtitle = stringResource(R.string.chat_empty_subtitle),
                )
            }
            return@CompositionLocalProvider
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().then(scrollPosition.touchObserver).testTag("main_chat_timeline"),
            reverseLayout = true,
            contentPadding = PaddingValues(top = topPadding, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            if (pendingClarify != null) {
                item(key = PENDING_CLARIFY_KEY) { MainChatClarification(pendingClarify, viewModel) }
            }
            if (specialistFooter != null) {
                item(key = SPECIALIST_SETTINGS_KEY) { specialistFooter() }
            }
            items(rows, key = { it.key }, contentType = { it.message?.role }) { row ->
                val message =
                    if (row.live) {
                        streaming.value.streamingMessage?.takeIf { it.id == row.message?.id } ?: return@items
                    } else {
                        row.message
                    }
                val query =
                    if (message != null && search.isActive && message.id in search.matchedIds) search.query else ""
                val currentMatch = message != null && search.currentMatchId == message.id
                when {
                    message == null -> {
                        MainChatWaitingBubble(listState)
                    }

                    message.id == SPECIALIST_SETUP_REVIEW -> {
                        specialistReview?.invoke()
                    }

                    message.clarifyInfo != null -> {
                        MainChatClarification(message.clarifyInfo, viewModel)
                    }

                    row.brainstorm != null -> {
                        BrainstormCard(snapshot = row.brainstorm, modifier = Modifier.padding(horizontal = 16.dp))
                    }

                    message.isApprovalTimelineRow() -> {
                        FullBleedSystemEvent(message, onRespondApproval = viewModel::respondToApproval)
                    }

                    message.role == MessageRole.USER -> {
                        ChatBubble(
                            message = message,
                            searchQuery = query,
                            isCurrentMatch = currentMatch,
                            onOpenAttachment = viewModel::openAttachment,
                            onSaveAttachment = onSaveAttachment,
                            savingAttachmentPath = savingAttachmentPath,
                            openingAttachmentPath = openingAttachmentPath,
                            canSaveAttachment = savingAttachmentPath == null,
                            onImageClick = onImageClick,
                            showTimestamp = false,
                            personalStyle = true,
                        )
                    }

                    else -> {
                        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            CompositionLocalProvider(LocalNativeUiMessage provides row.nativeIdentity) {
                                FullBleedAgentMessage(
                                    message = message,
                                    actionMessage =
                                        if (row.live) {
                                            message
                                        } else {
                                            message.copy(
                                                id = row.reply?.responseId ?: message.id,
                                                content = row.reply?.text ?: message.content,
                                                isStreaming = row.reply == null,
                                            )
                                        },
                                    showTurnHeader = false,
                                    isDarkTheme = false,
                                    searchQuery = query,
                                    isCurrentMatch = currentMatch,
                                    showReasoning = false,
                                    onOpenAttachment = viewModel::openAttachment,
                                    onSaveAttachment = onSaveAttachment,
                                    savingAttachmentPath = savingAttachmentPath,
                                    openingAttachmentPath = openingAttachmentPath,
                                    canSaveAttachment = savingAttachmentPath == null,
                                    onImageClick = onImageClick,
                                    modifier = Modifier.widthIn(max = maxWidth * 0.88f),
                                    showTimestamp = false,
                                    showFooter = false,
                                    allowInteractiveContent = !message.isSpecialistSetupText(),
                                    personalStyle = true,
                                )
                            }
                        }
                    }
                }
            }
            if (isLoadingOlder) {
                item(key = "loading-older") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MainChatClarification(
    request: ClarifyUi,
    viewModel: ChatViewModel,
) {
    ClarifyBubble(
        clarifyRequest = request,
        onRespondSingle = { viewModel.respondToClarify(request, it) },
        onRespondBatch = { viewModel.respondToClarifyBatch(request, it) },
        onDismiss = { viewModel.dismissClarify(request) },
    )
}

@Composable
private fun MainChatWaitingBubble(listState: LazyListState) {
    val visible by remember(listState) {
        derivedStateOf(structuralEqualityPolicy()) {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.any {
                it.key == MAIN_CHAT_WAITING_KEY &&
                    it.offset + it.size > layout.viewportStartOffset &&
                    it.offset < layout.viewportEndOffset - layout.afterContentPadding
            }
        }
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val position =
        if (visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            rememberInfiniteTransition(label = "reply dots").animateFloat(
                initialValue = -1f,
                targetValue = 3f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Restart),
                label = "active dot",
            )
        } else {
            null
        }
    val label = stringResource(R.string.chat_waiting_for_reply)
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        Modifier
            .padding(start = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .size(width = 66.dp, height = 40.dp)
            .testTag(MAIN_CHAT_WAITING_KEY)
            .semantics { contentDescription = label },
    ) {
        for (index in 0..2) {
            val emphasis = position?.value?.let { (1f - abs(it - index)).coerceIn(0f, 1f) } ?: 0f
            drawCircle(
                color = dotColor.copy(alpha = 0.35f + 0.65f * emphasis),
                radius = 3.dp.toPx(),
                center = Offset(size.width / 2 + (index - 1) * 12.dp.toPx(), size.height / 2),
            )
        }
    }
}
