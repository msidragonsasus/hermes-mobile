package com.m57.hermescontrol.home

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.navigation.ShellTab
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.ShellBottomBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

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
            actionability = "actionable",
        )

    // 2026-10-23 12:00 JST: the fixture task is due today at 17:00.
    private val now = Instant.parse("2026-10-23T03:00:00Z")

    private fun show(
        dark: Boolean = false,
        scale: Float = 1f,
        plan: TodayPlan? = ready(),
        pending: Map<String, TodayChange> = emptyMap(),
        failures: Map<String, TodayRowFailure> = emptyMap(),
        recent: TodayRecent? = null,
        change: (TodayTask, TodayChange) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                HermesControlTheme(themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            TodayPlanContent(
                                plan = plan,
                                busy = false,
                                failure = null,
                                enabled = true,
                                {},
                                {},
                                change,
                                pending = pending,
                                failures = failures,
                                recent = recent,
                                now = now,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun ready() =
        TodayPlan(
            plan_day = "2026-10-23",
            fresh = true,
            checked_at = "2026-10-23T09:00:00+09:00",
            state = "complete",
            tasks = listOf(task),
            coverage = listOf(TodayCoverage("gmail", "メール", "updated"), TodayCoverage("manaba", "manaba", "partial")),
        )

    private fun candidate(
        id: String,
        reason: String?,
        deadline: String? = null,
        actionability: String = "review",
    ) = task.copy(
        id = id,
        title = "説明会のご案内 $id",
        source = "gmail",
        deadline = deadline,
        actionability = actionability,
        review_reason = reason,
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
        var request: Pair<TodayTask, TodayChange>? = null
        show(change = { t, c -> request = t to c })
        compose.onNodeWithText("今日のタイムライン").assertIsDisplayed()
        compose.onNodeWithText(task.title).assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("期限 今日 17:00 · 残り5時間", substring = true).assertIsDisplayed()
        compose.onNodeWithText(task.reason).assertDoesNotExist()
        compose.onNodeWithTag("today_complete_one").assertDoesNotExist()
        val action = compose.onNodeWithTag("home_focus_action")
        action.assert(hasText("詳細を見る")).assertHeightIsAtLeast(48.dp).performClick()
        action.assert(hasText("詳細を閉じる"))
        compose.onNodeWithText(task.reason).assertIsDisplayed()
        compose.onNodeWithText("manaba：統計学").assertIsDisplayed()
        assertNull(request)
        action.performClick()
        compose.onNodeWithTag("today_complete_one").assertDoesNotExist()
        action.performClick()
        compose.onNodeWithTag("today_complete_one").assertIsEnabled().performClick()
        assertEquals(task to TodayChange.Done(true), request)
        capture("today-home-light")
    }

    @Test
    fun confirmedOverdueStaysVisibleButIsNeverRequiredToday() {
        val expired = task.copy(id = "late", title = "期限を過ぎた課題", deadline = "2026-10-22T17:00:00+09:00")
        show(plan = ready().copy(tasks = listOf(task, expired)))
        compose.onNodeWithText("期限切れ（昨日 17:00）", substring = true).assertIsDisplayed()
        compose.onNode(hasText(task.title) and hasAnyAncestor(hasTestTag("home_focus"))).assertIsDisplayed()
        compose.onNodeWithText(expired.title).assertIsDisplayed()
    }

    @Test
    fun allConfirmedTasksAreListedWithoutAFold() {
        val tasks = (1..8).map { task.copy(id = "t$it", title = "課題 $it", priority = 2) }
        show(plan = ready().copy(tasks = tasks))
        compose.onNodeWithText("今日のタイムライン").assertIsDisplayed()
        compose.onAllNodesWithText("課題 ", substring = true).assertCountEquals(8)
        compose.onNodeWithTag("today_task_t8").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun olderServerFortySevenUnclassifiedRowsAreCandidatesWithExplicitNotice() {
        val legacy =
            (1..47).map {
                val due = "2026-10-2${it % 3}"
                task.copy(id = "l$it", title = "応募案内 $it", priority = 1, deadline = due, actionability = "")
            }
        show(plan = ready().copy(tasks = legacy))
        compose.onAllNodesWithText("今日必須", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("today_review_toggle").assertIsDisplayed()
        compose.onNodeWithText("必要か未確認 47件", substring = true).assertIsDisplayed()
        // Empty today never claims nothing is due.
        compose.onNodeWithText("候補に必要な対応がないかは未確認です。", substring = true).assertIsDisplayed()
        compose.onNodeWithText("候補47件を表示").performScrollTo().performClick()
        compose.onNodeWithTag("today_task_l1").performScrollTo().assertIsDisplayed()
        // Candidates are chosen, not completed.
        compose.onNodeWithTag("today_complete_l1").assertDoesNotExist()
        capture("today-home-legacy")
    }

    @Test
    fun candidateCanBeAddedOrExcludedButExpiredOneCannotBeAdded() {
        val future = candidate("future", "interest_unconfirmed", "2026-10-25")
        val expired = candidate("expired", "expired", "2026-10-20")
        val requests = mutableListOf<Pair<String, TodayChange>>()
        show(
            plan = ready().copy(review_tasks = listOf(future, expired)),
            change = { t, c -> requests += t.id to c },
        )
        compose.onNodeWithText("候補2件を表示").performScrollTo().performClick()
        compose.onNodeWithText(future.title).performScrollTo().performClick()
        compose.onNodeWithText("期限 10月25日 · メール").assertIsDisplayed()
        compose.onNodeWithTag("today_add_future").performScrollTo().performClick()
        compose.onNodeWithTag("today_dismiss_future").performScrollTo().performClick()
        compose.onNodeWithText(expired.title).performScrollTo().performClick()
        compose.onNodeWithTag("today_add_expired").assertDoesNotExist()
        compose.onNodeWithTag("today_dismiss_expired").performScrollTo().assertIsDisplayed()
        assertEquals(
            listOf("future" to TodayChange.Interest(true), "future" to TodayChange.Interest(false)),
            requests,
        )
    }

    @Test
    fun confirmedTaskCanBeExcluded() {
        var request: Pair<TodayTask, TodayChange>? = null
        val mail = task.copy(source = "gmail", source_title = "提出期限のお知らせ")
        show(plan = ready().copy(tasks = listOf(mail)), change = { t, c -> request = t to c })
        compose.onNodeWithTag("home_focus_action").performClick()
        compose.onNodeWithText("提出期限のお知らせ", substring = true).assertDoesNotExist()
        compose.onNodeWithText("今日17時が締切").assertIsDisplayed()
        compose.onNodeWithText("元の情報を開く").performScrollTo().assertIsDisplayed()
        assertNull(request)
        compose.onNodeWithTag("today_dismiss_one").performScrollTo().performClick()
        assertEquals(mail to TodayChange.Interest(false), request)
    }

    @Test
    fun excludedFoldIsReversible() {
        val excluded =
            candidate("no", "dismissed", "2026-10-25", actionability = "dismissed").copy(user_interest = false)
        var request: Pair<String, TodayChange>? = null
        show(plan = ready().copy(dismissed_tasks = listOf(excluded)), change = { t, c -> request = t.id to c })
        compose.onNodeWithText("対象外1件を表示").performScrollTo().performClick()
        compose.onNodeWithText(excluded.title).performScrollTo().performClick()
        compose.onNodeWithTag("today_add_no").performScrollTo().performClick()
        assertEquals("no" to TodayChange.Interest(true), request)
    }

    @Test
    fun pendingAndFailedWritesStayOnTheirRow() {
        val other = task.copy(id = "two", title = "別の課題")
        val third = task.copy(id = "three", title = "三つ目の課題")
        show(
            plan = ready().copy(tasks = listOf(task, other, third)),
            pending = mapOf("one" to TodayChange.Done(true)),
            failures =
                mapOf(
                    "two" to TodayRowFailure(TodayChange.Done(true), "保存できませんでした。もう一度お試しください。", false),
                    "three" to
                        TodayRowFailure(
                            TodayChange.Interest(false),
                            "このサーバーは「対象外」「やることに追加」の保存に未対応です。サーバーの更新後に使えます。",
                            true,
                        ),
                ),
        )
        compose.onNodeWithText("完了を保存中…", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("home_focus_action").performClick()
        compose.onNodeWithTag("today_complete_one").assertIsOn().assertIsNotEnabled()
        // Other rows stay usable while one write is in flight.
        compose.onNodeWithText(other.title).performScrollTo().performClick()
        compose
            .onNodeWithTag("today_complete_two")
            .performScrollTo()
            .assertIsOff()
            .assertIsEnabled()
        compose.onNodeWithTag("today_task_error_two").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("today_retry_two").performScrollTo().assertIsDisplayed()
        // An unsupported server is reported as such, with no pointless retry.
        compose.onNodeWithTag("today_task_error_three").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("today_retry_three").assertDoesNotExist()
    }

    @Test
    fun completedTaskCanBeRestored() {
        var request: Pair<TodayTask, TodayChange>? = null
        val done = task.copy(done = true)
        show(
            plan = ready().copy(tasks = listOf(done)),
            recent = TodayRecent("one", TodayChange.Done(true), wasActionable = true),
            change = { t, c -> request = t to c },
        )
        compose.onNodeWithText("元に戻す").performScrollTo().performClick()
        assertEquals(done to TodayChange.Done(false), request)
        compose.onNodeWithText("完了した1件を表示").performScrollTo().performClick()
        compose
            .onNodeWithTag("today_complete_one")
            .performScrollTo()
            .assertIsOn()
            .assertIsEnabled()
    }

    @Test
    fun selectionKeptAsCandidateIsNotReportedAsAdded() {
        val kept = candidate("kept", "unclassified", actionability = "review").copy(user_interest = true)
        show(
            plan = ready().copy(review_tasks = listOf(kept)),
            recent = TodayRecent("kept", TodayChange.Interest(true), wasActionable = false),
        )
        compose.onNodeWithText("サーバーの判定では候補のままです", substring = true).assertIsDisplayed()
        compose.onNodeWithText("やることに追加しました", substring = true).assertDoesNotExist()
    }

    @Test
    fun darkKeepsPartialCoverageVisible() {
        show(dark = true)
        compose.onNodeWithText("manaba：一部未確認").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("today_coverage").performClick()
        compose.onNodeWithText("manaba：一部確認・未確認の情報があります").performScrollTo().assertIsDisplayed()
        capture("today-home-dark")
    }

    @Test
    fun enlargedTextKeepsSourceActionReachable() {
        show(scale = 2f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(task.title).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        compose.onNodeWithTag("home_focus_action").performScrollTo().performClick()
        compose.onNodeWithText("元の情報を開く").performScrollTo().assertIsDisplayed()
        capture("today-home-large-text")
    }

    @Test
    fun manabaLoginRequiredExplainsWhereToLogIn() {
        show(plan = ready().copy(coverage = listOf(TodayCoverage("manaba", "manaba", "auth_required"))))
        compose.onNodeWithTag("today_coverage").performScrollTo().performClick()
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

    /**
     * Mirrors Navigation.kt's inset ownership (shell Scaffold with ShellBottomBar, padding and
     * consumeWindowInsets) around HermesScaffold and a LazyColumn with HomeFeedContent's padding.
     * Not the authenticated app shell itself; that still needs a device check.
     */
    @Test
    fun lastRowScrollsFullyAboveShellNavigation() {
        val tasks = (1..20).map { task.copy(id = "t$it", title = "課題 $it", priority = 2) }
        compose.setContent {
            HermesControlTheme(themePreference = ThemePreference.LIGHT) {
                Scaffold(
                    contentWindowInsets = WindowInsets.navigationBars,
                    bottomBar = { Box(Modifier.testTag("shell_bar")) { ShellBottomBar(ShellTab.TODAY) } },
                ) { padding ->
                    Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                        HermesScaffold(title = { Text("ホーム") }, drawerGesturesEnabled = false) {
                            val spacing = LocalSpacing.current
                            val edges = PaddingValues(spacing.lg, spacing.md, spacing.lg, spacing.xl)
                            LazyColumn(modifier = Modifier.testTag("home_list"), contentPadding = edges) {
                                item {
                                    Column {
                                        TodayPlanContent(
                                            plan = ready().copy(tasks = tasks),
                                            busy = false,
                                            failure = null,
                                            enabled = true,
                                            {},
                                            {},
                                            { _, _ -> },
                                            now = now,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("home_list").performTouchInput { repeat(12) { swipeUp() } }
        compose.waitForIdle()
        val bar = compose.onNodeWithTag("shell_bar").getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("home_list").getUnclippedBoundsInRoot()
        val last = compose.onNodeWithTag("today_task_t20").getUnclippedBoundsInRoot()
        assertTrue("list $list overlaps bar $bar", list.bottom <= bar.top)
        assertTrue("last row $last hidden behind bar $bar", last.bottom <= bar.top)
        assertTrue("last row $last not fully scrolled into list $list", last.bottom <= list.bottom)
        capture("today-home-bottom-inset")
    }
}
