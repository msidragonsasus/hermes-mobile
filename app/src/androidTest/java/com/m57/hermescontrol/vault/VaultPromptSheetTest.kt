package com.m57.hermescontrol.vault

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
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

class VaultPromptSheetTest {
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
                PromptFlow(prompt) {
                    answers.add(it)
                    true
                }
            }
        }
        compose.onNodeWithTag("vault_prompt_sheet").assertDoesNotExist()
        val expectedSite =
            compose.activity.getString(
                com.m57.hermescontrol.R.string.vault_prompt_site_for,
                "example.test",
            )
        compose.onNodeWithTag("vault_prompt_site").assertTextEquals(expectedSite)
        openSheet()
        compose.onNodeWithTag("vault_prompt_submit").assertIsNotEnabled()
        compose.onNodeWithTag("vault_prompt_identifier").performTextReplacement("dummy-user")
        compose.onNodeWithTag("vault_prompt_password").performTextReplacement("dummy-pass")
        compose
            .onNodeWithTag(
                "vault_prompt_password",
            ).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.runOnIdle { assertTrue(answers.isEmpty()) }
        compose.onNodeWithTag("vault_prompt_submit").performScrollTo().performClick()
        compose.runOnIdle {
            val answer = Json.parseToJsonElement(answers.single()).jsonObject
            assertEquals("dummy-user", answer["identifier"]?.jsonPrimitive?.content)
            assertEquals("dummy-pass", answer["password"]?.jsonPrimitive?.content)
        }
        compose.onNodeWithTag("vault_prompt_sheet").assertDoesNotExist()
    }

    @Test
    fun restorationDiscardsInputsWithoutDeclining() {
        val answers = mutableListOf<String>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            HermesControlTheme {
                PromptFlow(prompt) {
                    answers.add(it)
                    true
                }
            }
        }
        openSheet()
        compose.onNodeWithTag("vault_prompt_identifier").performTextReplacement("dummy-user")
        compose.onNodeWithTag("vault_prompt_password").performTextReplacement("dummy-pass")
        compose.onNodeWithTag("vault_prompt_submit").assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("vault_prompt_sheet").assertDoesNotExist()
        compose.runOnIdle { assertTrue(answers.isEmpty()) }
        compose.onNodeWithTag("vault_prompt_decline").performClick()
        compose.runOnIdle { assertEquals(listOf(""), answers) }
    }

    @Test
    fun failedSendKeepsInputAvailableForUserRetry() {
        compose.setContent { HermesControlTheme { PromptFlow(prompt.copy(sendFailed = true)) { false } } }
        openSheet()
        compose.onNodeWithTag("vault_prompt_identifier").performTextReplacement("dummy-user")
        compose.onNodeWithTag("vault_prompt_password").performTextReplacement("dummy-pass")
        compose.onNodeWithTag("vault_prompt_submit").performScrollTo().performClick()
        compose.onNodeWithTag("vault_prompt_submit").assertIsEnabled()
    }

    @Test
    fun darkLargeTextRendersWithSharedFields() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                HermesControlTheme(themePreference = ThemePreference.DARK) { PromptFlow(prompt) { true } }
            }
        }
        openSheet()
        compose.onNodeWithTag("vault_prompt_password").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("vault_prompt_submit").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun lightThemeRendersWithSharedFields() {
        compose.setContent {
            HermesControlTheme(themePreference = ThemePreference.LIGHT) { PromptFlow(prompt) { true } }
        }
        openSheet()
        compose.onNodeWithTag("vault_prompt_password").assertIsDisplayed()
    }

    @Test
    fun keyboardKeepsPasswordAndSubmitReachable() {
        compose.setContent { HermesControlTheme { PromptFlow(prompt) { true } } }
        openSheet()
        compose.onNodeWithTag("vault_prompt_password").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("vault_prompt_password").assertIsDisplayed()
        compose.onNodeWithTag("vault_prompt_submit").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun cancelKeepsRequestPendingUntilItSettles() {
        val pending = mutableStateOf<VaultPromptUi?>(prompt)
        val answers = mutableListOf<String>()
        compose.setContent {
            HermesControlTheme {
                pending.value?.let { PromptFlow(it) { value -> answers.add(value) } }
            }
        }
        openSheet()
        compose.onNodeWithTag("vault_prompt_cancel").performScrollTo().performClick()
        compose.onNodeWithTag("vault_prompt_sheet").assertDoesNotExist()
        compose.onNodeWithTag("vault_prompt_card").assertIsDisplayed()
        openSheet()
        compose.runOnIdle {
            assertTrue(answers.isEmpty())
            pending.value = null
        }
        compose.onNodeWithTag("vault_prompt_sheet").assertDoesNotExist()
        compose.onNodeWithTag("vault_prompt_card").assertDoesNotExist()
    }

    @Test
    fun sheetWindowHasSecureFlag() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        compose.setContent { HermesControlTheme { PromptFlow(prompt) { true } } }
        openSheet()
        compose.runOnIdle {
            val sheetWindow =
                android.view.inspector.WindowInspector
                    .getGlobalWindowViews()
                    .last { it.isShown && it !== compose.activity.window.decorView }
            val params = sheetWindow.layoutParams as WindowManager.LayoutParams
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }

    private fun openSheet() {
        compose.onNodeWithTag("vault_prompt_open").performClick()
        compose.onNodeWithTag("vault_prompt_sheet").assertIsDisplayed()
    }

    @Composable
    private fun PromptFlow(
        prompt: VaultPromptUi,
        onRespond: (String) -> Boolean,
    ) {
        var open by remember { mutableStateOf(false) }
        VaultPromptCard(prompt, onOpen = { open = true }, onDecline = { onRespond("") })
        if (open) {
            VaultPromptSheet(prompt, onDismiss = { open = false }, onRespond = onRespond)
        }
    }
}
