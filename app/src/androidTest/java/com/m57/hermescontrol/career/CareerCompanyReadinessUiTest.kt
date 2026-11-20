package com.m57.hermescontrol.career

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.NavigationController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CareerCompanyReadinessUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun missingLoginSettingIsVisibleAndOpensOnlyThatCompanySettings() {
        NavigationController.attachAuth(NavBackStack<NavKey>(CareerScreenKey))
        compose.setContent {
            MaterialTheme {
                CareerCompanyContent(
                    state =
                        CareerCompanyState(
                            initialized = true,
                            enabled = true,
                            stale = false,
                            companies =
                                CareerCompanies(
                                    order_version = 0,
                                    order_state = "ready",
                                    companies =
                                        listOf(
                                            CareerCompanyRow(
                                                id = "missing",
                                                name = "設定が必要な企業",
                                                next_action = "マイページ取得状況を確認",
                                                new_count = 120,
                                                mypage_readiness =
                                                    CareerMyPageReadiness(
                                                        credential_state = "not_configured",
                                                        label = "ログインID・パスワード未設定",
                                                        next_action = "ログイン設定を確認",
                                                    ),
                                            ),
                                        ),
                                ),
                        ),
                    companyId = null,
                    onEnable = {},
                    onRefresh = {},
                    onCollect = {},
                )
            }
        }
        compose
            .onNodeWithTag(
                "company_login_status_missing",
                useUnmergedTree = true,
            ).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("マイページ：ログインID・パスワード未設定", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("マイページ取得状況を確認", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("新着 99+", useUnmergedTree = true).assertIsDisplayed()
        compose
            .onNodeWithTag("company_login_settings_missing")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            assertEquals(CareerCompanySettingsKey("missing"), NavigationController.backStack?.lastOrNull())
        }
    }
}
