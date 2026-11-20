package com.m57.hermescontrol.career

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.home.HomeItem
import com.m57.hermescontrol.home.HomeTarget
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class CareerActionDateRowTest {
    @get:Rule val compose = createComposeRule()

    private fun item(id: String) =
        HomeItem(
            id,
            "v1",
            "career",
            "action",
            "合成企業 冬季3days エントリーシート " + id,
            HomeTarget("mail", company_id = "synthetic-company", source_id = id),
            deadline = "2026-10-08T23:59+09:00",
        )

    @Composable
    private fun TestFrame(content: @Composable () -> Unit) {
        HermesControlTheme(themePreference = ThemePreference.LIGHT) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 32.dp)) { content() }
            }
        }
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder =
            File(instrumentation.targetContext.getExternalFilesDir(null), "career-home-v13-evidence").apply {
                mkdirs()
            }
        File(folder, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test
    fun dateRailAndOriginalSourceExpansionOpenExactSelectedMail() {
        val sources = (1..5).map { item("mail-" + it) }
        var opened: HomeItem? = null
        compose.setContent { TestFrame { CareerHomeActionRow(CareerActionGroup(sources)) { opened = it } } }
        capture("01-date-rail")
        compose.onNodeWithTag("career_action_date_mail-1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("23:59まで（日本時間）").assertIsDisplayed()
        compose.onNodeWithTag("career_action_sources_mail-1").performClick()
        compose.runOnIdle { assertEquals(null, opened) }
        capture("02-original-sources")
        compose.onNodeWithTag("career_action_source_mail-4").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(sources[3], opened) }
    }

    @Test
    fun unknownDeadlineHasOneMetadataMessageAndNoDateRail() {
        val withReminder = item("reminder").copy(deadline = null, detail = "提出・期限は元の案内で確認")
        val withoutReminder = item("no-reminder").copy(deadline = null, detail = "提出")
        compose.setContent {
            TestFrame {
                CareerHomeActionRow(CareerActionGroup(listOf(withReminder))) {}
                CareerHomeActionRow(CareerActionGroup(listOf(withoutReminder))) {}
            }
        }
        compose.onNodeWithTag("career_action_date_reminder", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("career_action_date_no-reminder", useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodesWithText("期限は元の案内で確認", substring = true, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("提出 · 期限未確定").assertIsDisplayed()
        compose.onNodeWithText("詳細を見る").assertDoesNotExist()
    }

    @Test
    fun largeFontUsesFullDateLineAndKeepsDeadlineTime() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                TestFrame { CareerHomeActionRow(CareerActionGroup(listOf(item("large")))) {} }
            }
        }
        capture("03-large-font")
        compose.onNodeWithTag("career_action_date_large").assertDoesNotExist()
        compose.onNodeWithText("2026年10月8日(木)").assertIsDisplayed()
        compose.onNodeWithText("23:59まで（日本時間）").assertIsDisplayed()
    }
}
