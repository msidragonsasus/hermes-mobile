package com.m57.hermescontrol.home

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class TodayPlanPanelTest {
    @get:Rule val compose = createComposeRule()
    private val task =
        TodayTask(
            "one",
            "統計学のレポートを提出する",
            "今日17時が締切。提出前に参考文献を確認する。",
            "manaba",
            "統計学",
            "https://example.org/ct/report_1",
            1,
            "2026-10-23T17:00:00+09:00",
        )

    private fun show(
        dark: Boolean = false,
        scale: Float = 1f,
        plan: TodayPlan? = ready(),
        complete: (TodayTask) -> Unit = {
        },
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                HermesControlTheme(themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            TodayPlanContent(plan, false, null, true, {}, {}, complete)
                        }
                    }
                }
            }
        }
    }

    private fun ready() =
        TodayPlan(
            plan_day = LocalDate.now(ZoneId.of("Asia/Tokyo")).toString(),
            fresh = true,
            checked_at = "2026-10-23T09:00:00+09:00",
            state = "complete",
            tasks = listOf(task),
            coverage = listOf(TodayCoverage("gmail", "メール", "updated"), TodayCoverage("manaba", "manaba", "partial")),
        )

    private fun capture(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun lightShowsActionReasonSourceAndExplicitCompletion() {
        var completed: TodayTask? = null
        show(complete = { completed = it })
        compose.onNodeWithText("今日やること").assertIsDisplayed()
        compose.onNodeWithText(task.title).assertIsDisplayed()
        compose.onNodeWithText("今日必須 · 期限 ", substring = true).assertIsDisplayed()
        // The long reason stays folded until the row is opened.
        compose.onNodeWithText(task.reason).assertDoesNotExist()
        compose.onNodeWithText(task.title).performClick()
        compose.onNodeWithText(task.reason).assertIsDisplayed()
        compose.onNodeWithTag("today_complete_one").performClick()
        assertEquals(task, completed)
        capture("today-home-light")
    }

    @Test
    fun darkKeepsPartialCoverageVisible() {
        show(dark = true)
        compose.onNodeWithText("manaba：一部確認・未確認の情報があります").performScrollTo().assertIsDisplayed()
        capture("today-home-dark")
    }

    @Test
    fun enlargedTextKeepsSourceActionReachable() {
        show(scale = 2f)
        compose.onNodeWithText(task.title).performScrollTo().performClick()
        compose.onNodeWithText("元の情報を開く").performScrollTo().assertIsDisplayed()
        capture("today-home-large-text")
    }

    @Test
    fun manabaLoginRequiredExplainsWhereToLogIn() {
        show(plan = ready().copy(coverage = listOf(TodayCoverage("manaba", "manaba", "auth_required"))))
        compose.onNodeWithText("manaba：ログインが必要").performScrollTo().performClick()
        compose
            .onNodeWithText("manabaのログインはアプリではなく、Hermesサーバーで1回だけ行います。")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("python -m today_core.manaba_login --profile default").assertExists()
    }

    @Test
    fun checkedCoverageIsNotRepeated() {
        show(plan = ready().copy(coverage = listOf(TodayCoverage("gmail", "メール", "updated"))))
        compose.onNodeWithTag("today_coverage").assertDoesNotExist()
    }

    @Test
    fun unknownCoverageNeverClaimsThereIsNothingToDo() {
        show(plan = TodayPlan(state = "failed", coverage = listOf(TodayCoverage("manaba", "manaba", "failed"))))
        compose.onNodeWithText("まだ確認できていない情報があります。「やることなし」とは判断していません。").assertIsDisplayed()
    }
}
