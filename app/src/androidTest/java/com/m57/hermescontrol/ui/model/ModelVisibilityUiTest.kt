package com.m57.hermescontrol.ui.model

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.model.components.ModelPickerDialog
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID

class ModelVisibilityUiTest {
    @get:Rule val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val configuration =
        Configuration(instrumentation.targetContext.resources.configuration).apply {
            setLocale(Locale.JAPANESE)
        }
    private val context = instrumentation.targetContext.createConfigurationContext(configuration)
    private val scope = "visibility-ui-${UUID.randomUUID()}"
    private val provider = ModelProvider("openai", "OpenAI", models = listOf("one", "two"))
    private val editLabel get() = context.getString(R.string.model_visibility_title)
    private val closeLabel get() = context.getString(R.string.action_close)

    @Before
    fun preferences() {
        mockkObject(AuthManager)
        every { AuthManager.baseUrlFlow } returns MutableStateFlow("test")
        every { AuthManager.selectedProfileFlow } returns MutableStateFlow(null)
        every { AuthManager.activeProfileId } returns MutableStateFlow(null)
        every { AuthManager.uiPreferenceScope() } returns scope
        every { AuthManager.readUiPreference(scope, "model_visibility") } returns null
        every { AuthManager.writeUiPreference(scope, "model_visibility", any()) } returns Unit
    }

    @After
    fun cleanup() {
        unmockkObject(AuthManager)
    }

    private fun showPicker(
        largeFont: Boolean = false,
        longCatalog: Boolean = false,
    ) {
        // Dialogs create a window context, so its Resources must also carry the font scale.
        val displayConfiguration = Configuration(configuration).apply { fontScale = if (largeFont) 2f else 1f }
        val displayContext = context.createConfigurationContext(displayConfiguration)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides displayContext,
                LocalConfiguration provides displayConfiguration,
                LocalResources provides displayContext.resources,
                LocalDensity provides Density(density.density, if (largeFont) 2f else 1f),
            ) {
                HermesControlTheme(themePreference = if (largeFont) ThemePreference.DARK else ThemePreference.LIGHT) {
                    ModelPickerDialog(
                        providers =
                            listOf(
                                if (longCatalog) provider.copy(models = List(20) { "model-$it" }) else provider,
                            ),
                        pinnedModels = listOf(PinnedModel("openai", "one")),
                        title = context.getString(R.string.chat_switch_model_title),
                        subtitle = context.getString(R.string.chat_switch_model_scope),
                        onRefresh = {},
                        onPinToggle = { _, _ -> },
                        onSelect = { _, _ -> },
                        onDismiss = {},
                    )
                }
            }
        }
    }

    private fun waitForVisibility(
        model: String,
        visible: Boolean,
    ) {
        compose.waitUntil(5_000) {
            ModelVisibilityStore.shared
                .observe(scope)
                .value
                .isVisible("openai", model) ==
                visible
        }
        compose.waitForIdle()
    }

    @Test
    fun hiddenPinnedModelDisappearsFromBothListsAndCanBeRestored() {
        showPicker()
        compose.onNodeWithText(editLabel).performClick()
        compose.onNodeWithTag("model_visibility:openai::one").performClick()
        waitForVisibility("one", false)
        compose.onNodeWithTag("model_visibility:openai::one").assertIsOff()
        compose.onNodeWithText(closeLabel).performClick()
        compose.onAllNodesWithText("one").assertCountEquals(0)
        compose.onAllNodesWithText("OpenAI · one").assertCountEquals(0)
        compose.onNodeWithText("two").assertIsDisplayed()
        compose.onNodeWithText(editLabel).performClick()
        compose.onNodeWithTag("model_visibility:openai::one").assertIsOff().performClick()
        waitForVisibility("one", true)
        compose.onNodeWithText(closeLabel).performClick()
        compose.onNodeWithText("one").assertIsDisplayed()
        compose.onNodeWithText("OpenAI · one").assertIsDisplayed()
    }

    @Test
    fun allHiddenStillOffersEditorAndReEnablingOneKeepsOtherHidden() {
        showPicker()
        compose.onNodeWithText(editLabel).performClick()
        compose.onNodeWithText(context.getString(R.string.model_visibility_provider_hide_all)).performClick()
        waitForVisibility("one", false)
        compose.onNodeWithText(closeLabel).performClick()
        compose.onNodeWithText(context.getString(R.string.model_visibility_empty)).assertIsDisplayed()
        compose.onNodeWithText(editLabel).assertIsDisplayed().performClick()
        compose.onNodeWithTag("model_visibility:openai::one").performClick()
        waitForVisibility("one", true)
        compose.onNodeWithTag("model_visibility_list").performScrollToNode(hasTestTag("model_visibility:openai::two"))
        compose.onNodeWithTag("model_visibility:openai::two").assertIsOff()
        compose.onNodeWithText(context.getString(R.string.model_visibility_show_all)).performClick()
        waitForVisibility("two", true)
        compose.onNodeWithTag("model_visibility:openai::two").assertIsOn()
    }

    @Test
    fun japanesePickerShowsCatalogBeyondPinnedRowWithoutScrolling() {
        showPicker(longCatalog = true)
        compose.onNode(hasScrollAction()).assertHeightIsAtLeast(240.dp)
        compose.onNodeWithText("model-0").assertIsDisplayed()
        compose.onNodeWithText("model-2").assertIsDisplayed()
        compose.onNodeWithContentDescription(closeLabel).assertIsDisplayed()
        compose.onNodeWithText(editLabel).assertIsDisplayed()
    }

    @Test
    fun largeFontPickerKeepsCatalogScrollableAndFooterReachable() {
        showPicker(largeFont = true, longCatalog = true)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("model-19"))
        compose.onNodeWithText("model-19").assertIsDisplayed()
        compose.onNodeWithContentDescription(closeLabel).assertIsDisplayed()
        compose.onNodeWithText(editLabel).assertIsDisplayed().performClick()
        compose.onNodeWithText(closeLabel).assertIsDisplayed()
    }

    @Test
    fun editorActionsRemainVisibleAtLargeFont() {
        showPicker(largeFont = true)
        compose.onNodeWithText(editLabel).performClick()
        compose.onNodeWithText(context.getString(R.string.model_visibility_show_all)).assertIsDisplayed()
        compose.onNodeWithText(closeLabel).assertIsDisplayed()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "model-visibility-large-dark.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
