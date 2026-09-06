package com.m57.hermescontrol.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.ToolsetDetailKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellNavigationTest {
    private fun shell() =
        ShellNavigation(
            mutableStateOf(ShellTab.TODAY),
            ShellTab.entries.associateWith { NavBackStack<NavKey>(it.root) },
        )

    @Test
    fun tabSwitchPreservesIndependentStacksAndRepeatedSelection() {
        val shell = shell()
        shell.navigate(SettingsScreen)
        shell.select(ShellTab.CHAT)
        val detail = ToolsetDetailKey("tools/one", "日本語")
        shell.navigate(detail)
        shell.select(ShellTab.TODAY)
        assertEquals(listOf(TodayScreen, SettingsScreen), shell.backStack.toList())
        shell.select(ShellTab.CHAT)
        shell.select(ShellTab.CHAT)
        assertEquals(listOf(ChatScreen, detail), shell.backStack.toList())
    }

    @Test
    fun rootNavigationRevealsChatWithoutDiscardingOtherTabs() {
        val shell = shell()
        shell.navigate(SettingsScreen)
        shell.select(ShellTab.CHAT)
        shell.navigate(ToolsetDetailKey("one"))
        shell.select(ShellTab.TODAY)
        shell.navigate(ChatScreen)
        assertEquals(ShellTab.CHAT, shell.selectedTab)
        assertEquals(listOf(ChatScreen), shell.backStack.toList())
        shell.select(ShellTab.TODAY)
        assertEquals(listOf(TodayScreen, SettingsScreen), shell.backStack.toList())
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
