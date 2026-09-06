package com.m57.hermescontrol.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.AuthLoginScreen
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.LandingScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.ToolsetDetailKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class ShellControllerTest {
    private lateinit var shell: ShellNavigation

    @Before
    fun attach() {
        shell =
            ShellNavigation(
                mutableStateOf(ShellTab.TODAY),
                ShellTab.entries.associateWith { NavBackStack<NavKey>(it.root) },
            )
        NavigationController.attachShell(shell)
    }

    @After
    fun detach() {
        NavigationController.detachShell(shell)
        NavigationController.consumePendingChatNavigation()
    }

    @Test
    fun consecutiveCallbacksUseSelectedStackWithoutRecomposition() {
        NavigationController.navigateTo(SettingsScreen)
        NavigationController.selectTab(ShellTab.CHAT)
        val detail = ToolsetDetailKey("tools")
        NavigationController.navigateTo(detail)
        NavigationController.navigateTo(detail)
        assertSame(shell.backStack, NavigationController.backStack)
        assertEquals(listOf(ChatScreen, detail), shell.backStack.toList())
        NavigationController.goBack()
        NavigationController.goBack()
        assertEquals(ShellTab.TODAY, shell.selectedTab)
        assertEquals(listOf(TodayScreen, SettingsScreen), shell.backStack.toList())
    }

    @Test
    fun notificationOpensChatRootAndRetainsOtherTabHistory() {
        NavigationController.navigateTo(AdvancedScreen)
        NavigationController.openChatSessionFromNotification("session-one")
        assertEquals(ShellTab.CHAT, shell.selectedTab)
        assertEquals(listOf(ChatScreen), shell.backStack.toList())
        assertEquals("session-one", NavigationController.pendingSessionId)
        NavigationController.selectTab(ShellTab.TODAY)
        assertEquals(listOf(TodayScreen, AdvancedScreen), shell.backStack.toList())
    }

    @Test
    fun authAttachmentSurvivesPreviousShellDisposalAndStopsPrivateTabRouting() {
        val auth = NavBackStack<NavKey>(LandingScreen)
        NavigationController.attachAuth(auth)
        NavigationController.detachShell(shell)
        NavigationController.selectTab(ShellTab.CHAT)
        NavigationController.navigateTo(AuthLoginScreen)
        assertSame(auth, NavigationController.backStack)
        assertEquals(listOf(LandingScreen, AuthLoginScreen), auth.toList())
        NavigationController.backStack = null
    }

    @Test
    fun disposingOldShellCannotDetachNewAttachment() {
        val replacement =
            ShellNavigation(
                mutableStateOf(ShellTab.APPS),
                ShellTab.entries.associateWith { NavBackStack<NavKey>(it.root) },
            )
        NavigationController.attachShell(replacement)
        NavigationController.detachShell(shell)
        assertSame(replacement.backStack, NavigationController.backStack)
        NavigationController.detachShell(replacement)
    }
}
