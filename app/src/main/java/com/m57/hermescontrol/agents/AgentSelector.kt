package com.m57.hermescontrol.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.BotsScreen
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.ui.bots.BotsViewModel
import com.m57.hermescontrol.ui.common.BotAvatar
import kotlinx.coroutines.launch

/** The default Hermes profile is shown as "Hermes"; bots (other profiles) by their own title. */
internal fun ProfileInfo.agentLabel(): String =
    if (is_default == true ||
        name == "default"
    ) {
        "Hermes"
    } else {
        effectiveTitle
    }

/**
 * Who you are talking to. Each bot is a Hermes profile with its own memory, skills and
 * conversation, so picking one switches the active profile and opens *that* bot's conversation;
 * going back to Hermes reopens Hermes's own. The list ends with a shortcut to bot management.
 * Agents that cannot receive messages yet (bro) are not offered here; see App management.
 */
@Composable
fun AgentSelector(
    openClaw: Boolean = false,
    botsViewModel: BotsViewModel = viewModel { BotsViewModel() },
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val state by botsViewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val profiles =
        state.profiles.filterNot { it.isHidden || it.name in state.hiddenProfiles }
    val active = profiles.firstOrNull { it.name == state.activeProfileName }
    val title = if (openClaw) "OpenClaw" else active?.agentLabel() ?: "Hermes"

    ConversationTitleSelector(
        title = title,
        onClick = {
            botsViewModel.loadBots(isRefresh = true)
            expanded = true
        },
    ) {
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (profiles.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Hermes") },
                    trailingIcon = if (!openClaw) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null,
                    onClick = {
                        expanded = false
                        if (openClaw) NavigationController.navigateTo(ChatScreen)
                    },
                    modifier = Modifier.testTag("agent_option_hermes"),
                )
            }
            profiles.forEach { profile ->
                val isActive = !openClaw && profile.name == state.activeProfileName
                DropdownMenuItem(
                    leadingIcon = {
                        BotAvatar(
                            name = profile.name,
                            avatar = profile.botMeta()?.avatar,
                            size = 32.dp,
                            showPresence = false,
                        )
                    },
                    text = {
                        Column(Modifier.widthIn(max = 220.dp)) {
                            Text(profile.agentLabel(), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            profile.effectiveDescription.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    trailingIcon = if (isActive) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null,
                    onClick = {
                        expanded = false
                        if (!isActive) {
                            if (openClaw && profile.name == state.activeProfileName) {
                                openConversationOf(profile)
                                return@DropdownMenuItem
                            }
                            scope.launch {
                                if (botsViewModel.selectBot(profile)) {
                                    botsViewModel.loadBots(isRefresh = true)
                                    openConversationOf(profile)
                                }
                            }
                        }
                    },
                    modifier =
                        Modifier.testTag(
                            if (profile.agentLabel() ==
                                "Hermes"
                            ) {
                                "agent_option_hermes"
                            } else {
                                "agent_option_${profile.name}"
                            },
                        ),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("OpenClaw") },
                trailingIcon = if (openClaw) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null,
                onClick = {
                    expanded = false
                    NavigationController.navigateTo(com.m57.hermescontrol.openclaw.OpenClawChatKey)
                },
                modifier = Modifier.testTag("agent_option_openclaw"),
            )
            HorizontalDivider()
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                text = { Text(stringResource(R.string.agent_selector_manage_bots)) },
                onClick = {
                    expanded = false
                    NavigationController.navigateTo(BotsScreen)
                },
                modifier = Modifier.testTag("agent_manage_bots"),
            )
        }
    }
}

/** Shared selector surface; each conversation mode owns its selection behavior. */
@Composable
internal fun ConversationTitleSelector(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable () -> Unit = {},
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.DropdownList, onClick = onClick)
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                .testTag("agent_selector"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.agent_select),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        menu()
    }
}

/** Continue the profile's own conversation; a profile that never chatted starts a fresh one. */
private fun openConversationOf(profile: ProfileInfo) {
    val sessionId =
        profile.canonical_session?.resolved_id
            ?: profile.canonical_session?.id
            ?: profile.last_session?.id
    if (!sessionId.isNullOrBlank()) {
        NavigationController.openChatSession(sessionId)
    } else {
        NavigationController.navigateTo(ChatScreen)
    }
}
