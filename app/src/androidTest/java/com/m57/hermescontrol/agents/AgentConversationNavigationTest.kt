package com.m57.hermescontrol.agents

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.navigation.ShellNavigation
import com.m57.hermescontrol.navigation.ShellTab
import com.m57.hermescontrol.openclaw.OpenClawChatKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AgentConversationNavigationTest {
    @get:Rule val compose = createComposeRule()
    private var shell: ShellNavigation? = null

    private fun attach(vararg keys: NavKey): NavBackStack<NavKey> {
        val stack = NavBackStack<NavKey>(*keys)
        val navigation =
            ShellNavigation(
                mutableStateOf(ShellTab.AGENT),
                ShellTab.primary.associateWith { if (it == ShellTab.AGENT) stack else NavBackStack<NavKey>(it.root) },
            )
        shell = navigation
        NavigationController.attachShell(navigation)
        return stack
    }

    @After
    fun clean() {
        shell?.let(NavigationController::detachShell)
        shell = null
    }

    @Test
    fun selectorOpensTheDedicatedOpenClawConversation() {
        val stack = attach(ChatScreen)
        compose.setContent { MaterialTheme { AgentSelector() } }
        compose.onNodeWithTag("agent_selector").performClick()
        compose.onNodeWithTag("agent_option_openclaw").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(ChatScreen, OpenClawChatKey), stack.toList()) }
    }
}
