package com.m57.hermescontrol.career

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class CareerMailScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun senderRuleListAndReadableMessageBodyShareOneFlow() {
        var added = ""
        val company = CareerEntity("companies", "company-a", 1, buildJsonObject { put("name", "A社") })
        val detail =
            CareerCompanyDetail(
                company = company,
                thread_id = "company:company-a",
                mail_senders = listOf(CareerMailSenderRule("sender", 1, company.id, "recruit@example.com")),
                mail_status = buildJsonObject { put("state", "updated") },
                mail =
                    listOf(
                        CareerMailItem(
                            message_id = "message-1",
                            thread_id = "thread-1",
                            sender_address = "recruit@example.com",
                            from_text = "採用担当 <recruit@example.com>",
                            subject = "一次面接のご案内",
                            snippet = "日程をご確認ください",
                            body = "一次面接は9月20日です。\n参加可否をご確認ください。",
                            locator = "https://mail.google.com/mail/#all/message-1",
                            received_at = "2026-09-14T01:00:00+00:00",
                        ),
                    ),
            )
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                MaterialTheme {
                    CareerMailContent(
                        state =
                            CareerCompanyState(
                                initialized = true,
                                enabled = true,
                                stale = false,
                                detail = detail,
                                connections = CareerConnections(mail = buildJsonObject { put("enabled", true) }),
                            ),
                        onRefresh = {},
                        onCollect = {},
                        onSetSender = { address, _ -> added = address },
                    )
                }
            }
        }
        compose.onNodeWithTag("career_mail_list").performScrollToNode(
            androidx.compose.ui.test
                .hasText("一次面接のご案内"),
        )
        screenshot("career-mail-list-360.png")
        compose.onNodeWithText("一次面接のご案内").assertIsDisplayed().performClick()
        compose.onNodeWithTag("career_mail_body").assertIsDisplayed()
        screenshot("career-mail-body-360.png")
        compose.onNodeWithText("閉じる").performClick()
        compose.onNodeWithTag("career_mail_list").performScrollToNode(
            androidx.compose.ui.test
                .hasText("送信元メールアドレス"),
        )
        compose.onNodeWithTag("career_mail_sender_input").performTextInput("new@example.com")
        compose.onNodeWithTag("career_mail_sender_add").performClick()
        compose.runOnIdle { assertEquals("new@example.com", added) }
    }

    private fun screenshot(name: String) {
        val outputDirectory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val directory = File(outputDirectory)
        directory.mkdirs()
        File(directory, name).outputStream().use { output ->
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(
                Bitmap.CompressFormat.PNG,
                100,
                output,
            )
        }
    }
}
