package com.m57.hermescontrol.career

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CareerInitializationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun unreadableLocalStateShowsRetryInsteadOfFirstUseAndDoesNotEnable() {
        var enables = 0
        var refreshes = 0
        var retries = 0
        compose.setContent {
            MaterialTheme {
                CareerContent(
                    state = CareerUiState(initializationError = "端末内の就活データを読めません。削除せず保持しています。"),
                    onEnable = { enables++ },
                    onRefresh = { refreshes++ },
                    onSend = { _, _ -> },
                    onRetry = {},
                    onDiscard = {},
                    onRetryInitialization = { retries++ },
                )
            }
        }
        compose.onNodeWithText("有効にして開く").assertDoesNotExist()
        compose.onNodeWithTag("loading_state").assertDoesNotExist()
        compose.onNodeWithTag("error_retry_button").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, enables)
            assertEquals(0, refreshes)
        }
    }

    @Test
    fun knownEnabledButIncompleteRestoreDoesNotExposeComposerOrSettingsWrites() {
        compose.setContent {
            MaterialTheme {
                CareerContent(
                    state = CareerUiState(enabled = true, initializationError = "端末内の就活データを読めません。"),
                    onEnable = {},
                    onRefresh = {},
                    onSend = { _, _ -> },
                    onRetry = {},
                    onDiscard = {},
                    settingsOnly = true,
                )
            }
        }
        compose.onNodeWithTag("error_retry_button").assertIsDisplayed()
        compose.onNodeWithTag("career_enable").assertDoesNotExist()
        compose.onNodeWithTag("career_settings").assertDoesNotExist()
        compose.onNodeWithTag("career_coach_send").assertDoesNotExist()
    }

    @Test
    fun disableButtonAndOpenConfirmationWaitForRefreshToFinish() {
        val state = mutableStateOf(CareerUiState(initialized = true, enabled = true, loading = true))
        val enables = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                CareerContent(
                    state = state.value,
                    onEnable = { enables += it },
                    onRefresh = {},
                    onSend = { _, _ -> },
                    onRetry = {},
                    onDiscard = {},
                    settingsOnly = true,
                )
            }
        }
        compose.onNodeWithTag("career_settings").performScrollToNode(hasText("就活Appを無効にする"))
        compose.onNodeWithText("就活Appを無効にする").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        compose.onNodeWithText("就活Appを無効にする").assertIsEnabled().performClick()
        compose.runOnIdle { state.value = state.value.copy(loading = true) }
        compose.onNodeWithText("無効にする", substring = false).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(emptyList<Boolean>(), enables)
            state.value = state.value.copy(loading = false)
        }
        compose.onNodeWithText("無効にする", substring = false).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(false), enables) }
    }
}
