package com.m57.hermescontrol.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.ui.common.ShellBottomBar
import com.m57.hermescontrol.ui.common.ShellMenu
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ShellChromeTest {
    @get:Rule val compose = createComposeRule()

    @Before fun preferences() {
        mockkObject(AuthManager)
        every { AuthManager.uiPreferenceScope() } returns "navigation-test"
        every { AuthManager.readUiPreference("navigation-test", "last_screen") } returns "TODAY|TodayScreen"
        every { AuthManager.writeUiPreference("navigation-test", "last_screen", any()) } returns Unit
    }

    @After fun cleanPreferences() {
        unmockkObject(AuthManager)
    }

    @Test
    fun appsRestoresAcrossTabsAndReselectRevealsRootWhileChatAndAgentKeepRootBehavior() {
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

        compose.runOnIdle { assertEquals(listOf(AppsScreen, AdvancedScreen), shell.backStack.toList()) }
        compose.onNodeWithTag("shell_tab_today").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(TodayScreen, SettingsScreen), shell.backStack.toList()) }
        compose.onNodeWithTag("shell_tab_agent").performClick().assertIsSelected()
        compose.onNodeWithTag("shell_tab_activity").assertDoesNotExist()
        compose.onNodeWithTag("shell_tab_chat").assertDoesNotExist()
        compose.onNodeWithText("ホーム").assertIsDisplayed()
        compose.onNodeWithTag("shell_tab_apps").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(AppsScreen, AdvancedScreen), shell.backStack.toList()) }
        compose.onNodeWithTag("shell_tab_apps").performClick()
        compose.runOnIdle { assertEquals(listOf(AppsScreen), shell.backStack.toList()) }

        // Reselecting a tab from a detail must also reveal its root, without duplicates.
        listOf(ShellTab.APPS, ShellTab.AGENT).forEach { tab ->
            compose.onNodeWithTag("shell_tab_${tab.name.lowercase()}").performClick()
            compose.runOnIdle { NavigationController.navigateTo(SettingsScreen) }
            repeat(2) { compose.onNodeWithTag("shell_tab_${tab.name.lowercase()}").performClick() }
            compose.runOnIdle { assertEquals(listOf(tab.root), shell.backStack.toList()) }
        }

        // Switching to Chat must discard its former detail, too.
        compose.runOnIdle { NavigationController.navigateTo(AdvancedScreen) }
        compose.onNodeWithTag("shell_tab_apps").performClick()
        compose.onNodeWithTag("shell_tab_agent").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(ChatScreen), shell.backStack.toList()) }
        compose.onNodeWithTag("shell_tab_today").performClick()
        compose.runOnIdle { assertEquals(listOf(TodayScreen, SettingsScreen), shell.backStack.toList()) }
    }

    @Test
    fun accountMenuAppearsOnlyAtTabRoots() {
        lateinit var shell: ShellNavigation
        compose.setContent {
            shell = rememberShellNavigation(true)!!
            NavigationController.attachShell(shell)
            DisposableEffect(shell) { onDispose { NavigationController.detachShell(shell) } }
            Column {
                if (shell.isAtRoot) ShellMenu()
                ShellBottomBar(shell.selectedTab)
            }
        }

        compose.onNodeWithTag("shell_menu").assertIsDisplayed()
        compose.runOnIdle { NavigationController.navigateTo(SettingsScreen) }
        compose.onNodeWithTag("shell_menu").assertDoesNotExist()

        listOf("agent", "apps").forEach { tab ->
            compose.onNodeWithTag("shell_tab_$tab").performClick()
            compose.onNodeWithTag("shell_menu").assertIsDisplayed()
        }

        compose.runOnIdle { NavigationController.navigateTo(AdvancedScreen) }
        compose.onNodeWithTag("shell_menu").assertDoesNotExist()
    }
}
