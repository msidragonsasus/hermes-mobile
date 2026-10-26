package com.m57.hermescontrol.vault

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.core.view.drawToBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.ws.VaultPromptKind
import com.m57.hermescontrol.data.ws.VaultPromptRequest
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class VaultPromptDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val prompt =
        VaultPromptUi(
            ConnectionScope("fixture", null, null, "fixture", 1),
            VaultPromptRequest(
                "srq-test",
                "fixture-chat",
                VaultPromptKind.LOGIN,
                "https://example.test/login?token=hidden",
                "Misleading label",
            ),
        )

    @Test
    fun actualOriginShownAndMaskedPairSentOnlyAfterExplicitSubmit() {
        val answers = mutableListOf<String>()
        compose.setContent {
            HermesControlTheme {
                VaultPromptDialog(prompt) {
                    answers.add(it)
                    true
                }
            }
        }
        compose.onNodeWithTag("vault_prompt_site").assertTextEquals("https://example.test")
        compose.onNodeWithTag("vault_prompt_submit").assertIsNotEnabled()
        compose.onNodeWithTag("vault_prompt_identifier").performTextReplacement("dummy-user")
        compose.onNodeWithTag("vault_prompt_password").performTextReplacement("dummy-pass")
        compose
            .onNodeWithTag(
                "vault_prompt_password",
            ).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.runOnIdle { assertTrue(answers.isEmpty()) }
        compose.onNodeWithTag("vault_prompt_submit").performClick()
        compose.runOnIdle {
            val answer = Json.parseToJsonElement(answers.single()).jsonObject
            assertEquals("dummy-user", answer["identifier"]?.jsonPrimitive?.content)
            assertEquals("dummy-pass", answer["password"]?.jsonPrimitive?.content)
        }
        compose.onNodeWithTag("vault_prompt_submit").assertIsNotEnabled()
    }

    @Test
    fun restorationDiscardsInputsWithoutDeclining() {
        val answers = mutableListOf<String>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            HermesControlTheme {
                VaultPromptDialog(prompt) {
                    answers.add(it)
                    true
                }
            }
        }
        compose.onNodeWithTag("vault_prompt_identifier").performTextReplacement("dummy-user")
        compose.onNodeWithTag("vault_prompt_password").performTextReplacement("dummy-pass")
        compose.onNodeWithTag("vault_prompt_submit").assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("vault_prompt_submit").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(answers.isEmpty()) }
        compose.onNodeWithTag("vault_prompt_decline").performClick()
        compose.runOnIdle { assertEquals(listOf(""), answers) }
    }

    @Test
    fun failedSendKeepsInputAvailableForUserRetry() {
        compose.setContent { HermesControlTheme { VaultPromptDialog(prompt.copy(sendFailed = true)) { false } } }
        compose.onNodeWithTag("vault_prompt_identifier").performTextReplacement("dummy-user")
        compose.onNodeWithTag("vault_prompt_password").performTextReplacement("dummy-pass")
        compose.onNodeWithTag("vault_prompt_submit").performClick()
        compose.onNodeWithTag("vault_prompt_submit").assertIsEnabled()
    }

    @Test
    fun darkLargeTextRendersWithSharedFields() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                HermesControlTheme(themePreference = ThemePreference.DARK) { VaultPromptDialog(prompt) { true } }
            }
        }
        compose.onNodeWithTag("vault_prompt_password").assertIsDisplayed()
        compose.onNodeWithTag("vault_prompt_submit").assertIsDisplayed()
        captureOwnDialog("vault-prompt-dark.png")
    }

    @Test
    fun lightThemeRendersWithSharedFields() {
        compose.setContent {
            HermesControlTheme(themePreference = ThemePreference.LIGHT) { VaultPromptDialog(prompt) { true } }
        }
        compose.onNodeWithTag("vault_prompt_password").assertIsDisplayed()
        captureOwnDialog("vault-prompt-light.png")
    }

    @Test
    fun keyboardKeepsPasswordAndSubmitReachable() {
        compose.setContent { HermesControlTheme { VaultPromptDialog(prompt) { true } } }
        compose.onNodeWithTag("vault_prompt_password").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("vault_prompt_password").assertIsDisplayed()
        compose.onNodeWithTag("vault_prompt_submit").assertIsDisplayed()
    }

    /** Draw only this test's own view tree. The real dialog retains FLAG_SECURE. */
    private fun captureOwnDialog(name: String) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val bitmap =
            compose.runOnIdle {
                android.view.inspector.WindowInspector
                    .getGlobalWindowViews()
                    .last { it.isShown && it.width > 0 && it.height > 0 }
                    .drawToBitmap()
            }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        File(target.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
