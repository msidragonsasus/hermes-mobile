package com.m57.hermescontrol.agents

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.openclaw.OpenClawChatKey
import com.m57.hermescontrol.ui.bots.BotsViewModel
import com.m57.hermescontrol.ui.bots.group.GroupChatScreen
import com.m57.hermescontrol.ui.bots.group.GroupChatViewModel
import com.m57.hermescontrol.ui.bots.group.groupConversationViewModel
import kotlinx.serialization.Serializable

@Serializable data class AgentGroupsKey(
    val openClaw: Boolean = false,
) : NavKey

@Serializable data class BotDefaultsKey(
    val profileName: String,
) : NavKey

/** Conversation destinations stay outside application management. */
@Composable
fun AgentModeTabs(
    groups: Boolean,
    conversation: NavKey = ChatScreen,
) {
    PrimaryTabRow(selectedTabIndex = if (groups) 1 else 0, modifier = Modifier.fillMaxWidth()) {
        Tab(
            selected = !groups,
            onClick = { if (groups) NavigationController.navigateTo(conversation) },
            text = { Text("会話") },
            modifier = Modifier.testTag("agent_tab_conversations"),
        )
        Tab(
            selected = groups,
            onClick = { if (!groups) NavigationController.navigateTo(AgentGroupsKey(conversation == OpenClawChatKey)) },
            text = { Text("グループ") },
            modifier = Modifier.testTag("agent_tab_groups"),
        )
    }
}

@Composable
fun AgentGroupsScreen(
    openClaw: Boolean = false,
    bots: BotsViewModel =
        viewModel(key = "group-history-${AuthManager.uiPreferenceScope()}") {
            BotsViewModel()
        },
    conversation: GroupChatViewModel = groupConversationViewModel(),
) {
    GroupChatScreen(
        // This tab resumes the current conversation; observed state is not a new navigation request.
        groupName = "",
        onBack = { NavigationController.goBack() },
        viewModel = conversation,
        bots = bots,
        conversationReturn = if (openClaw) OpenClawChatKey else ChatScreen,
    )
}
