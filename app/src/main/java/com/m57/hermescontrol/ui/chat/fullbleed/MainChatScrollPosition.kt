package com.m57.hermescontrol.ui.chat.fullbleed

import android.os.Bundle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.m57.hermescontrol.ui.chat.components.ChatTimelineNoPrefetchStrategy
import com.m57.hermescontrol.ui.chat.components.isAtReverseChatBottom
import com.m57.hermescontrol.ui.chat.nativeUiKey

internal fun mainChatScrollKey(
    scope: String,
    sessionId: String?,
): String = nativeUiKey(scope, sessionId.orEmpty())

internal data class MainChatVisibleAnchor(
    val key: String,
    val index: Int,
    val offset: Int,
)

@OptIn(ExperimentalFoundationApi::class)
internal class MainChatScrollPosition(
    private val anchors: Bundle,
    private val sessionKey: String,
) {
    val listState = LazyListState(0, 0, ChatTimelineNoPrefetchStrategy)
    private var awaitingAnchor = anchors.containsKey(sessionKey)
    private var latestKey: String? = null
    private var isTouching = false

    // Observe down before Compose crosses touch slop. Leave the gesture to the list and its rows.
    val touchObserver =
        Modifier.pointerInput(this) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                isTouching = true
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                } finally {
                    isTouching = false
                }
            }
        }

    fun onRowsChanged(
        latest: String?,
        loading: Boolean,
        indexOf: (String) -> Int?,
    ) {
        if (latest == null) return
        val canAdjustScroll = !isTouching && !listState.isScrollInProgress
        if (awaitingAnchor) {
            val saved = anchors.getBundle(sessionKey)
            val index = saved?.getString("key")?.let(indexOf)
            if (index != null) {
                if (canAdjustScroll) listState.requestScrollToItem(index, saved.getInt("offset"))
            } else if (saved != null && loading) {
                return
            }
            awaitingAnchor = false
        } else if (latestKey != null && latestKey != latest &&
            listState.isAtReverseChatBottom && canAdjustScroll
        ) {
            // Compose normally keeps the old index-0 key on insertion. When pinned,
            // keep index 0 on the next measure so a newly inserted row is visible.
            listState.requestScrollToItem(0)
        }
        latestKey = latest
    }

    fun forgetAnchor(key: String) {
        anchors.remove(key)
        if (key == sessionKey) awaitingAnchor = false
    }

    fun save(anchor: MainChatVisibleAnchor?) {
        if (awaitingAnchor || anchor == null) return
        if (anchor.index == 0 && anchor.offset == 0) {
            anchors.remove(sessionKey)
        } else {
            anchors.putBundle(
                sessionKey,
                Bundle().apply {
                    putString("key", anchor.key)
                    putInt("offset", anchor.offset)
                },
            )
        }
    }

    fun visibleAnchor(): MainChatVisibleAnchor? {
        val index = listState.firstVisibleItemIndex
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
        return MainChatVisibleAnchor(item.key.toString(), index, listState.firstVisibleItemScrollOffset)
    }
}

@Composable
internal fun rememberMainChatScrollPosition(sessionKey: String): MainChatScrollPosition {
    val anchors = rememberSaveable { Bundle() }
    val position = remember(anchors, sessionKey) { MainChatScrollPosition(anchors, sessionKey) }
    LaunchedEffect(position) {
        snapshotFlow { position.visibleAnchor() }.collect(position::save)
    }
    DisposableEffect(position) {
        onDispose { position.save(position.visibleAnchor()) }
    }
    return position
}
