package com.m57.hermescontrol.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.career.CareerCompanyKey
import com.m57.hermescontrol.career.CareerMyPageBrowserKey
import com.m57.hermescontrol.career.CareerScreenKey
import com.m57.hermescontrol.miniapp.sdui.CareerMyPageSduiKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Controller-level coverage; Android back dispatch and WebView teardown need device tests. */
class CareerBrowserNavigationTest {
    private lateinit var shell: ShellNavigation

    @Before
    fun attachShell() {
        shell =
            ShellNavigation(
                mutableStateOf(ShellTab.APPS),
                ShellTab.primary.associateWith { NavBackStack<NavKey>(it.root) },
            )
        NavigationController.attachShell(shell)
    }

    @After
    fun detachShell() {
        NavigationController.detachShell(shell)
    }

    @Test
    fun closingBrowserPreservesCompanyAndAppStack() {
        val company = CareerCompanyKey("synthetic-company")
        NavigationController.navigateTo(CareerScreenKey)
        NavigationController.navigateTo(company)
        val browser = CareerMyPageBrowserKey(company.companyId)
        NavigationController.navigateTo(browser)

        NavigationController.goBackFrom(browser)

        assertEquals(ShellTab.APPS, shell.selectedTab)
        assertEquals(listOf(AppsScreen, CareerScreenKey, company), shell.backStack.toList())
    }

    @Test
    fun closingBrowserOpenedFromMyPageReturnsToThatPage() {
        val myPage = CareerMyPageSduiKey("synthetic-company")
        NavigationController.navigateTo(myPage)
        val browser = CareerMyPageBrowserKey(myPage.companyId)
        NavigationController.navigateTo(browser)

        NavigationController.goBackFrom(browser)

        assertEquals(listOf(AppsScreen, myPage), shell.backStack.toList())
    }

    @Test
    fun repeatedOpenIsDeduplicatedAndReentryKeepsParent() {
        val company = CareerCompanyKey("synthetic-company")
        val browser = CareerMyPageBrowserKey(company.companyId, "synthetic-link")
        NavigationController.navigateTo(company)
        NavigationController.navigateTo(browser)
        NavigationController.navigateTo(browser)
        assertEquals(listOf(AppsScreen, company, browser), shell.backStack.toList())

        NavigationController.goBackFrom(browser)
        NavigationController.navigateTo(browser)
        NavigationController.goBackFrom(browser)

        assertEquals(listOf(AppsScreen, company), shell.backStack.toList())
    }

    @Test
    fun repeatedCloseCallbackCannotPopCompany() {
        val company = CareerCompanyKey("synthetic-company")
        val browser = CareerMyPageBrowserKey(company.companyId)
        NavigationController.navigateTo(company)
        NavigationController.navigateTo(browser)

        NavigationController.goBackFrom(browser)
        NavigationController.goBackFrom(browser)

        assertEquals(listOf(AppsScreen, company), shell.backStack.toList())
    }

    @Test
    fun staleCloseCallbackCannotPopAnotherDestination() {
        val browser = CareerMyPageBrowserKey("synthetic-company")
        val otherCompany = CareerCompanyKey("other-company")
        NavigationController.navigateTo(browser)
        NavigationController.navigateTo(otherCompany)

        NavigationController.goBackFrom(browser)

        assertEquals(listOf(AppsScreen, browser, otherCompany), shell.backStack.toList())
    }

    @Test
    fun staleCallbackCannotChangeAnotherTabsStack() {
        val browser = CareerMyPageBrowserKey("synthetic-company")
        NavigationController.navigateTo(browser)
        NavigationController.selectTab(ShellTab.TODAY)
        val company = CareerCompanyKey("other-company")
        NavigationController.navigateTo(company)

        NavigationController.goBackFrom(browser)

        assertEquals(ShellTab.TODAY, shell.selectedTab)
        assertEquals(listOf(TodayScreen, company), shell.backStack.toList())
    }

    @Test
    fun closingRootOrDetachedScreenNeverClearsOrSwitchesTab() {
        NavigationController.goBackFrom(AppsScreen)
        assertEquals(ShellTab.APPS, shell.selectedTab)
        assertEquals(listOf(AppsScreen), shell.backStack.toList())

        NavigationController.detachShell(shell)
        NavigationController.goBackFrom(CareerMyPageBrowserKey("synthetic-company"))
        assertEquals(null, NavigationController.backStack)
    }
}
