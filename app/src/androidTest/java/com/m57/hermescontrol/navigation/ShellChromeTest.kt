package com.m57.hermescontrol.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.ui.common.ShellBottomBar
import com.m57.hermescontrol.ui.common.ShellMenu
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ShellChromeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun touchTabsPreserveHistoryAndMenuOpensAdvancedInSelectedTab() {
        lateinit var shell: ShellNavigation
        compose.setContent {
            shell = rememberShellNavigation(true)!!
            NavigationController.attachShell(shell)
            DisposableEffect(shell) { onDispose { NavigationController.detachShell(shell) } }
            Column {
                ShellMenu()
                ShellBottomBar(shell.selectedTab)
            }
        }
        compose.runOnIdle { NavigationController.navigateTo(SettingsScreen) }
        compose.onNodeWithTag("shell_tab_apps").performClick().assertIsSelected()
        compose.onNodeWithTag("shell_menu").performClick()
        compose.onNodeWithText("Advanced").performClick()
        compose.runOnIdle { assertEquals(listOf(AppsScreen, AdvancedScreen), shell.backStack.toList()) }
        compose.onNodeWithTag("shell_tab_today").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(TodayScreen, SettingsScreen), shell.backStack.toList()) }
        compose.onNodeWithTag("shell_tab_chat").performClick().assertIsSelected()
        compose.onNodeWithTag("shell_tab_activity").performClick().assertIsSelected()
        compose.onNodeWithTag("shell_tab_apps").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(AppsScreen, AdvancedScreen), shell.backStack.toList()) }
    }
}
