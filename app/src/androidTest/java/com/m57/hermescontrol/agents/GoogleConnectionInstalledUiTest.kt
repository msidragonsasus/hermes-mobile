package com.m57.hermescontrol.agents

import android.app.KeyguardManager
import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.SettingsGoogleConnection
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test

/** Opens only the installed settings page; never presses the Google consent button. */
class GoogleConnectionInstalledUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun installedConnectionPageIsVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val keyguard = instrumentation.targetContext.getSystemService(KeyguardManager::class.java)
        assumeFalse("Unlock the phone to verify the visible page", keyguard.isKeyguardLocked)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(20_000) { NavigationController.backStack != null }
            compose.runOnIdle { NavigationController.navigateTo(SettingsGoogleConnection) }
            compose.waitUntil(20_000) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasTestTag("google_connection_status"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("GoogleアカウントをHermesにつなぐ").assertIsDisplayed()
            compose.onNodeWithTag("google_connection_status").assertIsDisplayed()
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("installed_google_connection_page_visible", "true")
                    putString("google_consent_started", "false")
                },
            )
        }
    }
}
