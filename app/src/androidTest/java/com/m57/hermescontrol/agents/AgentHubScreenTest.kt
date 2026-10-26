package com.m57.hermescontrol.agents

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.navigation.AgentScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AgentHubScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun clean() {
        NavigationController.backStack = null
    }

    @Test
    fun selectorOffersOnlyReachableAgentsAndLinksToBotManagement() {
        val stack = NavBackStack<NavKey>(AgentScreen)
        NavigationController.backStack = stack
        compose.setContent { MaterialTheme { AgentSelector() } }
        compose.onNodeWithTag("agent_selector").performClick()
        compose.onNodeWithTag("agent_option_hermes").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(AgentScreen), stack.toList()) }
        compose.onNodeWithTag("agent_selector").performClick()
        // bro cannot receive messages yet: it lives under App management, not in this list.
        compose.onNodeWithTag("agent_option_bro").assertDoesNotExist()
        compose.onNodeWithTag("agent_manage_bots").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(AgentScreen, com.m57.hermescontrol.BotsScreen), stack.toList()) }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun largeFontDetailShowsUnverifiedHistoryWithoutAnyComposer() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { AgentDetailScreen(AgentDetailKey("BRO")) }
            }
        }
        compose.onNodeWithText(context.getString(R.string.agent_destination, "bro")).assertIsDisplayed()
        compose.onNodeWithTag("agent_detail_list").performScrollToNode(hasTestTag("agent_actions_unavailable"))
        compose.onNodeWithTag("agent_actions_unavailable").assertIsDisplayed()
        compose.onNodeWithTag("agent_detail_list").performScrollToNode(hasTestTag("agent_history_unverified"))
        compose.onNodeWithTag("agent_history_unverified").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithTag("agent_send").assertDoesNotExist()
        compose.onNodeWithTag("agent_approve").assertDoesNotExist()
        compose.onNodeWithTag("agent_cancel").assertDoesNotExist()
    }

    @Test
    fun unknownDestinationDoesNotDisplayHermesAsFallback() {
        compose.setContent { MaterialTheme { AgentDetailScreen(AgentDetailKey("OPENCLAW")) } }
        compose.onNodeWithText(context.getString(R.string.agent_unavailable_title)).assertIsDisplayed()
        compose.onNodeWithText("Hermes").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
}
