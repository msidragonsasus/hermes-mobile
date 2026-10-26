package com.m57.hermescontrol.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon

/** Compatibility entry for the old hub route; Hermes uses its existing chat and ViewModel. */
@Composable
fun AgentHubScreen(
    sessionId: String? = null,
    onOpenDrawer: (() -> Unit)? = null,
) {
    com.m57.hermescontrol.ui.chat
        .ChatScreen(sessionId = sessionId, onOpenDrawer = onOpenDrawer)
}

@Composable
fun AgentDetailScreen(key: AgentDetailKey) {
    val agent = agentIdFromRoute(key.agentId)?.let(AgentCatalog::find)
    val spacing = LocalSpacing.current
    HermesScaffold(
        title = { Text(agent?.displayName ?: stringResource(R.string.shell_agent)) },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
    ) {
        if (agent == null) {
            EmptyState(
                title = stringResource(R.string.agent_unavailable_title),
                subtitle = stringResource(R.string.agent_unavailable_body),
            )
        } else {
            LazyColumn(
                modifier = Modifier.testTag("agent_detail_list"),
                contentPadding = PaddingValues(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                item {
                    Text(
                        stringResource(R.string.agent_destination, agent.displayName),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    UnverifiedStatus()
                }
                item {
                    Text(stringResource(agentDescription(agent.id)), style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.agent_actions_unavailable),
                            modifier = Modifier.padding(spacing.md).testTag("agent_actions_unavailable"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                item {
                    Text(stringResource(R.string.agent_capabilities), style = MaterialTheme.typography.titleMedium)
                }
                items(AgentCapability.entries, key = { it.name }) { capability ->
                    // No actions are exposed by this candidate. A missing capability is never Supported.
                    Column(Modifier.testTag("agent_capability_${capability.name.lowercase()}")) {
                        Text(stringResource(capabilityLabel(capability)), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(capabilityStateLabel(agent.capabilityState(capability))),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item {
                    EmptyState(
                        title = stringResource(R.string.agent_history_title, agent.displayName),
                        subtitle = stringResource(R.string.agent_history_unverified),
                        modifier = Modifier.fillMaxWidth().testTag("agent_history_unverified"),
                    )
                }
            }
        }
    }
}

@Composable
private fun UnverifiedStatus() {
    val color = LocalHermesStatusColors.current.warning
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        Icon(Icons.Default.Info, contentDescription = null, tint = color)
        Text(
            stringResource(R.string.agent_connection_unverified),
            color = color,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun agentDescription(id: AgentId): Int =
    when (id) {
        AgentId.BRO -> R.string.agent_bro_description
        AgentId.HERMES -> R.string.agent_hermes_description
    }

private fun capabilityLabel(capability: AgentCapability): Int =
    when (capability) {
        AgentCapability.SUBMIT_REQUEST -> R.string.agent_capability_submit
        AgentCapability.READ_REQUESTS -> R.string.agent_capability_requests
        AgentCapability.READ_RESULTS -> R.string.agent_capability_results
        AgentCapability.READ_PROGRESS -> R.string.agent_capability_progress
        AgentCapability.RESPOND_TO_APPROVAL -> R.string.agent_capability_approval
        AgentCapability.CANCEL_REQUEST -> R.string.agent_capability_cancel
    }

private fun capabilityStateLabel(state: AgentCapabilityState): Int =
    when (state) {
        AgentCapabilityState.UNKNOWN -> R.string.agent_capability_unknown
        AgentCapabilityState.UNSUPPORTED -> R.string.agent_capability_unsupported
        AgentCapabilityState.SUPPORTED -> R.string.agent_capability_supported
        AgentCapabilityState.BLOCKED -> R.string.agent_capability_blocked
    }
