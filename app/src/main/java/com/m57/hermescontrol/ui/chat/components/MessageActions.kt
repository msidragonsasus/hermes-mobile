package com.m57.hermescontrol.ui.chat.components

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.RichAnswerPrompt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal val LocalMessageReply = staticCompositionLocalOf<((ChatMessage) -> Unit)?> { null }
internal val LocalConversationComposerFocus = staticCompositionLocalOf<FocusRequester?> { null }

/** Quote into the current draft only. Sending still belongs to the destination's existing policy. */
internal fun quotedReplyDraft(
    message: ChatMessage,
    draft: String,
): String = quotedReplyMessage(messageActionText(message), draft)

/** Preserve the existing markdown wire representation when submitting a reply. */
internal fun quotedReplyMessage(
    quote: String?,
    draft: String,
): String {
    if (quote.isNullOrBlank()) return draft
    return quote.lineSequence().joinToString("\n") { "> $it" } + "\n\n" + draft
}

internal fun quotedReplyField(
    message: ChatMessage,
    draft: TextFieldValue,
): TextFieldValue {
    val text = quotedReplyDraft(message, draft.text)
    return TextFieldValue(text, selection = TextRange(text.length))
}

internal fun messageActionText(message: ChatMessage): String =
    if (message.role == MessageRole.USER) RichAnswerPrompt.visibleText(message.content) else message.content

/** Shared actions for user and assistant messages. Selection uses the rendered text in place. */
@Composable
internal fun MessageActionHost(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    selectable: Boolean = !message.isStreaming,
    selectionColors: TextSelectionColors = LocalTextSelectionColors.current,
    content: @Composable () -> Unit,
) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    var selecting by remember(message.id) { mutableStateOf(false) }
    val text = messageActionText(message)
    val available = !message.isStreaming && text.isNotBlank()
    val selectionActive = selectable && available && selecting
    val reply = LocalMessageReply.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val menuLabel = stringResource(R.string.chat_message_actions)
    val latestContent by rememberUpdatedState(content)
    // Keep the same renderer state when moving it into or out of SelectionContainer.
    val renderContent = remember { movableContentOf { latestContent() } }
    val gestures =
        if (available && !selectionActive) {
            Modifier
                .pointerInput(message.id, haptics) {
                    awaitEachGesture {
                        // A link or control can own down and still let the bubble recognize a hold.
                        val down = awaitFirstDown(requireUnconsumed = false)
                        awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        expanded = true
                        // Prevent the release of a recognized hold from also activating the child.
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }.semantics {
                    onLongClick(menuLabel) {
                        expanded = true
                        true
                    }
                }
        } else {
            Modifier
        }
    Box(modifier.then(gestures)) {
        if (selectionActive) {
            CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                MessageTextSelection(onDismiss = { selecting = false }) { renderContent() }
            }
        } else {
            renderContent()
        }
    }
    if (expanded && available) {
        MessageActionMenu(
            onDismiss = { expanded = false },
            onReply =
                reply?.let { action ->
                    {
                        expanded = false
                        action(message)
                    }
                },
            onCopy = {
                expanded = false
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) }
            },
            onSelect =
                if (selectable) {
                    {
                        expanded = false
                        selecting = true
                    }
                } else {
                    null
                },
        )
    }
}

@Composable
private fun MessageTextSelection(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberSelectionState()
    val conversation = LocalMessageSelection.current
    val windowInfo = LocalWindowInfo.current
    BackHandler { onDismiss() }
    DisposableEffect(conversation, state) {
        conversation?.active = state
        onDispose {
            if (conversation != null && conversation.active === state) {
                conversation.active = null
                conversation.bubble = null
            }
        }
    }
    LaunchedEffect(state) {
        // Wait for the menu to yield window focus and the rendered text to register its geometry.
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        withFrameNanos { }
        state.selectAll()
        snapshotFlow { state.selectedTexts }.first { it.isEmpty() }
        onDismiss()
    }
    SelectionContainer(
        state = state,
        modifier =
            Modifier
                .testTag("message_selection_text")
                .onGloballyPositioned {
                    if (conversation != null &&
                        conversation.active === state
                    ) {
                        conversation.bubble = it
                    }
                },
        content = content,
    )
}

@Composable
private fun MessageActionMenu(
    onDismiss: () -> Unit,
    onReply: (() -> Unit)?,
    onCopy: () -> Unit,
    onSelect: (() -> Unit)?,
) {
    val spacing = LocalSpacing.current
    val itemPadding = PaddingValues(horizontal = spacing.xl, vertical = spacing.xs)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(min = 192.dp, max = 280.dp).testTag("message_action_menu"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = spacing.xs)) {
                if (onReply != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_reply)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Reply, contentDescription = null) },
                        contentPadding = itemPadding,
                        onClick = onReply,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("message_action_reply"),
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.content_desc_copy)) },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    contentPadding = itemPadding,
                    onClick = onCopy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("message_action_copy"),
                )
                if (onSelect != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_message_select)) },
                        leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                        contentPadding = itemPadding,
                        onClick = onSelect,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("message_action_select"),
                    )
                }
            }
        }
    }
}
