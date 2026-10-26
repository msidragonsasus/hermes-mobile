package com.m57.hermescontrol.ui.apps

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
    fun packagedAssetsLoadAndValidateWithoutUiOrNetworkWait() {
        val started = android.os.SystemClock.elapsedRealtime()
        val examples =
            com.m57.hermescontrol.miniapp.BundledMiniAppExamples
                .load(context.assets)
        assertEquals(listOf("university", "development"), examples.map { it.id })
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        org.junit.Assert.assertTrue("Local asset loading exceeded the existing UI deadline", elapsed < 10_000)
        android.util.Log.i("MiniAppsAssetTiming", "localAssetsElapsedMs=$elapsed")
    }

    @Test
    fun bundledExamplesShowPlannedUngrantablePermissionsWithoutLaunchingFeatures() {
        val stack = NavBackStack<NavKey>(AppsScreen)
        NavigationController.backStack = stack
        compose.setContent { MaterialTheme { MiniAppsScreen(showExamples = true) } }
        awaitExamples()
        compose.onNodeWithText(context.getString(R.string.miniapps_preparing)).assertIsDisplayed()
        for (id in listOf("university", "development")) {
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
                MaterialTheme { MiniAppsScreen(showExamples = true) }
            }
        }
        awaitExamples()
        compose.onNodeWithTag("miniapps_list").performScrollToNode(hasTestTag("miniapp_details_university"))
        compose.onNodeWithTag("miniapp_details_university").performClick()
        compose.onNodeWithTag("miniapps_list").performScrollToNode(
            androidx.compose.ui.test
                .hasText(context.getString(R.string.miniapps_permissions_not_granted)),
        )
        compose.onNodeWithText(context.getString(R.string.miniapps_permissions_not_granted)).assertIsDisplayed()
    }

    @Test
    fun launcherShowsUsableCareerEntryAndNoDevelopmentExamples() {
        val stack = NavBackStack<NavKey>(AppsScreen)
        NavigationController.backStack = stack
        compose.setContent { MaterialTheme { MiniAppsScreen() } }
        compose.onNodeWithTag("pc_power_app").assertIsDisplayed()
        compose.onNodeWithTag("career_app").assertIsDisplayed()
        compose.onNodeWithTag("terminal_app").assertIsDisplayed()
        compose.onNodeWithTag("miniapp_career").assertDoesNotExist()
        compose.onNodeWithTag("miniapp_university").assertDoesNotExist()
        compose.onNodeWithTag("miniapp_development").assertDoesNotExist()
        compose.onNodeWithTag("career_open").performClick()
        compose.runOnIdle { assertEquals(com.m57.hermescontrol.career.CareerScreenKey, stack.last()) }
    }

    @Test
    fun pcPowerLauncherOpensNativePowerScreen() {
        val stack = NavBackStack<NavKey>(AppsScreen)
        NavigationController.backStack = stack
        compose.setContent { MaterialTheme { MiniAppsScreen() } }
        compose.onNodeWithTag("pc_power_open").performClick()
        compose.runOnIdle { assertEquals(com.m57.hermescontrol.pcpower.PcPowerScreenKey, stack.last()) }
    }

    @Test
    fun terminalLauncherOpensNativeTerminalScreen() {
        val stack = NavBackStack<NavKey>(AppsScreen)
        NavigationController.backStack = stack
        compose.setContent { MaterialTheme { MiniAppsScreen() } }
        compose.onNodeWithTag("terminal_open").performClick()
        compose.runOnIdle { assertEquals(com.m57.hermescontrol.terminal.TerminalScreenKey, stack.last()) }
    }

    @Test
    fun largeFontLauncherKeepsNamesVisibleAndWholeTileClickable() {
        val stack = NavBackStack<NavKey>(AppsScreen)
        NavigationController.backStack = stack
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                com.m57.hermescontrol.theme
                    .HermesControlTheme { MiniAppsScreen() }
            }
        }
        compose.onNodeWithText(context.getString(R.string.apps_pc_power)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.apps_career)).assertIsDisplayed()
        saveLauncherScreenshot("apps-launcher-large-font.png")
        compose.onNodeWithTag("career_open").performClick()
        compose.runOnIdle { assertEquals(com.m57.hermescontrol.career.CareerScreenKey, stack.last()) }
    }

    @Test
    fun launcherUsesCompactTilesInTheSameRow() {
        compose.setContent {
            com.m57.hermescontrol.theme
                .HermesControlTheme { MiniAppsScreen() }
        }
        val power = compose.onNodeWithTag("pc_power_open").fetchSemanticsNode().boundsInRoot
        val career = compose.onNodeWithTag("career_open").fetchSemanticsNode().boundsInRoot
        assertEquals(power.top, career.top, 1f)
        org.junit.Assert.assertTrue("Tiles should be separate columns", career.left > power.right)
        saveLauncherScreenshot("apps-launcher.png")
    }

    private fun saveLauncherScreenshot(name: String) {
        val file = java.io.File(context.getExternalFilesDir(null), name)
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(
                android.graphics.Bitmap.CompressFormat.PNG,
                100,
                it,
            )
        }
    }
}
