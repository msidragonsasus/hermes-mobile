package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.ChatMessage

internal fun ChatTurn.Agent.tabKey(): String =
    when (val first = entries.first()) {
        is AgentEntry.Prose -> first.message.id
        is AgentEntry.ToolRow -> first.message.id
        is AgentEntry.SystemEvent -> first.message.id
        is AgentEntry.ConnectionEvents -> first.messages.first().id
    }

internal fun ChatTurn.Agent.reasoningMessage(): ChatMessage? =
    entries.filterIsInstance<AgentEntry.Prose>().firstOrNull { it.message.reasoningText.isNotBlank() }?.message

/** True when the turn has reasoning or tool calls to fold behind the activity row. */
internal fun ChatTurn.Agent.hasActivity(): Boolean =
    reasoningMessage() != null || entries.any { it is AgentEntry.ToolRow }

/**
 * The answer always shows; tool rows fold away until the activity row is opened.
 * Approval and clarification must never disappear behind it.
 */
internal fun AgentEntry.visibleWhen(activityExpanded: Boolean): Boolean =
    when (this) {
        is AgentEntry.Prose -> true

        is AgentEntry.ToolRow -> {
            activityExpanded || message.approvalInfo != null || message.clarifyInfo != null
        }

        is AgentEntry.SystemEvent, is AgentEntry.ConnectionEvents -> true
    }

/**
 * One quiet line ("推論・ツール 9件 ›") that folds a turn's reasoning and tool
 * calls, in the style of Claude and Codex. Collapsed by default.
 */
@Composable
internal fun AgentTurnActivityRow(
    expanded: Boolean,
    hasReasoning: Boolean,
    toolCount: Int,
    toolRunning: Boolean,
    onToggle: () -> Unit,
) {
    val parts =
        buildList {
            if (hasReasoning) add(stringResource(R.string.chat_turn_activity_reasoning))
            if (toolCount > 0) add(pluralStringResource(R.plurals.chat_turn_activity_tools, toolCount, toolCount))
        }
    val label =
        parts.joinToString(stringResource(R.string.chat_turn_activity_separator)) +
            if (toolRunning) " · " + stringResource(R.string.group_chat_tool_running) else ""
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier =
            Modifier
                .padding(horizontal = 16.dp)
                .toggleable(value = expanded, role = Role.Button, onValueChange = { onToggle() })
                .padding(vertical = 6.dp)
                .testTag("agent_turn_activity"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
        Spacer(modifier = Modifier.width(2.dp))
        Icon(
            imageVector =
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
    }
}
