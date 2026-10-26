package com.m57.hermescontrol.career

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Dummy UI fixtures only. Requires a device; compiling does not prove a UI pass. */
class CareerCompanyInitializationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun unreadableEnabledFlagShowsLocalRetryInsteadOfOnboarding() {
        var enables = 0
        var refreshes = 0
        var retries = 0
        compose.setContent {
            MaterialTheme {
                CareerCompanyContent(
                    state = CareerCompanyState(initializationError = "端末の保存情報を読めません。"),
                    companyId = null,
                    onEnable = { enables++ },
                    onRefresh = { refreshes++ },
                    onCollect = {},
                    embedded = true,
                    onRetryInitialization = { retries++ },
                )
            }
        }
        compose.onNodeWithTag("career_enable").assertDoesNotExist()
        compose.onNodeWithTag("loading_state").assertDoesNotExist()
        compose.onNodeWithTag("error_retry_button").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, enables)
            assertEquals(0, refreshes)
        }
    }

    @Test
    fun partialEnabledRestoreDoesNotExposeCompanyWrites() {
        compose.setContent {
            MaterialTheme {
                CareerCompanyContent(
                    state =
                        CareerCompanyState(
                            enabled = true,
                            initializationError = "端末の保存情報を読めません。",
                            companies = CareerCompanies(companies = listOf(CareerCompanyRow("company", "保存済み企業"))),
                        ),
                    companyId = null,
                    onEnable = {},
                    onRefresh = {},
                    onCollect = {},
                    embedded = true,
                )
            }
        }
        compose.onNodeWithTag("error_retry_button").assertIsDisplayed()
        compose.onNodeWithTag("career_enable").assertDoesNotExist()
        compose.onNodeWithTag("company_add").assertDoesNotExist()
        compose.onNodeWithTag("company_order_begin").assertDoesNotExist()
    }

    @Test
    fun companyDetailRestoreFailureShowsRetryBeforeComposingPager() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                CareerCompanyContent(
                    state = CareerCompanyState(enabled = true, initializationError = "端末の保存情報を読めません。"),
                    companyId = "company",
                    onEnable = {},
                    onRefresh = {},
                    onCollect = {},
                    embedded = true,
                    onRetryInitialization = { retries++ },
                )
            }
        }
        compose.onNodeWithTag("company_page_0").assertDoesNotExist()
        compose.onNodeWithTag("error_retry_button").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun restoreInProgressShowsOnlyLoadingEvenWithKnownEnabledFlag() {
        compose.setContent {
            MaterialTheme {
                CareerCompanyContent(
                    state = CareerCompanyState(enabled = true),
                    companyId = null,
                    onEnable = {},
                    onRefresh = {},
                    onCollect = {},
                    embedded = true,
                )
            }
        }
        compose.onNodeWithTag("loading_state").assertIsDisplayed()
        compose.onNodeWithTag("error_retry_button").assertDoesNotExist()
        compose.onNodeWithTag("career_enable").assertDoesNotExist()
        compose.onNodeWithTag("company_add").assertDoesNotExist()
    }

    @Test
    fun confirmedDisabledScopeStillOffersOnboarding() {
        var enables = 0
        compose.setContent {
            MaterialTheme {
                CareerCompanyContent(
                    state = CareerCompanyState(initialized = true, enabled = false),
                    companyId = null,
                    onEnable = { enables++ },
                    onRefresh = {},
                    onCollect = {},
                    embedded = true,
                )
            }
        }
        compose.onNodeWithTag("error_retry_button").assertDoesNotExist()
        compose.onNodeWithTag("career_enable").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, enables) }
    }
}
