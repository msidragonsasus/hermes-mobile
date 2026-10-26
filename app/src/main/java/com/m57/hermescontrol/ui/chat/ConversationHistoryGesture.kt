package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Screen-owned history gesture; child controls must retain their own horizontal drags. */
@Composable
internal fun Modifier.conversationHistorySwipe(onOpen: () -> Unit): Modifier {
    val distance = with(LocalDensity.current) { 64.dp.toPx() }
    val currentOnOpen by rememberUpdatedState(onOpen)
    return pointerInput(distance) {
        var travelled = 0f
        var opened = false
        detectHorizontalDragGestures(
            onDragStart = {
                travelled = 0f
                opened = false
            },
            onHorizontalDrag = { change, amount ->
                travelled += amount
                if (travelled > distance && !opened) {
                    change.consume()
                    opened = true
                    currentOnOpen()
                }
            },
        )
    }
}
