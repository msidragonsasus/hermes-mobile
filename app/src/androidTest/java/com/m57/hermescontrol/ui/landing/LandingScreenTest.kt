package com.m57.hermescontrol.ui.landing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.util.LocaleContextWrapper
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LandingScreenTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun preferences() {
        mockkObject(AuthManager)
        every { AuthManager.getAppLanguage() } returns "system"
        every { AuthManager.setAppLanguage(any()) } returns Unit
    }

    @After
    fun cleanPreferences() {
        unmockkObject(AuthManager)
    }

    private fun showLanding(language: String) {
        val context =
            LocaleContextWrapper.wrapWithCode(
                InstrumentationRegistry.getInstrumentation().targetContext,
                language,
            )
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
            ) {
                HermesControlTheme {
                    LandingScreen(onAuthLogin = {})
                }
            }
        }
    }

    @Test
    fun japaneseCanBeSelectedBeforeLogin() {
        showLanding("en")
        compose.onNodeWithText("System").performClick()
        compose.onNodeWithText("日本語").assertIsDisplayed().performClick()
        verify(exactly = 1) { AuthManager.setAppLanguage("ja") }
    }

    @Test
    fun japaneseLandingUsesTranslatedCopy() {
        every { AuthManager.getAppLanguage() } returns "ja"
        showLanding("ja")
        compose.onNodeWithText("Hermes Mobile").assertIsDisplayed()
        compose.onNodeWithText("Hermes Agentの操作パネル").assertIsDisplayed()
        compose.onNodeWithText("ログイン").assertIsDisplayed()
        compose.onNodeWithText("言語").assertIsDisplayed()
        compose.onNodeWithText("日本語").assertIsDisplayed()
    }

    @Test
    fun selectingCurrentLanguageDoesNotWritePreferences() {
        showLanding("en")
        compose.onNodeWithText("System").performClick()
        compose.onNodeWithText("English").assertIsDisplayed()
        compose.onAllNodesWithText("System")[1].performClick()
        verify(exactly = 0) { AuthManager.setAppLanguage(any()) }
    }
}
