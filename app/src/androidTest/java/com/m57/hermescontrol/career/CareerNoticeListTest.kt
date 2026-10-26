package com.m57.hermescontrol.career

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class CareerNoticeListTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val title = "【サンプル企業】本選考（秋ターム）エントリーシート提出のご案内。選考参加を希望する方は期限までに提出してください。"
    private val deadlines = "ES1次締切：9月30日（水）14:00\nES2次締切：10月15日（木）14:00\n最終締切：10月29日（木）14:00"
    private val latest =
        CareerNotice(
            seq = 20,
            id = "latest",
            company_id = "sample",
            kind = "source_changed",
            title = title,
            created_at = "2026-09-30T12:37:00Z",
            eligible = true,
            company_name = "サンプル企業",
            category = "action",
            label = "提出・回答の依頼",
            quote = "本選考への参加を希望する方はエントリーシートを提出してください。".repeat(8),
            deadline_text = deadlines,
            source_ref = CareerNoticeSource("mypage", "evidence-latest"),
        )

    private fun render(
        font: Float = 1f,
        dark: Boolean = false,
        notices: List<CareerNotice> = listOf(latest.copy(seq = 1, id = "old", title = "以前の案内"), latest),
        onClick: (CareerNotice) -> Unit = {},
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                HermesControlTheme(themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT) {
                    Box(Modifier.width(360.dp).fillMaxSize().systemBarsPadding()) {
                        CareerNoticeList(notices, Modifier.fillMaxSize(), onClick)
                    }
                }
            }
        }
    }

    @Test fun longJapaneseNoticeKeepsTitleAndEveryDeadlineReadable() {
        render()
        screenshot("notices-360-light.png")
        assertReadableDetails()
    }

    @Test fun largeFontDarkNoticeKeepsTitleAndEveryDeadlineReadable() {
        render(font = 2f, dark = true)
        screenshot("notices-360-font200-dark.png")
        assertReadableDetails()
    }

    @Test fun newestNoticeComesFirstAndEachCardOpensItsOwnNotice() {
        var selected: CareerNotice? = null
        val settings =
            latest.copy(
                seq = 2,
                id = "settings",
                title = "再接続の案内",
                source_ref = CareerNoticeSource("mail_settings", "mail"),
            )
        render(notices = listOf(settings, latest), onClick = { selected = it })
        compose.onNodeWithTag("career_notice_latest").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(latest, selected) }
        compose.onNodeWithTag("career_notice_list").performScrollToNode(hasTestTag("career_notice_settings"))
        compose.onNodeWithText("設定を開く").assertIsDisplayed()
        compose.onNodeWithTag("career_notice_settings").performClick()
        compose.runOnIdle { assertEquals(settings, selected) }
    }

    private fun assertReadableDetails() {
        compose.onNodeWithText("サンプル企業").assertIsDisplayed()
        compose.onNodeWithText("2件の案内").assertIsDisplayed()
        listOf(title, deadlines).forEach { text ->
            val node =
                compose.onNode(
                    hasText(text) and hasAnyAncestor(hasTestTag("career_notice_latest")),
                    useUnmergedTree = true,
                )
            node.performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse(
                "${if (text == title) "Title" else "Deadlines"}: size=${layout.size}, lines=${layout.lineCount}, " +
                    "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}",
                layout.hasVisualOverflow,
            )
            val textBounds = node.fetchSemanticsNode().boundsInRoot
            val cardBounds = compose.onNodeWithTag("career_notice_latest").fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Text stays inside the card's side padding",
                textBounds.left > cardBounds.left && textBounds.right < cardBounds.right,
            )
        }
        compose
            .onNode(
                hasText("案内を読む") and hasAnyAncestor(hasTestTag("career_notice_latest")),
                useUnmergedTree = true,
            ).performScrollTo()
            .assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir, "notice-list-regression")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, name).outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
