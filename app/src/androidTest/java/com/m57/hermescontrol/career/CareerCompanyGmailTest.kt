package com.m57.hermescontrol.career

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CareerCompanyGmailTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun partialImportDoesNotDisplayLatestOrNoNewMail() {
        compose.setContent {
            MaterialTheme {
                SourceStatus(
                    "メール",
                    buildJsonObject {
                        put("version", 2)
                        put("state", "partial")
                        put("fresh", false)
                        put("recent_complete", false)
                        put("backfill_complete", false)
                        put("last_success_at", "2026-09-28T00:00:00Z")
                    },
                    37,
                )
            }
        }
        compose.onNodeWithText("直近分を取得中です。保存できたメールから表示します。").assertExists()
        compose.onNodeWithText("保存済み 37 件").assertExists()
        compose.onNodeWithText("最新", substring = true).assertDoesNotExist()
        compose.onNodeWithText("ありませんでした", substring = true).assertDoesNotExist()
    }

    @Test
    fun companyUsesAccountWideQueueAndKeepsSettingsAvailable() {
        val busy = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        var refresh = 0
        var settings = 0
        compose.setContent {
            MaterialTheme {
                CareerCompanyMailButtons(enabled.value, busy.value, { refresh++ }, { settings++ })
            }
        }
        compose.onNodeWithText("全企業を更新").performClick()
        compose.runOnIdle {
            assertEquals(1, refresh)
            busy.value = true
        }
        compose.onNodeWithText("全企業を更新").assertIsNotEnabled()
        compose.onNodeWithText("自動同期の設定").performClick()
        compose.runOnIdle {
            assertEquals(1, settings)
            busy.value = false
        }
        compose.onNodeWithText("全企業を更新").assertIsEnabled()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("全企業を更新").assertIsNotEnabled()
        compose.onNodeWithText("続きを取得").assertDoesNotExist()
    }
}
