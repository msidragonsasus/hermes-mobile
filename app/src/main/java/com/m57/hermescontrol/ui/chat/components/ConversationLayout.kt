package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import com.m57.hermescontrol.theme.LocalConversationStyle
import com.m57.hermescontrol.ui.chat.ChatMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal val LocalMessageSelection = staticCompositionLocalOf<ConversationMessageSelection?> { null }

internal class ConversationMessageSelection {
    var active: SelectionState? = null
    var bubble: LayoutCoordinates? = null
    private var viewport: LayoutCoordinates? = null

    val dismissOnOutsideTap =
        Modifier
            .onGloballyPositioned { viewport = it }
            .pointerInput(this) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val selected = active ?: return@awaitEachGesture
                    val target = bubble?.takeIf { it.isAttached } ?: return@awaitEachGesture
                    val source = viewport?.takeIf { it.isAttached } ?: return@awaitEachGesture
                    val point = target.localPositionOf(source, down.position)
                    if (point.x in 0f..target.size.width.toFloat() && point.y in 0f..target.size.height.toFloat()) {
                        return@awaitEachGesture
                    }

                    // Observe outside taps without taking them from links, composer or timeline scrolling.
                    if (waitForUpOrCancellation(PointerEventPass.Initial) != null && active === selected) {
                        selected.clear()
                    }
                }
            }
}

/**
 * A measured conversation viewport and an independent, bottom-aligned composer layer.
 * The enclosing screen owns system bars; this layout owns IME avoidance. Scaffold measures
 * the complete dock (attachments, options and status included) before sizing the viewport,
 * so it never obscures the final message or depends on a previous frame's pixel measurement.
 */
@Composable
internal fun ConversationLayout(
    modifier: Modifier = Modifier,
    onReply: ((ChatMessage) -> Unit)? = null,
    composer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LocalConversationStyle.current
    val focus = remember { FocusRequester() }
    val messageSelection = remember { ConversationMessageSelection() }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current
    val scope = rememberCoroutineScope()
    val reply: ((ChatMessage) -> Unit)? =
        onReply?.let { action ->
            { message ->
                action(message)
                scope.launch {
                    // The message menu owns window focus until its dialog dismisses.
                    withFrameNanos { }
                    snapshotFlow { windowInfo.isWindowFocused }.first { it }
                    focus.requestFocus()
                    keyboard?.show()
                }
            }
        }
    CompositionLocalProvider(
        LocalMessageReply provides reply,
        LocalMessageSelection provides messageSelection,
        LocalConversationComposerFocus provides focus,
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize().imePadding().then(messageSelection.dismissOnOutsideTap),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Column(Modifier.widthIn(max = style.maxWidth).fillMaxWidth(), content = composer)
                }
            },
        ) { dockPadding ->
            Box(
                Modifier.fillMaxSize().padding(dockPadding).consumeWindowInsets(dockPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(Modifier.widthIn(max = style.maxWidth).fillMaxSize(), content = content)
            }
        }
    }
}
