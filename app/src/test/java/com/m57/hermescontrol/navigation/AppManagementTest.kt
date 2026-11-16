package com.m57.hermescontrol.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.CronJobsScreen
import com.m57.hermescontrol.HistoryScreen
import com.m57.hermescontrol.ScreenRegistry
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.ToolsetDetailKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppManagementTest {
    @Test
    fun directoryKeepsAllManagementDestinationsExactlyOnceWithoutConversationEntries() {
        val keys = AppManagement.sections.flatMap { it.entries }.map { it.key }
        val expected =
            ScreenRegistry.ALL_SCREENS.map { it.key }.toSet() - setOf(ChatScreen, HistoryScreen) +
                setOf(
                    SettingsConnection,
                    com.m57.hermescontrol.SettingsExternalServices,
                    com.m57.hermescontrol.SettingsPasswords,
                    ActivityScreen,
                    MiniAppExamplesScreen,
                    com.m57.hermescontrol.WorkScreen,
                    com.m57.hermescontrol.agents
                        .AgentDetailKey("BRO"),
                )
        assertEquals(expected, keys.toSet())
        assertEquals(keys.toSet().size, keys.size)
        assertFalse(ChatScreen in keys)
        assertFalse(HistoryScreen in keys)
    }

    @Test
    fun everyManagementEntryAndItsDetailsHideThePrimaryTabSelection() {
        AppManagement.sections.flatMap { it.entries }.forEach { entry ->
            assertTrue(AppManagement.contains(listOf(ChatScreen, entry.key)))
            assertTrue(AppManagement.contains(listOf(ChatScreen, entry.key, ToolsetDetailKey("example"))))
        }
        assertTrue(AppManagement.contains(listOf(ChatScreen, AdvancedScreen)))
        assertTrue(
            AppManagement.contains(
                listOf(
                    ChatScreen,
                    com.m57.hermescontrol.agents
                        .BotSettingsKey("research"),
                ),
            ),
        )
        assertFalse(AppManagement.contains(listOf(ChatScreen)))
        assertFalse(AppManagement.contains(listOf(ChatScreen, HistoryScreen)))
    }

    @Test
    fun returningFromManagementRestoresConversationScopeAndDoesNotChangeOtherTabStacks() {
        val shell =
            ShellNavigation(
                mutableStateOf(ShellTab.AGENT),
                ShellTab.primary.associateWith { NavBackStack<NavKey>(it.root) },
            )
        shell.navigate(AdvancedScreen)
        shell.navigate(CronJobsScreen)
        assertTrue(shell.isInManagement)
        assertEquals(ShellTab.AGENT, shell.selectedTab)
        shell.backStack.removeLastOrNull()
        assertEquals(AdvancedScreen, shell.backStack.last())
        assertTrue(shell.isInManagement)
        shell.backStack.removeLastOrNull()
        assertEquals(listOf(ChatScreen), shell.backStack.toList())
        assertFalse(shell.isInManagement)
        shell.select(ShellTab.APPS)
        assertEquals(listOf(AppsScreen), shell.backStack.toList())
    }

    @Test
    fun restoredLegacyManagementDestinationStillGetsManagementScope() {
        val (tab, destination) = ShellResume.decode("CHAT|CronJobsScreen")
        assertEquals(ShellTab.AGENT, tab)
        assertTrue(AppManagement.contains(listOf(tab.root, destination)))
    }
}
