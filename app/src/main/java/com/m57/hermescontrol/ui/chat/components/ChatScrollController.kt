package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shared latest-message actions, unread badge and search navigation (issue #682).
 * Forward lists follow content changes through scroll commands. A reversed timeline
 * anchors content in layout and reports whether its index and offset are both zero.
 */
class ChatScrollController(
    private val listState: LazyListState,
    internal val scope: CoroutineScope,
    followIntent: MutableState<Boolean> = mutableStateOf(true),
    private val reverseLayout: Boolean = false,
) {
    /** True while the reader is pinned at (or within tolerance of) the bottom. */
    private var forwardFollowIntent by followIntent
    val isFollowingBottom: Boolean
        get() = if (reverseLayout) listState.isAtReverseChatBottom else forwardFollowIntent

    /**
     * Number of new messages that arrived while follow was paused. Shown on the
     * scroll-to-bottom FAB; cleared when the reader resumes following.
     */
    var pendingCount by mutableStateOf(0)
        private set

    private var lastTailKey: Any? = null
    private var lastMessageCount: Int = 0
    private var tailFollowJob: Job? = null
    private var tailFollowPending = false

    /**
     * Pixel tolerance for "at bottom" detection. Covers the LazyColumn's
     * bottom contentPadding (8dp ≈ 24px on hdpi) plus a small margin so
     * that soft-keyboard transitions and fractional rendering don't falsely
     * break follow mode.
     */
    var bottomPixelTolerance by mutableStateOf(48)

    /** Serialized scroll scope — all scroll jobs funnel through here. */
    fun launchScroll(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }

    /** Observe scroll changes for the lifetime of the calling LaunchedEffect. */
    suspend fun observeUserScrollPosition() {
        if (reverseLayout) {
            snapshotFlow { listState.isAtReverseChatBottom }.collect { atBottom ->
                if (atBottom) pendingCount = 0
            }
            return
        }

        var previousHeight: Int? = null
        snapshotFlow {
            val layout = listState.layoutInfo
            if (layout.visibleItemsInfo.isEmpty()) {
                null
            } else {
                Triple(
                    listState.isAtBottom(bottomPixelTolerance),
                    layout.viewportSize.height,
                    listState.isScrollInProgress,
                )
            }
        }.filterNotNull()
            .distinctUntilChanged()
            .collect { (atBottom, height, scrolling) ->
                val firstLayout = previousHeight == null
                val resized = previousHeight != height
                previousHeight = height
                if (tailFollowJob?.isActive == true) return@collect
                if (resized && isFollowingBottom && !scrolling && (!firstLayout || !atBottom)) {
                    // IME, attachments and a growing composer resize the viewport.
                    scheduleTailFollow()
                } else if (atBottom) {
                    forwardFollowIntent = true
                    pendingCount = 0
                } else {
                    forwardFollowIntent = false
                }
            }
    }

    /**
     * Call on every tail-content change (new message, streaming token, thinking
     * toggle, subagent card, clarify prompt). [tailKey] must be a stable value
     * that changes only when the tail content actually changes — pass
     * [tailContentKey] for a ready-made key. [messageCount] is the current total
     * message count, used to derive the unseen-message badge while paused.
     *
     * Follows the tail only when [isFollowingBottom] was true before this
     * change. While paused, increments [pendingCount] by the number of new
     * messages instead.
     */
    fun onTailChanged(
        tailKey: Any?,
        messageCount: Int = 0,
    ) {
        if (tailKey == lastTailKey) {
            lastMessageCount = messageCount
            return
        }
        val newMessages = if (lastTailKey == null) 0 else (messageCount - lastMessageCount).coerceAtLeast(0)
        lastMessageCount = messageCount
        lastTailKey = tailKey
        if (!isFollowingBottom) {
            pendingCount += newMessages
        } else if (!reverseLayout) {
            scheduleTailFollow()
        }
    }

    private fun scheduleTailFollow(animated: Boolean = false) {
        tailFollowPending = true
        if (tailFollowJob?.isActive == true) return
        tailFollowJob =
            scope.launch {
                var animate = animated
                while (tailFollowPending && isFollowingBottom) {
                    tailFollowPending = false
                    scrollToBottomAwaitingLayout(animate)
                    animate = false
                }
            }
    }

    private suspend fun scrollToBottomAwaitingLayout(animated: Boolean = false) {
        do {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
            // Layout-info observers can resume during LazyColumn subcomposition.
            withFrameNanos { }
        } while (listState.layoutInfo.visibleItemsInfo.isEmpty())
        listState.scrollToBottom(animated)
        if (!listState.isAtBottom(bottomPixelTolerance)) {
            withFrameNanos { }
            listState.scrollToBottom(animated = false)
        }
    }

    /** Force-follow to the bottom (session switch / explicit send). Clears unread. */
    fun jumpToBottom(animated: Boolean = false) {
        tailFollowJob?.cancel()
        pendingCount = 0
        if (reverseLayout) {
            if (animated) {
                tailFollowJob = scope.launch { listState.animateScrollToItem(0) }
            } else {
                listState.requestScrollToItem(0)
            }
        } else {
            forwardFollowIntent = true
            scheduleTailFollow(animated)
        }
    }

    /** FAB tap: resume following + clear unread. */
    fun resumeFollowing() = jumpToBottom(animated = true)

    /** True when the FAB should be visible (not following bottom + content exists). */
    fun showFab(contentPresent: Boolean): Boolean = !isFollowingBottom && contentPresent

    /**
     * Restore the viewport to a specific item (resolved by id in the caller, so
     * the restore is robust even if streaming tokens arrive during the page
     * insert). Keeps the reader's eye on the same content after prepend.
     */
    fun scrollToItem(
        index: Int,
        offset: Int,
    ) {
        scope.launch { listState.scrollToItem(index, offset) }
    }

    /**
     * Navigate to a search match (serialized through [scope]).
     *
     * [index] is the LAZY-COLUMN item index (see [messageIdToLazyIndex] —
     * message indices ≠ lazy indices once reasoning/tool items exist).
     * Top-aligns the item, then repositions it so the matched WORD lands
     * comfortably in view (~1/3 down the viewport): the word's position is
     * estimated as textOffset / contentLength × measured item height. Mirrors
     * the verified scrollToBottom pattern (top-align → measure → adjust).
     *
     * @param contentOffset character offset of the word in the message text.
     * @param contentLength total length of the message text.
     */
    suspend fun scrollToSearchMatch(
        index: Int,
        contentOffset: Int,
        contentLength: Int,
        obscuredTopPx: Int = 0,
    ) {
        // Runs in the caller's search effect: a changed query cancels stale positioning.
        tailFollowJob?.cancel()
        if (!reverseLayout) forwardFollowIntent = false
        listState.animateScrollToItem(index)
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
        val fraction = if (contentLength > 0) contentOffset.toFloat() / contentLength else 0f
        val matchOffset = (item.size * fraction).toInt()
        val delta =
            if (reverseLayout) {
                // Lazy item offsets run from bottom to top in the reversed column.
                val itemTop = info.viewportEndOffset - item.offset - item.size
                (viewportHeight / 3).coerceAtLeast(obscuredTopPx) - (itemTop + matchOffset)
            } else {
                val targetTop = (viewportHeight / 3).coerceAtLeast(obscuredTopPx + info.viewportStartOffset)
                item.offset + matchOffset - targetTop
            }
        if (delta != 0) {
            listState.animateScrollBy(delta.toFloat())
        }
    }
}

/**
 * Stable tail-key covering messages, streaming state, typing, subagent cards,
 * and clarification prompts — anything that can change the list tail.
 */
fun tailContentKey(
    messages: List<*>,
    streamingMessage: Any?,
    isThinking: Boolean,
    subagentIndicators: List<*>,
    clarifyRequest: Any?,
): Any =
    listOf(
        messages.size,
        streamingMessage?.hashCode() ?: 0,
        isThinking,
        subagentIndicators.size,
        clarifyRequest?.hashCode() ?: 0,
    )

/**
 * Continuous bottom-follow tracker derived from [LazyListState]: true when the
 * last item's bottom edge is at (or within [tolerance] px of) the viewport
 * bottom. Replaces the old item-count `isAtBottom(threshold)` heuristic.
 */
fun LazyListState.isAtBottom(tolerance: Int = 8): Boolean {
    val layoutInfo = this.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return true
    val lastItem = visibleItems.last()
    if (lastItem.index < layoutInfo.totalItemsCount - 1) return false
    val lastBottom = lastItem.offset + lastItem.size
    val viewportBottom = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
    return lastBottom <= viewportBottom + tolerance
}

/**
 * Scroll so the bottom edge of the last item is aligned to the viewport bottom.
 * Top-aligns first (instant or animated), then scrolls the exact remaining gap
 * so a taller-than-viewport last item's bottom stays visible. Uses the remaining
 * delta (not `Int.MAX_VALUE`) to avoid integer-overflow wrap in the internal
 * scroll-position clamp.
 */
suspend fun LazyListState.scrollToBottom(animated: Boolean) {
    val layoutInfo = this.layoutInfo
    if (layoutInfo.totalItemsCount == 0) return
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (animated) {
        animateScrollToItem(lastIndex)
    } else {
        scrollToItem(lastIndex)
    }
    val info = this.layoutInfo
    val lastItem = info.visibleItemsInfo.lastOrNull { it.index == lastIndex } ?: return
    val remaining =
        (lastItem.offset + lastItem.size + info.afterContentPadding) - info.viewportEndOffset
    if (remaining > 0) {
        if (animated) {
            animateScrollBy(remaining.toFloat())
        } else {
            scroll { scrollBy(remaining.toFloat()) }
        }
    }
}

internal val LazyListState.isAtReverseChatBottom: Boolean
    get() = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

@Composable
fun rememberChatScrollController(
    listState: LazyListState,
    scope: CoroutineScope,
    reverseLayout: Boolean = false,
): ChatScrollController {
    val followIntent = rememberSaveable { mutableStateOf(true) }
    return remember(listState, scope, followIntent, reverseLayout) {
        ChatScrollController(listState, scope, followIntent, reverseLayout)
    }
}
