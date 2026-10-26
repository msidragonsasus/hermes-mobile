package com.m57.hermescontrol.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.agents.AgentDetailKey
import com.m57.hermescontrol.career.CareerCompanyKey
import com.m57.hermescontrol.career.CareerMyPageBrowserKey
import com.m57.hermescontrol.career.CareerScreenKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class AppsTabRestoreTest {
    private lateinit var shell: ShellNavigation
    private val company = CareerCompanyKey("synthetic-company")

    @Before
    fun attach() {
        shell =
            ShellNavigation(
                mutableStateOf(ShellTab.TODAY),
                ShellTab.primary.associateWith { NavBackStack<NavKey>(it.root) },
            )
        NavigationController.attachShell(shell)
        NavigationController.selectTabFromBar(ShellTab.APPS)
        NavigationController.navigateTo(CareerScreenKey)
        NavigationController.navigateTo(company)
    }

    @After
    fun detach() {
        NavigationController.detachShell(shell)
    }

    @Test
    fun reselectingAppsStillReturnsToRoot() {
        NavigationController.selectTabFromBar(ShellTab.APPS)
        assertEquals(listOf(AppsScreen), shell.backStack.toList())
        NavigationController.selectTabFromBar(ShellTab.APPS)
        assertEquals(listOf(AppsScreen), shell.backStack.toList())
    }

    @Test
    fun homeToAppsRestoresExactStackAndNextReselectReturnsToRootWithoutRecomposition() {
        val appsStack = shell.backStack
        NavigationController.selectTabFromBar(ShellTab.TODAY)
        NavigationController.navigateTo(SettingsScreen)
        NavigationController.selectTabFromBar(ShellTab.APPS)
        assertSame(appsStack, NavigationController.backStack)
        assertEquals(listOf(AppsScreen, CareerScreenKey, company), shell.backStack.toList())
        NavigationController.selectTabFromBar(ShellTab.APPS)
        assertEquals(listOf(AppsScreen), shell.backStack.toList())
        NavigationController.selectTabFromBar(ShellTab.TODAY)
        assertEquals(listOf(TodayScreen, SettingsScreen), shell.backStack.toList())
    }

    @Test
    fun agentAndChatRoundTripsRestoreAppsWithoutChangingTheirOwnNavigation() {
        NavigationController.selectTabFromBar(ShellTab.AGENT)
        val bro = AgentDetailKey("BRO")
        NavigationController.navigateTo(bro)
        NavigationController.selectTabFromBar(ShellTab.APPS)
        assertEquals(listOf(AppsScreen, CareerScreenKey, company), shell.backStack.toList())
        NavigationController.selectTab(ShellTab.AGENT)
        assertEquals(listOf(ChatScreen, bro), shell.backStack.toList())
        NavigationController.selectTabFromBar(ShellTab.AGENT)
        assertEquals(listOf(ChatScreen), shell.backStack.toList())
        NavigationController.selectTabFromBar(ShellTab.APPS)
        assertEquals(listOf(AppsScreen, CareerScreenKey, company), shell.backStack.toList())
    }

    @Test
    fun explicitBrowserCloseAndBackStillPopOnlyTheirOwnDestinations() {
        val browser = CareerMyPageBrowserKey(company.companyId)
        NavigationController.navigateTo(browser)
        NavigationController.goBackFrom(browser)
        NavigationController.goBackFrom(browser)
        assertEquals(listOf(AppsScreen, CareerScreenKey, company), shell.backStack.toList())
        NavigationController.goBack()
        assertEquals(listOf(AppsScreen, CareerScreenKey), shell.backStack.toList())
        NavigationController.selectTabFromBar(ShellTab.TODAY)
        NavigationController.selectTabFromBar(ShellTab.APPS)
        assertEquals(listOf(AppsScreen, CareerScreenKey), shell.backStack.toList())
    }

    @Test
    fun explicitAppsRootNavigationStillClearsAppsDetails() {
        NavigationController.selectTabFromBar(ShellTab.TODAY)
        NavigationController.navigateTo(AppsScreen)
        assertEquals(ShellTab.APPS, shell.selectedTab)
        assertEquals(listOf(AppsScreen), shell.backStack.toList())
    }
}
