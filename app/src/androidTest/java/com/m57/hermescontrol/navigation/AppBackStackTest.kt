package com.m57.hermescontrol.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.LandingScreen
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.ToolsetDetailKey
import com.m57.hermescontrol.data.local.AuthManager
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AppBackStackTest {
    @get:Rule val compose = createComposeRule()
    private var saved: String? = null

    @Before fun preferences() {
        mockkObject(AuthManager)
        every { AuthManager.uiPreferenceScope() } returns "navigation-test"
        every { AuthManager.readUiPreference("navigation-test", "last_screen") } answers { saved }
        every { AuthManager.writeUiPreference("navigation-test", "last_screen", any()) } answers
            {
                saved = thirdArg()
                Unit
            }
    }

    @After fun cleanPreferences() {
        unmockkObject(AuthManager)
    }

    @Test
    fun restoresExactAttentionIdentifiersWithoutRequestText() {
        val restoration = StateRestorationTester(compose)
        lateinit var shell: ShellNavigation
        restoration.setContent { shell = rememberShellNavigation(true)!! }
        val ref =
            com.m57.hermescontrol.domain.hermes.RequestRef(
                com.m57.hermescontrol.domain.hermes
                    .ConnectionScope("synthetic", "connection", "profile", "process", 1),
                com.m57.hermescontrol.domain.hermes
                    .SessionRef("runtime"),
                "request",
                "q2",
            )
        compose.runOnIdle { shell.navigate(AttentionDetailKey(ref)) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(listOf(TodayScreen, AttentionDetailKey(ref)), shell.backStack.toList()) }
    }

    @Test
    fun restoresSelectedTabAndInactiveTabDetail() {
        val restoration = StateRestorationTester(compose)
        lateinit var shell: ShellNavigation
        restoration.setContent { shell = rememberShellNavigation(true)!! }
        val detail = ToolsetDetailKey("tools/saved", "復元")
        compose.runOnIdle {
            shell.select(ShellTab.TODAY)
            shell.navigate(SettingsScreen)
            shell.select(ShellTab.CHAT)
            shell.navigate(detail)
            shell.select(ShellTab.APPS)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(ShellTab.APPS, shell.selectedTab)
            assertEquals(listOf(AppsScreen), shell.backStack.toList())
            shell.select(ShellTab.CHAT)
            assertEquals(listOf(ChatScreen, detail), shell.backStack.toList())
            shell.select(ShellTab.TODAY)
            assertEquals(listOf(TodayScreen, SettingsScreen), shell.backStack.toList())
        }
    }

    @Test
    fun logoutDiscardsAllTabStacksAndSelection() {
        val restoration = StateRestorationTester(compose)
        val authenticated = mutableStateOf(true)
        var shell: ShellNavigation? = null
        restoration.setContent { shell = rememberShellNavigation(authenticated.value) }
        compose.runOnIdle {
            shell!!.select(ShellTab.CHAT)
            shell!!.navigate(ToolsetDetailKey("private"))
            authenticated.value = false
        }
        compose.runOnIdle { assertEquals(null, shell) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(null, shell) }
        compose.runOnIdle { authenticated.value = true }
        compose.runOnIdle {
            assertEquals(ShellTab.TODAY, shell!!.selectedTab)
            shell!!.select(ShellTab.CHAT)
            assertEquals(listOf(ChatScreen), shell!!.backStack.toList())
        }
    }

    @Test
    fun restoresStackAndParameterizedDestinationAcrossSavedStateRecreation() {
        val restoration = StateRestorationTester(compose)
        lateinit var stack: NavBackStack<NavKey>
        restoration.setContent { stack = rememberAppBackStack(authenticated = true) }
        val detail = ToolsetDetailKey("tool/id", "日本語の表示名")
        compose.runOnIdle {
            stack.add(SettingsScreen)
            stack.add(detail)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(listOf(ChatScreen, SettingsScreen, detail), stack.toList()) }
    }

    @Test
    fun logoutAndLoggedOutRestorationCannotRevealAuthenticatedDestination() {
        val restoration = StateRestorationTester(compose)
        val authenticated = mutableStateOf(true)
        lateinit var stack: NavBackStack<NavKey>
        restoration.setContent { stack = rememberAppBackStack(authenticated.value) }
        compose.runOnIdle {
            stack.add(SettingsScreen)
            authenticated.value = false
        }
        compose.runOnIdle { assertEquals(listOf(LandingScreen), stack.toList()) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(listOf(LandingScreen), stack.toList()) }
        compose.runOnIdle { authenticated.value = true }
        compose.runOnIdle { assertEquals(listOf(ChatScreen), stack.toList()) }
    }
}
