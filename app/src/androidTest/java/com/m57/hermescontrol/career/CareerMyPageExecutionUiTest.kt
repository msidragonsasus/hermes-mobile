package com.m57.hermescontrol.career

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.m57.hermescontrol.theme.HermesControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CareerMyPageExecutionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun unsetDoesNotChooseFirstModelAndPrivateEndpointIsDisabled() {
        var selections = 0
        val state =
            CareerMyPageExecutionUiState(
                settings = CareerMyPageExecutionSettings(),
                capabilities =
                    CareerMyPageCapabilities(
                        models =
                            listOf(
                                CareerMyPageModelOption(
                                    provider = "openrouter",
                                    id = "private-forbidden-free",
                                    label = "禁止された無料接続",
                                    reasoning_efforts = listOf("default"),
                                    auth_kind = "api_key",
                                    connection_state = "available",
                                    billing = "free",
                                    selectable = false,
                                    private_content_allowed = false,
                                    reason = "private_content_forbidden",
                                ),
                            ),
                    ),
            )
        compose.setContent {
            HermesControlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CareerMyPageExecutionContent(state, {}, { selections++ }, {})
                }
            }
        }
        compose.onNodeWithTag("career_mypage_本文の整理_model").performScrollTo().performClick()
        compose.onNodeWithText("個別案内の送信が許可されていません。", substring = true).assertExists()
        compose.onNodeWithText("禁止された無料接続").assertIsNotEnabled()
        assertEquals(0, selections)
    }

    @Test
    fun pendingUpdateDisablesNewSave() {
        val execution = CareerExecution("openai-codex", "test", "medium")
        compose.setContent {
            HermesControlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CareerMyPageExecutionContent(
                        CareerMyPageExecutionUiState(
                            settings = CareerMyPageExecutionSettings(analysis = execution),
                            pending =
                                CareerMyPageExecutionUpdate(
                                    0,
                                    "pending",
                                    execution,
                                    null,
                                    true,
                                    CareerMyPageBudget(),
                                    false,
                                ),
                        ),
                        {},
                        {},
                        {},
                    )
                }
            }
        }
        compose.onNodeWithTag("career_mypage_execution_save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("同じ保存を再確認").assertExists()
    }

    @Test
    fun unsupportedBackendShowsUpdateRequirementAndDisablesSave() {
        val reason = "このサーバーはマイページのAI設定に対応していません。就活サーバーの更新が必要です。"
        compose.setContent {
            HermesControlTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CareerMyPageExecutionContent(
                        CareerMyPageExecutionUiState(
                            supported = false,
                            settings = CareerMyPageExecutionSettings(),
                            error = reason,
                        ),
                        {},
                        {},
                        {},
                    )
                }
            }
        }
        compose.onNodeWithTag("career_mypage_execution_save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(reason).performScrollTo().assertExists()
    }
}
