package com.m57.hermescontrol.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.ToolsetDetailKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNavigationTest {
    @Test
    fun openClawKeepsConversationScopeWhileDefaultsHidePrimaryTabs() {
        val navigation = shell()
        navigation.select(ShellTab.AGENT)
        val chat = com.m57.hermescontrol.openclaw.OpenClawChatKey
        val settings = com.m57.hermescontrol.openclaw.OpenClawSettingsKey
        navigation.navigate(chat)
        assertFalse(navigation.isInManagement)
        navigation.navigate(settings)
        assertTrue(navigation.isInManagement)
        navigation.navigate(chat)
        assertFalse(navigation.isInManagement)
        assertEquals(listOf(ChatScreen, chat), navigation.backStack.toList())
        navigation.navigate(
            com.m57.hermescontrol.agents
                .BotDefaultsKey("other-bot"),
        )
        assertTrue(navigation.isInManagement)
        navigation.navigate(chat)
        assertFalse(navigation.isInManagement)
    }

    @Test
    fun coldStartDefaultsToHomeAndRestoresCareerWithoutTemporaryDetails() {
        assertEquals(ShellTab.TODAY to TodayScreen, ShellResume.decode(null))
        assertEquals(ShellTab.TODAY to TodayScreen, ShellResume.decode("ACTIVITY|ActivityScreen"))
        assertEquals(ShellTab.TODAY to TodayScreen, ShellResume.decode("TODAY|TodayScreen"))
        assertEquals(ShellTab.AGENT to ChatScreen, ShellResume.decode("CHAT|ChatScreen"))
        assertEquals(listOf(ShellTab.TODAY, ShellTab.AGENT, ShellTab.APPS), ShellTab.primary)
        val career = com.m57.hermescontrol.career.CareerScreenKey
        val value = ShellResume.encode(ShellTab.APPS, listOf(AppsScreen, career, ToolsetDetailKey("temporary")))
        assertEquals(ShellTab.APPS to career, ShellResume.decode(value))
        val pcPower = com.m57.hermescontrol.pcpower.PcPowerScreenKey
        val powerValue = ShellResume.encode(ShellTab.APPS, listOf(AppsScreen, pcPower))
        assertEquals(ShellTab.APPS to pcPower, ShellResume.decode(powerValue))
        assertEquals(ShellTab.APPS to AppsScreen, ShellResume.decode("APPS|LandingScreen"))
    }

    private fun shell() =
        ShellNavigation(
            mutableStateOf(ShellTab.TODAY),
            ShellTab.primary.associateWith { NavBackStack<NavKey>(it.root) },
        )

    @Test
    fun tabSwitchPreservesIndependentStacksAndRepeatedSelection() {
        val shell = shell()
        shell.navigate(SettingsScreen)
        shell.select(ShellTab.AGENT)
        val detail = ToolsetDetailKey("tools/one", "日本語")
        shell.navigate(detail)
        shell.select(ShellTab.TODAY)
        assertEquals(listOf(TodayScreen, SettingsScreen), shell.backStack.toList())
        shell.select(ShellTab.AGENT)
        shell.select(ShellTab.AGENT)
        assertEquals(listOf(ChatScreen, detail), shell.backStack.toList())
    }

    @Test
    fun rootNavigationRevealsChatWithoutDiscardingOtherTabs() {
        val shell = shell()
        shell.navigate(SettingsScreen)
        shell.select(ShellTab.AGENT)
        shell.navigate(ToolsetDetailKey("one"))
        shell.select(ShellTab.TODAY)
        shell.navigate(ChatScreen)
        assertEquals(ShellTab.AGENT, shell.selectedTab)
        assertEquals(listOf(ChatScreen), shell.backStack.toList())
        shell.select(ShellTab.TODAY)
        assertEquals(listOf(TodayScreen, SettingsScreen), shell.backStack.toList())
    }

    @Test
    fun accountMenuIsLimitedToEachTabRoot() {
        val shell = shell()
        assertTrue(shell.isAtRoot)

        ShellTab.primary.forEach { tab ->
            shell.select(tab)
            assertTrue(shell.isAtRoot)
            shell.navigate(SettingsScreen)
            assertFalse(shell.isAtRoot)
        }
    }

    @Test
    fun navigatingToExistingDestinationPopsToItAndDoesNotDuplicate() {
        val shell = shell()
        shell.navigate(AdvancedScreen)
        shell.navigate(SettingsScreen)
        shell.navigate(ToolsetDetailKey("one"))
        shell.navigate(SettingsScreen)
        shell.navigate(SettingsScreen)
        assertEquals(listOf(TodayScreen, AdvancedScreen, SettingsScreen), shell.backStack.toList())
    }
}
