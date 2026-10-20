package com.m57.hermescontrol.ui.apps

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.m57.hermescontrol.navigation.AppsScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MiniAppsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun clean() {
        NavigationController.backStack = null
    }

    private fun awaitExamples() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("miniapps_list").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun bundledExamplesShowPlannedUngrantablePermissionsWithoutLaunchingFeatures() {
        val stack = NavBackStack<NavKey>(AppsScreen)
        NavigationController.backStack = stack
        compose.setContent { MaterialTheme { MiniAppsScreen() } }
        awaitExamples()
        compose.onNodeWithText(context.getString(R.string.miniapps_preparing)).assertIsDisplayed()
        for (id in listOf("career", "university", "development")) {
            compose.onNodeWithTag("miniapps_list").performScrollToNode(hasTestTag("miniapp_details_$id"))
            compose.onNodeWithTag("miniapp_$id").assertIsDisplayed()
        }
        compose.onNodeWithTag("miniapp_details_development").performClick()
        compose.onNodeWithText(context.getString(R.string.miniapps_permissions_not_granted)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(AppsScreen), stack.toList()) }
    }

    @Test
    fun largeFontExampleDetailsRemainScrollable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { MiniAppsScreen() }
            }
        }
        awaitExamples()
        compose.onNodeWithTag("miniapps_list").performScrollToNode(hasTestTag("miniapp_details_career"))
        compose.onNodeWithTag("miniapp_details_career").performClick()
        compose.onNodeWithTag("miniapps_list").performScrollToNode(
            androidx.compose.ui.test
                .hasText(context.getString(R.string.miniapps_permissions_not_granted)),
        )
        compose.onNodeWithText(context.getString(R.string.miniapps_permissions_not_granted)).assertIsDisplayed()
    }
}
