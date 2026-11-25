package com.m57.hermescontrol.career

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.HermesControlApp
import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.home.HomeCoverage
import com.m57.hermescontrol.home.HomeFeedContent
import com.m57.hermescontrol.home.HomeItem
import com.m57.hermescontrol.home.HomePage
import com.m57.hermescontrol.home.HomeSettingsSheet
import com.m57.hermescontrol.home.HomeStatusNotice
import com.m57.hermescontrol.home.HomeTarget
import com.m57.hermescontrol.home.TodayCoverage
import com.m57.hermescontrol.home.TodayPlan
import com.m57.hermescontrol.home.TodayPlanContent
import com.m57.hermescontrol.home.TodayPlanState
import com.m57.hermescontrol.home.TodayTask
import com.m57.hermescontrol.navigation.ShellTab
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LocalShellMenu
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.ShellBottomBar
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

class MaterialOverviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val result =
        HomeItem(
            "result",
            "v1",
            "career",
            "result",
            "企業調査が届きました",
            HomeTarget("company", company_id = "company"),
            detail = "サンプル商事",
            updated_at = "2026-09-28T08:30:00+09:00",
        )
    private val page = HomePage(items = listOf(result), coverage = listOf(HomeCoverage("mail", "メール", "updated")))
    private val entities =
        listOf(
            CareerEntity("companies", "company", 1, buildJsonObject { put("name", "サンプル商事") }),
            CareerEntity("companies", "company2", 1, buildJsonObject { put("name", "みらいシステムズ") }),
            CareerEntity(
                "offers",
                "es",
                1,
                buildJsonObject {
                    put("title", "ES提出")
                    put("company_id", "company")
                    put("deadline", "2026-09-30T17:00:00+09:00")
                },
            ),
            CareerEntity(
                "events",
                "interview",
                1,
                buildJsonObject {
                    put("title", "面接")
                    put("company_id", "company2")
                    put("scheduledAt", "2026-10-02T14:00:00+09:00")
                },
            ),
        )

    @Test fun careerLight() = career(ThemePreference.LIGHT, 1f, "career-light")

    @Test fun careerDark() = career(ThemePreference.DARK, 1f, "career-dark")

    @Test fun careerLargeText() = career(ThemePreference.DARK, 2f, "career-large")

    @Test fun homeLight() = home(ThemePreference.LIGHT, 1f, "home-light")

    @Test fun homeDark() = home(ThemePreference.DARK, 1f, "home-dark")

    @Test fun homeLargeText() = home(ThemePreference.LIGHT, 2f, "home-large")

    private fun career(
        theme: ThemePreference,
        scale: Float,
        name: String,
    ) {
        val area = mutableStateOf("ホーム")
        val stale = mutableStateOf(false)
        var opened: String? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                HermesControlTheme(themePreference = theme) {
                    Surface(
                        Modifier
                            .requiredSize(
                                390.dp,
                                844.dp,
                            ).consumeWindowInsets(WindowInsets.systemBars)
                            .testTag("overview_capture"),
                    ) {
                        HermesScaffold(
                            title = { Text("就活") },
                            navigationIcon = NavIcon.Back {},
                            drawerGesturesEnabled = false,
                            pinTopBar = true,
                            actions = {
                                IconButton(onClick = {}) { Icon(Icons.Outlined.Refresh, "更新") }
                                IconButton(onClick = {}) { Icon(Icons.Outlined.Settings, "設定") }
                            },
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                CareerAreaTabs(area.value, true) { area.value = it }
                                CareerDashboardContent(
                                    state =
                                        CareerUiState(
                                            initialized = true,
                                            enabled = true,
                                            snapshot = CareerSnapshot(1, entities),
                                            stale = stale.value,
                                        ),
                                    page = page,
                                    pageError = if (stale.value) "offline" else null,
                                    pageLoading = false,
                                    onSelect = { opened = it.id },
                                    onTask = { opened = it.id },
                                    onAttention = {},
                                    onResult = {
                                        opened =
                                            it.id
                                    },
                                    onSettings = {},
                                    today = LocalDate.of(2026, 9, 28),
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("回答・判断待ちはありません。").assertIsDisplayed()
        screenshot(name)
        compose.onNodeWithTag("career_agenda").performScrollToNode(hasText("募集・締切を開く"))
        compose.onNodeWithText("募集・締切を開く").performClick()
        compose.runOnIdle { assertEquals("es", opened) }
        compose.onNodeWithTag("career_agenda").performScrollToNode(hasText("企業調査が届きました"))
        compose.onNodeWithText("企業調査が届きました").performClick()
        compose.runOnIdle { assertEquals("result", opened) }
        screenshot("$name-scrolled")
        compose.onNodeWithTag("career_area_企業").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("企業", area.value)
            stale.value = true
        }
        compose.onNodeWithTag("career_agenda").performScrollToNode(hasText("最新の回答・判断待ちは未確認です。"))
        compose.onAllNodesWithText("回答・判断待ちはありません。").fetchSemanticsNodes().also { assertTrue(it.isEmpty()) }
    }

    private fun home(
        theme: ThemePreference,
        scale: Float,
        name: String,
    ) {
        var refreshed = false
        val showSettings = mutableStateOf(false)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesControlApp
        var opened: String? = null
        val link = mutableStateOf(LinkState.CONNECTED)
        val task =
            TodayTask(
                "today",
                "研究発表の資料に、調査結果と参考文献を追加して提出する",
                "今日17時までに授業のページで提出する。",
                "gmail",
                "研究演習",
                "https://example.org/assignment",
                1,
                "2026-11-22T17:00:00+09:00",
                actionability = "actionable",
            )
        val plan =
            TodayPlan(
                plan_day = "2026-11-22",
                fresh = true,
                checked_at = "2026-11-22T06:31:00+09:00",
                state = "complete",
                tasks =
                    listOf(task) +
                        (1..131).map { index ->
                            task.copy(
                                id = "candidate-$index",
                                title = "配信されたお知らせ $index",
                                actionability = "review",
                                review_reason = if (index <= 118) "unknown_relevance" else "optional",
                                deadline = null,
                            )
                        },
                coverage =
                    listOf(
                        TodayCoverage("gmail", "メール", "partial"),
                        TodayCoverage("manaba", "manaba", "auth_required"),
                    ),
            )
        val actions =
            listOf(
                HomeItem(
                    "question",
                    "v1",
                    "chat",
                    "action",
                    "回答を待っています",
                    HomeTarget("chat", session_id = "s1"),
                    detail = "確認したいことがあります。",
                ),
                HomeItem(
                    "es",
                    "v1",
                    "career",
                    "action",
                    "ESの提出期限",
                    HomeTarget("company", company_id = "company"),
                    detail = "サンプル商事",
                    deadline = "2026-09-30T17:00:00+09:00",
                    urgent = true,
                ),
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                HermesControlTheme(themePreference = theme) {
                    Surface(
                        Modifier
                            .requiredSize(
                                390.dp,
                                844.dp,
                            ).consumeWindowInsets(WindowInsets.systemBars)
                            .testTag("overview_capture"),
                    ) {
                        Scaffold(
                            contentWindowInsets = WindowInsets.navigationBars,
                            bottomBar = { ShellBottomBar(ShellTab.TODAY) },
                        ) { padding ->
                            Box(
                                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                            ) {
                                CompositionLocalProvider(
                                    LocalShellMenu provides
                                        { IconButton(onClick = {}) { Icon(Icons.Outlined.Settings, "設定") } },
                                ) {
                                    HermesScaffold(
                                        title = { Text("ホーム") },
                                        drawerGesturesEnabled = false,
                                        onRefresh = { refreshed = true },
                                        showRefreshAction = false,
                                        actions = {
                                            IconButton(
                                                onClick = { showSettings.value = true },
                                                modifier = Modifier.testTag("home_settings"),
                                            ) {
                                                Icon(Icons.Outlined.Tune, "ホームの設定")
                                            }
                                        },
                                    ) {
                                        HomeFeedContent(
                                            scope = "fixture",
                                            actions = actions,
                                            results = listOf(result),
                                            pending = emptyList(),
                                            linkState = link.value,
                                            notice =
                                                if (link.value == LinkState.OFFLINE) {
                                                    HomeStatusNotice(NoticeTone.Info, "オフラインです。保存済みの情報を表示しています。")
                                                } else {
                                                    null
                                                },
                                            onOpen = { opened = it.id },
                                            onAttention = {},
                                            today = { focusOccupied, timeline ->
                                                Column(if (scale == 2f) Modifier.widthIn(max = 320.dp) else Modifier) {
                                                    TodayPlanContent(
                                                        plan = plan,
                                                        busy = false,
                                                        failure = null,
                                                        enabled = true,
                                                        onRefresh = {},
                                                        onConfigure = {},
                                                        onChange = { _, _ -> },
                                                        now = Instant.parse("2026-11-22T00:00:00Z"),
                                                        showFocus = !focusOccupied,
                                                        offline = link.value == LinkState.OFFLINE,
                                                        additionalTimeline = timeline,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    if (showSettings.value) {
                                        HomeSettingsSheet(
                                            scope = "fixture",
                                            today = remember { TodayPlanState("fixture", app.homeRepository) },
                                            chatVisible = true,
                                            careerVisible = true,
                                            careerEnabled = true,
                                            metadata = page,
                                            onProviderChange = { _, _ -> },
                                            onRetry = {},
                                            onDismiss = { showSettings.value = false },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("home_settings").assertIsDisplayed()
        compose.onNodeWithText("接続中").assertDoesNotExist()
        compose.onNodeWithText(task.title).assertIsDisplayed()
        if (scale == 2f) {
            for (label in listOf("今日のタイムライン", "今の情報で調べる")) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose
                    .onNodeWithText(label, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertEquals(label, label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                repeat(layout.lineCount) { line ->
                    assertTrue(label, !layout.isLineEllipsized(line))
                    assertTrue(
                        "$label line is ${layout.getLineRight(line)} x ${layout.getLineBottom(line)} in ${layout.size}",
                        layout.getLineLeft(line) >= 0 && layout.getLineRight(line) <= layout.size.width &&
                            layout.getLineTop(line) >= 0 && layout.getLineBottom(line) <= layout.size.height,
                    )
                }
            }
        }
        screenshot(name)
        compose.onNodeWithTag("home_list").performTouchInput { swipeDown() }
        compose.runOnIdle { assertTrue(refreshed) }
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("ESの提出期限"))
        compose.onNodeWithText("ESの提出期限").performClick()
        compose.runOnIdle {
            assertEquals("es", opened)
            link.value = LinkState.OFFLINE
        }
        val offline = "オフラインです。保存済みの情報を表示しています。"
        compose.onNodeWithTag("home_list").performScrollToNode(hasText(offline))
        compose.onNodeWithText(offline).assertIsDisplayed()
        // The enter-always app bar can still be collapsed after scrolling to a list item.
        compose.onNodeWithTag("home_list").performTouchInput { swipeDown() }
        compose.onNodeWithTag("home_settings").assertIsDisplayed().performClick()
        compose.onNodeWithTag("home_settings_sheet").assertIsDisplayed()
        compose.onNode(hasText("就活") and hasAnyAncestor(hasTestTag("home_settings_sheet"))).assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                "material-overview",
            )
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use {
            compose
                .onNodeWithTag(
                    "overview_capture",
                ).captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
