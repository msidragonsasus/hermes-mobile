package com.m57.hermescontrol.career

import android.content.ClipData
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.ChatBubble
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MarkdownText
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.fullbleed.FullBleedAgentMessage
import kotlinx.coroutines.launch
import java.time.Instant

/** Reuse the host prose/bubble renderers without its session, attachments or execution authority. */
@Composable
internal fun CareerConversationMessage(message: CareerCoachMessage) {
    val spacing = LocalSpacing.current
    val user = message.role == "user"
    val timestamp = runCatching { Instant.parse(message.created_at).toEpochMilli() }.getOrNull()
    Column(Modifier.fillMaxWidth().testTag("career_message_${message.id}")) {
        if (timestamp != null) {
            val chat =
                ChatMessage(
                    id = message.id,
                    role = if (user) MessageRole.USER else MessageRole.ASSISTANT,
                    content = message.text,
                    timestamp = timestamp,
                    isStreaming = false,
                )
            if (user) {
                ChatBubble(chat)
            } else {
                Text(
                    "就活コーチ",
                    Modifier.padding(horizontal = spacing.lg),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FullBleedAgentMessage(chat, showTurnHeader = false, isDarkTheme = isSystemInDarkTheme())
            }
        } else if (user) {
            // Older/compatibility snapshots may not have a timestamp: never invent a sent time.
            Column(Modifier.fillMaxWidth().padding(horizontal = spacing.md), horizontalAlignment = Alignment.End) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.widthIn(max = 360.dp),
                ) {
                    SelectionContainer {
                        Text(message.text, Modifier.padding(spacing.md), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        } else {
            val clipboard = LocalClipboard.current
            val scope = rememberCoroutineScope()
            Column(
                Modifier.fillMaxWidth().padding(horizontal = spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    "就活コーチ",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer { MarkdownText(message.text, MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, message.text))) }
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "回答をコピー")
                }
            }
        }
    }
}
