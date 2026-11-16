package com.m57.hermescontrol.career

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.m57.hermescontrol.ui.chat.ChatBubble
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.fullbleed.FullBleedAgentMessage
import java.time.Instant

/** Shared prose, bubble and message actions; execution authority stays in the Career conversation. */
@Composable
internal fun CareerConversationMessage(message: CareerCoachMessage) {
    val timestamp = runCatching { Instant.parse(message.created_at).toEpochMilli() }.getOrNull()
    val chat =
        ChatMessage(
            id = message.id,
            role = if (message.role == "user") MessageRole.USER else MessageRole.ASSISTANT,
            content = message.text,
            timestamp = timestamp ?: 0L,
        )
    // Compatibility snapshots retain the same actions without inventing a displayed time.
    Column(Modifier.fillMaxWidth().testTag("career_message_${message.id}")) {
        if (message.role == "user") {
            ChatBubble(chat, showTimestamp = timestamp != null)
        } else {
            FullBleedAgentMessage(
                chat,
                showTurnHeader = true,
                speakerName = "就活コーチ",
                isDarkTheme = isSystemInDarkTheme(),
                showTimestamp = timestamp != null,
            )
        }
    }
}
