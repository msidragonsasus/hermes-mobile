package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.domain.hermes.ClarificationLock
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.domain.hermes.SessionRef
import com.m57.hermescontrol.ui.chat.components.ClarifyBubble
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ClarifyCardPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val ref =
        RequestRef(ConnectionScope("synthetic", "c", "p", "proc", 1), SessionRef("runtime-only"), "exact-request")

    private fun request(status: AttentionStatus = AttentionStatus.PENDING) =
        ClarifyUi(
            text = "どの資料を使いますか？",
            questionId = "q-exact",
            ref = ref,
            responseEnabled = true,
            questionStates = mapOf("q-exact" to status),
        )

    @Test
    fun pendingDetailsHideTechnicalIdsUntilExpanded() {
        compose.setContent { MaterialTheme { ClarifyBubble(request(), {}, {}, {}) } }
        compose.onNodeWithText("どの資料を使いますか？").assertIsDisplayed()
        compose.onNodeWithText("期限：不明").assertDoesNotExist()
        compose.onNodeWithText("exact-request", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("clarify_details").performClick()
        compose.onNodeWithText("exact-request", substring = true).assertIsDisplayed()
    }

    @Test
    fun typedDraftStaysReadableWhenResultBecomesUnknownWithoutResending() {
        val ui = mutableStateOf(request())
        val responses = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ClarifyBubble(ui.value, {
                    responses.add(it)
                    ui.value = ui.value.copy(questionStates = mapOf("q-exact" to AttentionStatus.UNKNOWN))
                }, {}, {})
            }
        }
        compose.onNodeWithTag("clarify_input_q-exact").performTextInput("入力した回答")
        compose.onNodeWithText("回答を送信").performClick()
        compose.onNodeWithText("送信内容（受付未確認）").assertIsDisplayed()
        compose.onNodeWithText("入力した回答").assertIsDisplayed()
        compose.onNodeWithTag("clarify_input_q-exact").assertDoesNotExist()
        compose.onNodeWithText("再送").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("入力した回答"), responses) }
    }

    @Test
    fun strictAckLockAndNullSkipAreReadableNotEditable() {
        val ui =
            mutableStateOf(
                request(AttentionStatus.CONFIRMED).copy(
                    questionLocks =
                        mapOf("q-exact" to ClarificationLock("受け付けた内容")),
                ),
            )
        compose.setContent { MaterialTheme { ClarifyBubble(ui.value, {}, {}, {}) } }
        compose.onNodeWithText("回答を受け付けました").assertIsDisplayed()
        compose.onNodeWithText("受け付けた内容").assertIsDisplayed()
        compose.onNodeWithTag("clarify_input_q-exact").assertDoesNotExist()
        compose.runOnIdle { ui.value = ui.value.copy(questionLocks = mapOf("q-exact" to ClarificationLock(null))) }
        compose.onNodeWithText("回答を辞退しました").assertIsDisplayed()
    }

    @Test
    fun expiredAndDisconnectedNeverExposeEditableInputOrAcceptance() {
        val ui = mutableStateOf(request(AttentionStatus.EXPIRED))
        compose.setContent { MaterialTheme { ClarifyBubble(ui.value, {}, {}, {}) } }
        compose.onNodeWithTag("clarify_input_q-exact").assertDoesNotExist()
        compose.onNodeWithText("回答を受け付けました").assertDoesNotExist()
        compose.runOnIdle { ui.value = request().copy(responseEnabled = false) }
        compose.onNodeWithTag("clarify_input_q-exact").assertDoesNotExist()
        compose.onNodeWithText("回答を受け付けました").assertDoesNotExist()
    }

    @Test
    fun batchMultiselectSendsOnlyEnteredAnswerableExactQids() {
        val answers = mutableListOf<Map<String, String>>()
        val ui =
            request().copy(
                questions =
                    listOf(
                        ClarifyQuestionUi("multi-exact", "使う資料", listOf("資料A", "資料B"), true),
                        ClarifyQuestionUi("locked-exact", "回答済み"),
                        ClarifyQuestionUi("blank-exact", "任意の補足"),
                    ),
                questionStates =
                    mapOf(
                        "multi-exact" to AttentionStatus.PENDING,
                        "locked-exact" to AttentionStatus.CONFIRMED,
                        "blank-exact" to AttentionStatus.PENDING,
                    ),
                questionLocks = mapOf("locked-exact" to ClarificationLock("既存回答")),
            )
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ClarifyBubble(ui, {}, { answers.add(it) }, {})
                }
            }
        }
        compose.onNodeWithText("入力した回答を送信").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("資料A").performScrollTo().performClick()
        compose.onNodeWithText("資料B").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(emptyList<Map<String, String>>(), answers) }
        compose.onNodeWithText("入力した回答を送信").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(mapOf("multi-exact" to "資料A, 資料B")), answers) }
    }

    @Test
    fun singleChoiceFastTapKeepsExactCallbackAndWaitsForAck() {
        val ui = mutableStateOf(request().copy(options = listOf("資料A", "資料B")))
        val responses = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ClarifyBubble(ui.value, {
                    responses.add(it)
                    ui.value = ui.value.copy(questionStates = mapOf("q-exact" to AttentionStatus.SUBMITTING))
                }, {}, {})
            }
        }
        compose.onNodeWithText("資料B").performClick()
        compose.onNodeWithText("送信中・受付確認待ち").assertIsDisplayed()
        compose.onNodeWithText("送信しました。受付の確認を待っています").assertDoesNotExist()
        compose.onNodeWithText("回答を受け付けました").assertDoesNotExist()
        compose.onNodeWithTag("clarify_input_q-exact").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("資料B"), responses) }
    }
}
