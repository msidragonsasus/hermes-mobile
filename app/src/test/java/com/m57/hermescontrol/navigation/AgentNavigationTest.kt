package com.m57.hermescontrol.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.HistoryScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.agents.AgentDetailKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentNavigationTest {
    private lateinit var shell: ShellNavigation

    @Before
    fun attach() {
        shell =
            ShellNavigation(
                mutableStateOf(ShellTab.TODAY),
                ShellTab.primary.associateWith { NavBackStack<NavKey>(it.root) },
            )
        NavigationController.attachShell(shell)
    }

    @After
    fun detach() {
        NavigationController.detachShell(shell)
        NavigationController.consumePendingChatNavigation()
    }

    @Test
    fun threeTabsUseTheOriginalHermesConversationRoot() {
        assertEquals(listOf(ShellTab.TODAY, ShellTab.AGENT, ShellTab.APPS), ShellTab.primary)
        assertEquals(listOf(TodayScreen, ChatScreen, AppsScreen), ShellTab.primary.map { it.root })
        NavigationController.navigateTo(AgentScreen)
        NavigationController.navigateTo(HistoryScreen)
        NavigationController.navigateTo(HistoryScreen)
        assertEquals(listOf(ChatScreen, HistoryScreen), shell.backStack.toList())
        NavigationController.selectTab(ShellTab.APPS)
        NavigationController.selectTab(ShellTab.CHAT)
        assertEquals(ShellTab.AGENT, shell.selectedTab)
        assertEquals(listOf(ChatScreen, HistoryScreen), shell.backStack.toList())
    }

    @Test
    fun legacyHubAndHermesDetailRoutesNeverAddAnotherChat() {
        NavigationController.navigateTo(ChatScreen)
        NavigationController.navigateTo(AgentDetailKey("BRO"))
        repeat(2) { NavigationController.navigateTo(AgentDetailKey("HERMES")) }
        NavigationController.navigateTo(AgentScreen)
        assertEquals(listOf(ChatScreen), shell.backStack.toList())
        NavigationController.goBack()
        assertEquals(ShellTab.TODAY, shell.selectedTab)
    }

    @Test
    fun explicitSessionTargetsKeepIdWhileNotificationLandsInMainChat() {
        NavigationController.navigateTo(AgentScreen)
        NavigationController.navigateTo(HistoryScreen)
        NavigationController.openChatSession("existing-session")
        assertEquals(ShellTab.AGENT, shell.selectedTab)
        assertEquals(listOf(ChatScreen), shell.backStack.toList())
        assertEquals("existing-session", NavigationController.consumePendingSessionId())
        repeat(2) { NavigationController.openChatSessionFromNotification("notification-session") }
        assertEquals(listOf(ChatScreen), shell.backStack.toList())
        assertNull(NavigationController.pendingSessionId)
        assertTrue(NavigationController.consumePendingChatNavigation()?.scrollToBottom == true)
    }

    @Test
    fun oldPreferencesAndSavedStacksMigrateWithoutDiscardingHistoryOrManagement() {
        assertEquals(ShellTab.AGENT to ChatScreen, ShellResume.decode("CHAT|ChatScreen"))
        assertEquals(ShellTab.AGENT to HistoryScreen, ShellResume.decode("CHAT|HistoryScreen"))
        assertEquals(ShellTab.AGENT to ChatScreen, ShellResume.decode("AGENT|AgentScreen"))
        assertEquals(ShellTab.AGENT to ChatScreen, ShellResume.decode("AGENT|AgentDetailKey(agentId=BRO)"))
        assertEquals(listOf(ChatScreen, HistoryScreen), migrateAgentStack(listOf(ChatScreen, HistoryScreen)))
        assertEquals(
            listOf(ChatScreen, AgentDetailKey("BRO")),
            migrateAgentStack(listOf(AgentScreen, AgentDetailKey("BRO"))),
        )
        assertEquals(listOf(ChatScreen), migrateAgentStack(listOf(AgentScreen, AgentDetailKey("HERMES"), ChatScreen)))
        assertEquals("AGENT|ChatScreen", ShellResume.encode(ShellTab.CHAT, listOf(ChatScreen)))
    }

    @Test
    fun offlineConnectionSettingsRemainReachableAndReturnToTheSameHermesRoot() {
        // Navigation does not require a live transport or replace the existing offline Chat state.
        NavigationController.navigateTo(AgentScreen)
        NavigationController.navigateTo(SettingsConnection)
        NavigationController.goBack()
        assertEquals(ShellTab.AGENT, shell.selectedTab)
        assertEquals(listOf(ChatScreen), shell.backStack.toList())
    }
}
