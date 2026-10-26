package com.m57.hermescontrol.openclaw

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.theme.HermesControlTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Run on an isolated, unconfigured emulator; never changes Gateway settings or sends a prompt. */
class OpenClawScreenTest {
    @get:Rule val compose = createComposeRule()

    @After
    fun clean() {
        NavigationController.backStack = null
    }

    @Test
    fun unconfiguredChatExplainsSetupAndDoesNotEnableSending() {
        val stack = NavBackStack<NavKey>(ChatScreen, OpenClawChatKey)
        NavigationController.backStack = stack
        compose.setContent { HermesControlTheme { OpenClawChatScreen() } }
        compose.onNodeWithTag("openclaw_chat").assertIsDisplayed()
        compose.onNodeWithTag("agent_tab_groups").assertIsDisplayed()
        compose.onNodeWithTag("openclaw_input").assertIsDisplayed()
        compose.onNodeWithTag("openclaw_input").assertIsNotEnabled()
        compose.onNodeWithTag("openclaw_send").assertDoesNotExist()
        screenshot("openclaw-chat.png")
        compose.onNodeWithText("接続を設定").performClick()
        compose.runOnIdle { assertEquals(OpenClawSettingsKey, stack.last()) }
    }

    @Test
    fun connectionAndBotDefaultsHaveExplicitLabelsAndNoTokenValue() {
        compose.setContent { HermesControlTheme { OpenClawSettingsScreen() } }
        compose.onNodeWithTag("openclaw_gateway_url").assertIsDisplayed()
        compose.onNodeWithTag("openclaw_token").assertIsDisplayed()
        compose.onNodeWithTag("openclaw_save_connection").assertIsDisplayed()
        screenshot("openclaw-settings.png")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
