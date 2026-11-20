package com.m57.hermescontrol.ui.chat.fullbleed

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ImageViewerModel
import com.m57.hermescontrol.ui.chat.InlineAttachment
import com.m57.hermescontrol.ui.chat.MarkdownText
import com.m57.hermescontrol.ui.chat.components.MessageActionHost
import com.m57.hermescontrol.ui.chat.components.ReasoningCard
import com.m57.hermescontrol.ui.common.BotAvatar

/**
 * Full-bleed renderer for ONE agent (assistant) message (issue #866).
 *
 * Unlike [com.m57.hermescontrol.ui.chat.ChatBubble], agent prose renders
 * directly on the background — no bubble container, no width cap — with a
 * turn header (role + time) and shared long-press message actions. User messages
 * keep their bubbles; this composable is only used for ASSISTANT messages.
 *
 * [showTurnHeader] renders the role/time header — true only for the first
 * prose entry of an agent turn so multi-message turns don't repeat it.
 */
@Composable
internal fun FullBleedAgentMessage(
    message: ChatMessage,
    showTurnHeader: Boolean,
    speakerName: String? = null,
    isDarkTheme: Boolean,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
    showReasoning: Boolean = true,
    onOpenAttachment: (Attachment) -> Unit = {},
    onSaveAttachment: (Attachment) -> Unit = {},
    savingAttachmentPath: String? = null,
    openingAttachmentPath: String? = null,
    canSaveAttachment: Boolean = true,
    onImageClick: ((ImageViewerModel) -> Unit)? = null,
    modifier: Modifier = Modifier,
    showTimestamp: Boolean = true,
    showFooter: Boolean = true,
    actionMessage: ChatMessage = message,
    allowInteractiveContent: Boolean = true,
    personalStyle: Boolean = false,
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    var localImage by remember { mutableStateOf<ImageViewerModel?>(null) }
    val openImage: (ImageViewerModel) -> Unit = onImageClick ?: { localImage = it }
    localImage?.let { image ->
        com.m57.hermescontrol.ui.chat
            .ImageViewerDialog(image, onDismiss = { localImage = null })
    }

    val bodyModifier =
        if (personalStyle) {
            Modifier
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        } else {
            Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        }
    MessageActionHost(message = actionMessage, modifier = modifier, useDialog = personalStyle) {
        Column(
            modifier =
                bodyModifier.testTag(if (personalStyle) "chat_assistant_bubble" else "fullbleed_agent_message"),
        ) {
            if (showTurnHeader) {
                DisableSelection {
                    AssistantTurnHeader(message.timestamp, speakerName = speakerName, showTimestamp = showTimestamp)
                }
            }

            if (showReasoning && message.reasoningText.isNotBlank()) {
                ReasoningCard(
                    reasoningText = message.reasoningText,
                    isStreaming = message.isStreaming,
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Defense-in-depth: never render an empty prose block (blank bubble +
            // message actions). Until the first text delta, the turn's thinking
            // indicator and activity row represent its progress.
            if (message.content.isNotBlank()) {
                MarkdownText(
                    text = message.content,
                    textColor = textColor,
                    isStreaming = message.isStreaming,
                    searchQuery = searchQuery,
                    isCurrentMatch = isCurrentMatch,
                    onImageClick = openImage,
                    onOpenAttachment = onOpenAttachment,
                    allowInteractiveContent = allowInteractiveContent,
                    bodyStyle =
                        if (personalStyle) {
                            MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp)
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                    fillWidth = !personalStyle,
                )
            }

            // Render inline attachments (mirrors ChatBubble so agent-delivered
            // media — images, files — shows in full-bleed mode too).
            if (!message.attachments.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                message.attachments.forEach { attachment ->
                    InlineAttachment(
                        attachment = attachment,
                        textColor = textColor,
                        onOpen = { onOpenAttachment(it) },
                        onSave = { onSaveAttachment(it) },
                        savingPath = savingAttachmentPath,
                        openingPath = openingAttachmentPath,
                        canSave = canSaveAttachment,
                        onImageClick = openImage,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            if (showFooter && !message.isStreaming && showTimestamp && message.content.isNotBlank()) {
                Text(
                    text =
                        com.m57.hermescontrol.ui.chat.formatTimestamp(
                            message.finishTimestamp ?: message.timestamp,
                            DateFormat.is24HourFormat(LocalContext.current),
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp).testTag("fullbleed_finish_time"),
                )
            }
        }
    }
}

/** The agent this conversation is with: its own name and the avatar set in bot settings. */
data class ChatAgentIdentity(
    val name: String,
    val avatar: BotAvatarMeta?,
)

/** Provided by the chat screen so every turn header names the active agent. */
val LocalChatAgentIdentity = staticCompositionLocalOf<ChatAgentIdentity?> { null }

/**
 * Agent turn header. With a [LocalChatAgentIdentity] it shows the agent's
 * avatar and name only (the finish time already sits under the answer);
 * otherwise "Agent · <time>", or [speakerName] in group chats.
 */
@Composable
internal fun AssistantTurnHeader(
    timestamp: Long,
    modifier: Modifier = Modifier,
    speakerName: String? = null,
    showTimestamp: Boolean = true,
) {
    val identity = if (speakerName == null) LocalChatAgentIdentity.current else null
    Row(
        modifier = modifier.padding(bottom = 4.dp).testTag("fullbleed_agent_header"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (identity != null) {
            BotAvatar(
                name = identity.name,
                avatar = identity.avatar,
                size = 22.dp,
                showPresence = false,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = speakerName ?: identity?.name ?: stringResource(R.string.fullbleed_role_agent),
            style = MaterialTheme.typography.labelLargeEmphasized,
            // Meta color (not primary): Nord's light mode reuses its
            // pastel Frost accent as primary, which fails >= 3:1 on a
            // light background. The header is metadata, so the dimmed
            // meta token is both more correct and gate-compliant in
            // every preset.
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showTimestamp && identity == null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text =
                    com.m57.hermescontrol.ui.chat.formatTimestamp(
                        timestamp,
                        DateFormat.is24HourFormat(LocalContext.current),
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One completion time for a finished response; its actions live on the prose menu. */
@Composable
internal fun ReplyFooterRow(
    finishedAt: Long,
    modifier: Modifier = Modifier,
) {
    Text(
        text =
            com.m57.hermescontrol.ui.chat.formatTimestamp(
                finishedAt,
                DateFormat.is24HourFormat(LocalContext.current),
            ),
        modifier = modifier.testTag("fullbleed_finish_time"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
