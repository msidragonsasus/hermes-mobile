package com.m57.hermescontrol.vault

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class PasswordsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun formRequiresAWebsiteAndMasksAndClearsSecretsAfterSubmit() {
        var submissions = 0
        compose.setContent {
            HermesControlTheme {
                AddLoginDialog(false, null, {}, { _, _, _, password, _ ->
                    assertEquals("dummy-pass", password)
                    submissions++
                })
            }
        }
        compose.onNodeWithTag("vault_save").assertIsNotEnabled()
        fill("vault_label", "Example")
        fill("vault_origin", "file:///private")
        fill("vault_identifier", "dummy-user")
        fill("vault_password", "dummy-pass")
        compose.onNodeWithTag("vault_password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("vault_save").assertIsNotEnabled()
        fill("vault_origin", "https://example.test/login")
        compose.onNodeWithTag("vault_save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, submissions) }
        compose.onNodeWithTag("vault_save").assertIsNotEnabled()
    }

    @Test
    fun secretsDoNotSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { HermesControlTheme { AddLoginDialog(false, null, {}, { _, _, _, _, _ -> }) } }
        fill("vault_label", "Example")
        fill("vault_origin", "https://example.test")
        fill("vault_identifier", "dummy-user")
        fill("vault_password", "dummy-pass")
        compose.onNodeWithTag("vault_save").assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("vault_save").assertIsNotEnabled()
    }

    @Test
    fun listAndDeleteFollowServerAcknowledgement() {
        var removed = false
        val repo =
            VaultRepository({ method, _ ->
                when (method) {
                    "vault.list" -> {
                        mapOf(
                            "items" to
                                if (removed) {
                                    emptyList()
                                } else {
                                    listOf(
                                        mapOf(
                                            "id" to "dummy",
                                            "kind" to "login",
                                            "label" to "Example",
                                            "origin" to "https://example.test",
                                            "identifier" to "dummy-user",
                                        ),
                                    )
                                },
                        )
                    }

                    "vault.remove" -> {
                        removed = true
                        mapOf("removed" to true)
                    }

                    else -> {
                        error("Unexpected method")
                    }
                }
            })
        compose.setContent {
            HermesControlTheme(themePreference = ThemePreference.LIGHT) { VaultContent(repo, "test-profile") }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Example", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("vault-list-light")
        compose
            .onNode(
                androidx.compose.ui.test
                    .hasContentDescription("Delete Example") or
                    androidx.compose.ui.test
                        .hasContentDescription("Exampleを削除"),
            ).performClick()
        val deleteLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.vault_delete)
        compose.onNodeWithText(deleteLabel, substring = false).performClick()
        compose.waitUntil(5_000) { removed }
        val deleted = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.vault_deleted)
        compose.onNodeWithTag("vault_notice").assertTextEquals(deleted)
    }

    @Test
    fun formFitsDarkThemeAndLargeText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                HermesControlTheme(themePreference = ThemePreference.DARK) {
                    AddLoginDialog(false, null, {}, { _, _, _, _, _ -> })
                }
            }
        }
        fill("vault_label", "Example")
        fill("vault_origin", "https://example.test")
        fill("vault_identifier", "dummy-user")
        fill("vault_password", "dummy-pass")
        compose.onNodeWithTag("vault_save").assertIsEnabled()
        compose.onNodeWithTag("vault_save").assertIsDisplayed()
        compose.onNodeWithTag("vault_password").assertIsDisplayed()
    }

    private fun fill(
        tag: String,
        text: String,
    ) {
        compose
            .onNodeWithTag(tag)
            .performScrollTo()
            .performClick()
            .performTextReplacement(text)
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
