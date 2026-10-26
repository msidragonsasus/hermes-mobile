package com.m57.hermescontrol.career

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CareerConversationUxTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun ready(snapshot: CareerCoachSnapshot = CareerCoachSnapshot()): CareerCoachUiState {
        val execution = CareerExecution("openai-codex", "test-model", "medium")
        return CareerCoachUiState(
            initialized = true,
            stale = false,
            draft = "送信前の相談",
            snapshot = snapshot,
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
        )
    }

    private fun messages(
        first: Int,
        last: Int,
    ) = (first..last).map {
        CareerCoachMessage("message-$it", "run-$it", "user", "相談 $it\n" + "読みやすい会話本文。".repeat(12), it.toLong())
    }

    @Test
    fun checkingCapabilitiesKeepsInputAvailableButCannotSendOrAccidentallySubmit() {
        val state = mutableStateOf(ready().copy(capabilitiesChecked = false))
        var sends = 0
        compose.setContent {
            MaterialTheme {
                CareerCoachPanel(state.value, null, CareerCoachActions(send = { sends++ }), {})
            }
        }
        compose.onNodeWithTag("career_coach_input").assertIsEnabled()
        compose.onNodeWithTag("career_coach_send").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(0, sends)
            state.value = ready()
        }
        compose.onNodeWithTag("career_coach_send").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, sends) }
    }

    @Test
    fun progressErrorAndBoundStopRemainVisibleWithoutOpeningDetails() {
        val run = CareerCoachRun("active", "message", "running")
        val state =
            mutableStateOf(
                ready(CareerCoachSnapshot(runs = listOf(run))).copy(
                    progress =
                        mapOf(run.id to "公式情報を確認"),
                ),
            )
        val stopped = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                CareerCoachPanel(
                    state.value,
                    null,
                    CareerCoachActions(control = { active, action ->
                        stopped +=
                            "${active.id}:$action"
                    }),
                    {},
                )
            }
        }
        compose.onNodeWithText("公式情報を確認").assertIsDisplayed()
        compose.onNodeWithText("回答は完了後に表示します").assertIsDisplayed()
        compose.onNodeWithTag("career_coach_stop").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("active:stop"), stopped)
            state.value = state.value.copy(stale = true, error = "接続を確認できません。入力は保持しています。")
        }
        compose.onNodeWithTag("career_coach_error").assertIsDisplayed()
        compose.onNodeWithTag("career_coach_stop").assertIsNotEnabled()
    }

    @Test
    fun firstLoadOpensLatestAndNewReplyDoesNotMoveReaderUntilLatestIsPressed() {
        val state = mutableStateOf(ready(CareerCoachSnapshot(messages = messages(1, 30))))
        lateinit var list: LazyListState
        compose.setContent {
            list = rememberLazyListState()
            MaterialTheme { CareerCoachPanel(state.value, null, CareerCoachActions(), {}, listState = list) }
        }
        compose.waitUntil(5000) { list.firstVisibleItemIndex > 0 && !list.canScrollForward }
        compose.onNodeWithTag("career_message_message-30").assertIsDisplayed()
        compose.onNodeWithTag("career_coach_conversation").performScrollToIndex(2)
        compose.waitForIdle()
        val anchor = list.firstVisibleItemIndex
        compose.runOnIdle { state.value = state.value.copy(snapshot = CareerCoachSnapshot(messages = messages(1, 31))) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(anchor, list.firstVisibleItemIndex) }
        compose.onNodeWithTag("career_coach_latest").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { !list.canScrollForward }
        compose.onNodeWithTag("career_message_message-31").assertIsDisplayed()
    }

    @Test
    fun loadingOlderHistoryPreservesMessageAnchorInsteadOfJumpingToNewPageOrLatest() {
        val state = mutableStateOf(ready(CareerCoachSnapshot(messages = messages(21, 40), older_before = 21)))
        lateinit var list: LazyListState
        compose.setContent {
            list = rememberLazyListState()
            MaterialTheme {
                CareerCoachPanel(
                    state.value,
                    null,
                    CareerCoachActions(older = { state.value = state.value.copy(loading = true, loadingOlder = true) }),
                    {},
                    listState = list,
                )
            }
        }
        compose.waitUntil(5000) { !list.canScrollForward }
        compose.onNodeWithTag("career_coach_conversation").performScrollToIndex(0)
        compose.waitForIdle()
        val visible = list.layoutInfo.visibleItemsInfo.first { it.key == "message-21" }
        val offset = visible.offset
        compose.onNodeWithTag("career_coach_older").performClick()
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    loading = false,
                    loadingOlder = false,
                    snapshot = CareerCoachSnapshot(messages = messages(1, 40)),
                )
        }
        compose.waitUntil(5000) { list.layoutInfo.visibleItemsInfo.any { it.key == "message-21" } }
        compose.waitForIdle()
        compose.runOnIdle {
            val restored = list.layoutInfo.visibleItemsInfo.first { it.key == "message-21" }
            assertTrue(kotlin.math.abs(restored.offset - offset) <= 1)
        }
        compose.onNodeWithTag("career_coach_latest").assertIsDisplayed()
    }
}
