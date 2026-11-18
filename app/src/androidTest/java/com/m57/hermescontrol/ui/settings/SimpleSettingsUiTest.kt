package com.m57.hermescontrol.ui.settings

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.model.AccountLimitWindow
import com.m57.hermescontrol.data.model.AccountLimitsResponse
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.settings.components.AppearanceSection
import com.m57.hermescontrol.ui.settings.components.ChatSection
import com.m57.hermescontrol.ui.settings.limits.AccountLimitsContent
import com.m57.hermescontrol.ui.settings.limits.AccountLimitsState
import com.m57.hermescontrol.ui.settings.limits.LimitsStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/** Offline native rendering and navigation targets; never opens a live connection or saves settings. */
class SimpleSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scale = mutableStateOf(1f)
    private val theme = mutableStateOf(ThemePreference.DARK)

    @Test
    fun dailyEntriesNavigateAtLargeFontAndInBothThemes() {
        var destination: NavKey? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                HermesControlTheme(themePreference = theme.value) {
                    SettingsScreen(onBack = {}, onNavigate = { destination = it })
                }
            }
        }
        capture("settings-dark.png")
        val context = instrumentation.targetContext
        val entries = SettingsDirectory.sections.flatMap { it.entries }
        for (entry in entries) {
            val title = context.getString(entry.labelRes)
            compose.onNodeWithTag("settings_directory").performScrollToNode(hasText(title))
            compose.onNodeWithText(title).performClick()
            compose.runOnIdle { assertEquals(entry.key, destination) }
        }
        compose.runOnIdle {
            theme.value = ThemePreference.LIGHT
            scale.value = 2f
        }
        val first = entries.first()
        compose.onNodeWithTag("settings_directory").performScrollToNode(hasText(context.getString(first.labelRes)))
        compose.onNodeWithText(context.getString(first.labelRes)).assertIsDisplayed()
        capture("settings-light-large.png")
        for (entry in entries) {
            val title = context.getString(entry.labelRes)
            compose.onNodeWithTag("settings_directory").performScrollToNode(hasText(title))
            compose.onNodeWithText(title).performClick()
            compose.runOnIdle { assertEquals(entry.key, destination) }
        }
    }

    @Test
    fun remainingQuotaAndResetTimeAreDistinctAndUnknownStaysUnknown() {
        val now = Instant.parse("2026-11-17T01:00:00Z")
        val snapshot =
            AccountLimitsResponse(
                available = true,
                provider = "openai-codex",
                plan = "Plus",
                fetched_at = now.toString(),
                windows =
                    listOf(
                        AccountLimitWindow("Session", 25.0, now.plusSeconds(7200).toString()),
                        AccountLimitWindow("Weekly", null, null),
                    ),
            )
        compose.setContent {
            HermesControlTheme(themePreference = ThemePreference.DARK) {
                HermesScaffold(title = { Text("利用制限") }, drawerGesturesEnabled = false) {
                    AccountLimitsContent(AccountLimitsState(LimitsStatus.READY, snapshot), now, onRetry = {})
                }
            }
        }
        val context = instrumentation.targetContext
        compose
            .onNodeWithText(
                context.getString(com.m57.hermescontrol.R.string.settings_limits_remaining, "75"),
            ).assertIsDisplayed()
        compose
            .onNodeWithText(
                context.getString(com.m57.hermescontrol.R.string.settings_limits_reset_hours, 2L, 0L),
            ).assertIsDisplayed()
        compose
            .onNodeWithText(
                context.getString(com.m57.hermescontrol.R.string.settings_limits_percent_unknown),
            ).assertIsDisplayed()
        compose
            .onNodeWithText(
                context.getString(com.m57.hermescontrol.R.string.settings_limits_reset_unknown),
            ).assertIsDisplayed()
        capture("limits-dark.png")
    }

    @Test
    fun appearanceCanChoosePresetFromDynamicColorsWithoutShowingTechnicalControls() {
        val dynamic = mutableStateOf(true)
        val preset = mutableStateOf(ThemePreset.DEFAULT)
        compose.setContent {
            HermesControlTheme(themePreference = ThemePreference.LIGHT) {
                HermesScaffold(title = { Text("外観") }, drawerGesturesEnabled = false) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        AppearanceSection(
                            themePreference = ThemePreference.LIGHT,
                            onThemeChange = {},
                            useDynamicColors = dynamic.value,
                            onUseDynamicColorsChange = { dynamic.value = it },
                            themePreset = preset.value,
                            onThemePresetChange = { preset.value = it },
                        )
                        ChatSection(false, {}, 20, {}, showTypingControls = false)
                    }
                }
            }
        }
        val context = instrumentation.targetContext
        compose
            .onNodeWithText(
                context.getString(com.m57.hermescontrol.R.string.settings_item_use_dynamic_colors),
            ).performClick()
        compose.onNodeWithText(context.getString(com.m57.hermescontrol.R.string.theme_preset_nord)).performClick()
        compose.runOnIdle {
            assertEquals(false, dynamic.value)
            assertEquals(ThemePreset.NORD, preset.value)
        }
        compose
            .onNodeWithText(
                context.getString(com.m57.hermescontrol.R.string.settings_item_typing_effect),
            ).assertDoesNotExist()
        capture("appearance-light.png")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory =
            File(
                instrumentation.targetContext.getExternalFilesDir(null),
                "simple-settings",
            ).apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
