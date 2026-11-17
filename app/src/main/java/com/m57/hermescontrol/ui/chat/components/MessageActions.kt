package com.m57.hermescontrol.ui.chat.components

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.RichAnswerPrompt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val LocalMessageReply = staticCompositionLocalOf<((ChatMessage) -> Unit)?> { null }
internal val LocalConversationComposerFocus = staticCompositionLocalOf<FocusRequester?> { null }

/** Quote into the current draft only. Sending still belongs to the destination's existing policy. */
internal fun quotedReplyDraft(
    message: ChatMessage,
    draft: String,
): String {
    val text = messageActionText(message)
    if (text.isBlank()) return draft
    return text.lineSequence().joinToString("\n") { "> $it" } + "\n\n" + draft
}

internal fun quotedReplyField(
    message: ChatMessage,
    draft: TextFieldValue,
): TextFieldValue {
    val text = quotedReplyDraft(message, draft.text)
    return TextFieldValue(text, selection = TextRange(text.length))
}

private fun messageActionText(message: ChatMessage): String =
    if (message.role == MessageRole.USER) RichAnswerPrompt.visibleText(message.content) else message.content

/** One anchored, rounded menu for user bubbles and agent prose, with no permanent action footer. */
@Composable
internal fun MessageActionHost(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    var selecting by remember(message.id) { mutableStateOf(false) }
    val selectionState = rememberSelectionState()
    var press by remember(message.id) { mutableStateOf(Offset.Zero) }
    val text = messageActionText(message)
    val available = !message.isStreaming && text.isNotBlank()
    val reply = LocalMessageReply.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val spacing = LocalSpacing.current
    val menuLabel = stringResource(R.string.chat_message_actions)
    val gestures =
        if (available && !selecting) {
            Modifier
                .pointerInput(message.id) {
                    detectTapGestures(onLongPress = {
                        press = it
                        expanded = true
                    })
                }.semantics {
                    onLongClick(menuLabel) {
                        expanded = true
                        true
                    }
                }
        } else {
            Modifier
        }
    BackHandler(enabled = selecting) {
        selectionState.clear()
        selecting = false
    }
    LaunchedEffect(selecting) {
        if (selecting) {
            // Wait for the original rendered text to register its selection geometry.
            withFrameNanos { }
            selectionState.selectAll()
            snapshotFlow { selectionState.selectedTexts }.first { it.isEmpty() }
            selecting = false
        }
    }
    Box(modifier.then(gestures)) {
        if (selecting && available) {
            SelectionContainer(state = selectionState, modifier = Modifier.testTag("message_selection_text")) {
                content()
            }
        } else {
            content()
        }
        // The anchor follows the pressed point, including a tall message scrolled partway offscreen.
        Box(Modifier.absoluteOffset { IntOffset(press.x.roundToInt(), press.y.roundToInt()) }.size(0.dp)) {
            DropdownMenu(
                expanded = expanded && available,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 192.dp, max = 280.dp).testTag("message_action_menu"),
                shape = MaterialTheme.shapes.extraLarge,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 0.dp,
            ) {
                if (reply != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_reply)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Reply, contentDescription = null) },
                        contentPadding = PaddingValues(horizontal = spacing.xl, vertical = spacing.xs),
                        onClick = {
                            expanded = false
                            reply(message)
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("message_action_reply"),
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.content_desc_copy)) },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    contentPadding = PaddingValues(horizontal = spacing.xl, vertical = spacing.xs),
                    onClick = {
                        expanded = false
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("message_action_copy"),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_message_select)) },
                    leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                    contentPadding = PaddingValues(horizontal = spacing.xl, vertical = spacing.xs),
                    onClick = {
                        expanded = false
                        selecting = true
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("message_action_select"),
                )
            }
        }
    }
}
