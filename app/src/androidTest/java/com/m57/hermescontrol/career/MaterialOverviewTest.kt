package com.m57.hermescontrol.career

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.home.HomeCoverage
import com.m57.hermescontrol.home.HomeFeedContent
import com.m57.hermescontrol.home.HomeItem
import com.m57.hermescontrol.home.HomePage
import com.m57.hermescontrol.home.HomeTarget
import com.m57.hermescontrol.navigation.ShellTab
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LocalShellMenu
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.ShellBottomBar
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

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
        var opened: String? = null
        val link = mutableStateOf(LinkState.CONNECTED)
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
                            androidx.compose.foundation.layout.Box(
                                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                            ) {
                                CompositionLocalProvider(
                                    LocalShellMenu provides
                                        { IconButton(onClick = {}) { Icon(Icons.Outlined.Settings, "設定") } },
                                ) {
                                    HermesScaffold(title = { Text("ホーム") }, drawerGesturesEnabled = false) {
                                        HomeFeedContent(
                                            "fixture",
                                            actions,
                                            listOf(result),
                                            emptyList(),
                                            link.value,
                                            false,
                                            null,
                                            null,
                                            page,
                                            true,
                                            true,
                                            true,
                                            { refreshed = true },
                                            { _, _ -> },
                                            { opened = it.id },
                                            {},
                                            {},
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("接続中").assertIsDisplayed()
        if (scale == 1f) {
            val connection = compose.onNodeWithTag("home_connection").fetchSemanticsNode().boundsInRoot
            val refresh = compose.onNodeWithTag("home_refresh").fetchSemanticsNode().boundsInRoot
            assertTrue(abs(connection.center.y - refresh.center.y) < 4f)
        }
        screenshot(name)
        compose.onNodeWithTag("home_refresh").performClick()
        compose.runOnIdle { assertTrue(refreshed) }
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("ESの提出期限"))
        compose.onNodeWithText("ESの提出期限").performClick()
        compose.runOnIdle {
            assertEquals("es", opened)
            link.value = LinkState.OFFLINE
        }
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("オフライン"))
        compose.onNodeWithText("オフライン").assertIsDisplayed()
        compose.onNodeWithTag("home_providers").performClick()
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("就活 をホームに表示"))
        compose.onNodeWithText("就活 をホームに表示").assertIsDisplayed()
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
