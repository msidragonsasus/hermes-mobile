package com.m57.hermescontrol.career

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import java.io.File

class CareerConsultationContextUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun conversationShowsReadableAnswerAndSafeCompanyContextWithoutSecrets() {
        val execution = CareerExecution("openai-codex", "test-model", "medium")
        val company =
            CareerCompanyRow(
                id = "synthetic-company",
                name = "株式会社サンプル",
                next_action = "マイページのログイン設定を確認",
                mypage_readiness =
                    CareerMyPageReadiness(
                        credential_state = "not_configured",
                        label = "ログインID・パスワード未設定",
                        next_action = "ログイン設定を確認",
                    ),
            )
        val context =
            buildJsonObject {
                put("companies", JsonArray(listOf(Json.encodeToJsonElement(company))))
                put("total", 1)
                put("checked_at", "2026-10-01T09:00:00+09:00")
            }
        val state =
            CareerCoachUiState(
                initialized = true,
                stale = false,
                draft = "この企業の準備を進めたい",
                execution = execution,
                capabilitiesChecked = true,
                capabilities =
                    CareerCapabilities(
                        state = "available",
                        features = listOf("execution_config"),
                        models =
                            listOf(
                                CareerModelOption(
                                    execution.provider,
                                    execution.model,
                                    reasoning_efforts = listOf("medium"),
                                ),
                            ),
                    ),
                snapshot =
                    CareerCoachSnapshot(
                        messages =
                            listOf(
                                CareerCoachMessage(
                                    "user",
                                    "run",
                                    "user",
                                    "就活を進めるため、次に何をすればいい？",
                                    1,
                                    "2026-10-01T00:00:00Z",
                                ),
                                CareerCoachMessage(
                                    "answer",
                                    "run",
                                    "assistant",
                                    "まずは企業のログイン設定を確認しましょう。\n\n" +
                                        "保存された情報では、取得用の設定が未完了です。" +
                                        "企業サイトへの登録が済んでいないとは断定できません。\n\n" +
                                        "設定後にマイページを確認し、届いている案内と締切から次の対応を決めましょう。",
                                    2,
                                    "2026-10-01T00:01:00Z",
                                ),
                            ),
                        company_context = context,
                    ),
            )
        InstrumentationRegistry.getInstrumentation().runOnMainSync { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            HermesControlTheme {
                HermesScaffold(
                    title = { Text("就活の相談") },
                    navigationIcon = NavIcon.Back {},
                    drawerGesturesEnabled = false,
                ) {
                    CareerCoachPanel(state, null, CareerCoachActions(), {})
                }
            }
        }
        compose.onNodeWithTag("career_message_answer").assertIsDisplayed()
        compose.onNodeWithTag("career_coach_input").assertIsDisplayed()
        capture("career-consultation-updated.png")
        compose.onNodeWithTag("career_consultation_context").performClick()
        compose.onNodeWithText("株式会社サンプル").assertIsDisplayed()
        compose.onNodeWithText("ログインID・パスワード未設定").assertIsDisplayed()
        compose.onNodeWithTag("consultation_company_settings_synthetic-company").assertIsDisplayed()
        capture("career-company-context.png")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val directory = File(output).apply { mkdirs() }
        File(directory, name).outputStream().use { stream ->
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(
                Bitmap.CompressFormat.PNG,
                100,
                stream,
            )
        }
    }
}
