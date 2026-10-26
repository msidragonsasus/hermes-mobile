package com.m57.hermescontrol.career

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.theme.HermesControlTheme
import io.mockk.coEvery
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Parent navigation must preserve unsaved credentials while allowing a saved server run to continue. */
class CareerMyPageResultsNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val companyId = "fixture-results-company"
    private lateinit var navigation: NavBackStack<NavKey>

    @Before
    fun prepareFixtureOnlyNavigation() {
        // Keep the parent ViewModels offline and isolate their note persistence from real app storage.
        mockkConstructor(CareerLocalStore::class)
        coEvery { anyConstructed<CareerLocalStore>().enabled() } returns false
        coEvery { anyConstructed<CareerLocalStore>().uiValue(any()) } returns null
        coEvery { anyConstructed<CareerLocalStore>().saveUiValue(any(), any()) } returns Unit
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.enableEdgeToEdge()
            navigation = NavBackStack<NavKey>(CareerCompanySettingsKey(companyId))
            NavigationController.attachAuth(navigation)
        }
    }

    @After
    fun releaseFixtureStorage() {
        unmockkConstructor(CareerLocalStore::class)
    }

    private fun show(
        settingsOnly: Boolean = true,
        pendingAnalysis: Boolean = false,
    ) {
        val detail =
            CareerCompanyDetail(
                company = CareerEntity("companies", companyId, 1, buildJsonObject { put("name", "テスト企業") }),
                thread_id = "company:$companyId",
                mypage =
                    CareerMyPage(
                        status = buildJsonObject { put("state", "running") },
                        supported = true,
                        bot_available = true,
                        connection_version = 1,
                        analysis_resumable = pendingAnalysis,
                        portal_url = "https://portal.example.test/login",
                    ),
            )
        val state =
            CareerCompanyState(
                initialized = true,
                enabled = true,
                stale = false,
                detail = detail,
                loginAccounts =
                    listOf(ShukatsuLoginAccount("fixture-login", companyId, "テスト企業", "MyPage", "", "fixture-id")),
            )
        compose.setContent {
            HermesControlTheme {
                Box(Modifier.width(390.dp)) {
                    CareerCompanyPager(state, if (settingsOnly) 0 else 1, {
                    }, {}, {}, { _, _ -> }, { _, _, _ -> }, {}, settingsOnly = settingsOnly)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun unsavedPasswordAsksBeforeResultsAndCancelRetainsInput() {
        show()
        compose.onNodeWithTag("company_page_0").performScrollToNode(hasTestTag("mypage_password"))
        compose
            .onNodeWithTag("mypage_password")
            .performScrollTo()
            .assertIsDisplayed()
            .performTextReplacement("fixture-password")
        compose
            .onNodeWithTag("mypage_show_results")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        compose.onNodeWithText("未保存の変更があります").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(CareerCompanySettingsKey(companyId)), navigation.toList()) }
        compose.onNodeWithText("編集を続ける").performClick()

        compose.onNodeWithTag("company_page_0").performScrollToNode(hasTestTag("mypage_password"))
        compose.onNodeWithTag("mypage_password").assertTextContains("fixture-password")
        compose.runOnIdle { assertEquals(listOf(CareerCompanySettingsKey(companyId)), navigation.toList()) }
    }

    @Test
    fun analysisResumeIsDisabledWhileServerIsAlreadyRunning() {
        show(settingsOnly = false, pendingAnalysis = true)
        compose.onNodeWithTag("company_page_1").performScrollToNode(hasTestTag("mypage_resume_analysis"))
        compose.onNodeWithTag("mypage_resume_analysis").assertIsNotEnabled()
    }

    @Test
    fun savedSettingsCanOpenResultsWhileServerRunIsActive() {
        show()
        compose.onNodeWithTag("company_page_0").performScrollToNode(hasTestTag("mypage_show_results"))
        compose
            .onNodeWithTag("mypage_show_results")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        compose.onNodeWithText("未保存の変更があります").assertDoesNotExist()
        compose.waitUntil(5_000) { navigation.lastOrNull() == CareerCompanyKey(companyId, CareerCompanyTab.OFFERS) }
        compose.runOnIdle { assertEquals(2, navigation.size) }
    }
}
