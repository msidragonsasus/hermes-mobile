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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppBackStackTest {
    @get:Rule val compose = createComposeRule()

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
