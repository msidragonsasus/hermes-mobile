package com.m57.hermescontrol.career

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CareerThreadsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val model = mockk<CareerThreadsViewModel>(relaxed = true)

    @Test fun frozenEsReferenceIsReadableAndRemovingItDoesNotSend() {
        var removed = 0
        var sent = 0
        val coach =
            CareerCoachUiState(
                initialized = true,
                stale = false,
                snapshot = CareerCoachSnapshot(),
                draft = "この回答を相談したい",
                target = CareerTarget("offers", "offer-a"),
                answerContext =
                    kotlinx.serialization.json.buildJsonObject {
                        put("question_id", kotlinx.serialization.json.JsonPrimitive("q1"))
                        put("question_version", kotlinx.serialization.json.JsonPrimitive("v3"))
                        put("body", kotlinx.serialization.json.JsonPrimitive("まだ保存していない回答の本文"))
                    },
            )
        compose.setContent {
            HermesControlTheme {
                CareerCoachPanel(coach, null, CareerCoachActions(clearTarget = { removed++ }, send = { sent++ }), {})
            }
        }
        compose.onNodeWithText("まだ保存していない回答の本文").assertDoesNotExist()
        compose.onNodeWithText("参照資料 1件").performClick()
        compose.onNodeWithText("まだ保存していない回答の本文").assertIsDisplayed()
        compose.onNodeWithText("参照を外す").performClick()
        compose.runOnIdle {
            assertEquals(1, removed)
            assertEquals(0, sent)
        }
        compose.onNodeWithTag("career_coach_input").assertIsDisplayed()
    }

    private val state =
        CareerThreadsState(
            loading = false,
            supported = true,
            capabilitiesChecked = true,
            stale = false,
            threads =
                listOf(
                    CareerThread("thread:research", "A社の企業研究", "a", company_name = "A社"),
                    CareerThread("thread:interview", "A社の面接対策", "a", company_name = "A社"),
                    CareerThread("thread:other", "B社の相談", "b"),
                    CareerThread("thread:theme", "自己分析"),
                    CareerThread("thread:old", "過去の面接対策", "a", archived = true),
                ),
        )

    @Before fun setup() {
        NavigationController.attachAuth(NavBackStack<NavKey>(CareerScreenKey))
    }

    @Test fun companyThreadsPreserveSelectedMailAndNeverSendOnNavigation() {
        val target =
            CareerTarget("companies", "a", source_refs = listOf(CareerSourceRef("mail", "mail-a", "account-a")))
        compose.setContent {
            HermesControlTheme {
                CareerThreadsContent(state, model, CareerThreadsKey("a", target = target))
            }
        }
        compose.onNodeWithText("B社の相談").assertDoesNotExist()
        compose.onNodeWithText("A社の企業研究").assertIsDisplayed()
        compose.onNodeWithText("A社の面接対策").performScrollTo().performClick()
        compose.runOnIdle {
            val route = NavigationController.backStack!!.last() as CareerConversationKey
            assertEquals("thread:interview", route.threadId)
            assertEquals(target, route.target)
            verify(exactly = 0) { model.create(any(), any(), any()) }
            verify(exactly = 0) { model.update(any(), any(), any()) }
        }
    }

    @Test fun newConsultationSkipsNamingAndArchiveCanBeRestored() {
        compose.setContent { HermesControlTheme { CareerThreadsContent(state, model) } }
        compose.onNodeWithText("新しい相談").performClick()
        compose.onNodeWithText("テーマ・会話名").assertDoesNotExist()
        compose.runOnIdle {
            verify { model.startConversation(CareerThreadsKey()) }
            verify(exactly = 0) { model.create(any(), any(), any()) }
        }
        compose.onNodeWithTag("career_threads_menu").performClick()
        compose.onNodeWithText("アーカイブを表示").performClick()
        compose.onNodeWithText("過去の面接対策").assertIsDisplayed()
        compose.onNodeWithTag("career_thread_menu_thread:old").performClick()
        compose.onNodeWithText("復元").performClick()
        compose.runOnIdle { verify { model.update(state.threads.last(), archived = false) } }
        compose.onNodeWithText("アーカイブ · 相談一覧に戻る").performClick()
        compose.onNodeWithText("自己分析").performScrollTo().assertIsDisplayed()
    }

    @Test fun largeFont360DarkKeepsSearchAndCreationReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                HermesControlTheme(themePreference = ThemePreference.DARK) {
                    Box(Modifier.width(360.dp)) { CareerThreadsContent(state, model) }
                }
            }
        }
        compose.onNodeWithText("会話を探す").performTextInput("自己分析")
        compose.onNodeWithTag("career_thread_thread:theme").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("新しい相談").performScrollTo().performClick()
        compose.onNodeWithText("テーマ・会話名").assertDoesNotExist()
        compose.runOnIdle { verify { model.startConversation(CareerThreadsKey()) } }
    }

    @Test fun cardsShowPlainPreviewReadableTimeAndConversationKind() {
        val rich =
            state.copy(
                threads =
                    listOf(
                        CareerThread(
                            "thread:a",
                            "A社の面接対策",
                            "a",
                            updated_at = "2026-01-02T00:30:00+00:00",
                            last_message = "**重要** 面接の準備",
                            company_name = "A社",
                        ),
                        CareerThread("thread:g", "自己分析", last_message = "## 強み\n- 粘り強さ"),
                    ),
            )
        compose.setContent { HermesControlTheme { CareerThreadsContent(rich, model) } }
        // The card is one merged node, so each Text stays an exact entry in its text list; only the
        // joined kind/time line needs substring matching, scoped to the card it belongs to.
        val cardA = hasTestTag("career_thread_thread:a")
        val cardG = hasTestTag("career_thread_thread:g")
        compose.onNode(cardA and hasText("重要 面接の準備")).assertIsDisplayed()
        compose.onNode(cardG and hasText("強み 粘り強さ")).assertIsDisplayed()
        compose.onNodeWithText("**重要**", substring = true).assertDoesNotExist()
        compose.onNodeWithText("2026-01-02T00:30:00+00:00", substring = true).assertDoesNotExist()
        val updated = careerThreadUpdatedAt("2026-01-02T00:30:00+00:00")
        compose.onNode(cardA and hasText("企業の相談 · A社 · $updated")).assertIsDisplayed()
        compose.onNode(cardG and hasText("全体相談")).assertIsDisplayed()
        compose.onNodeWithTag("career_thread_menu_thread:a").assertIsDisplayed()
    }

    @Test fun newDraftInputIsFocusedWhenRequested() {
        val coach = CareerCoachUiState(initialized = true, stale = false, snapshot = CareerCoachSnapshot())
        compose.setContent {
            HermesControlTheme {
                CareerCoachPanel(coach, null, CareerCoachActions(), {}, focusInput = true)
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("career_coach_input") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("career_coach_input").assertIsFocused()
    }

    @Test fun defaultCoachPanelDoesNotStealFocus() {
        val coach = CareerCoachUiState(initialized = true, stale = false, snapshot = CareerCoachSnapshot())
        compose.setContent { HermesControlTheme { CareerCoachPanel(coach, null, CareerCoachActions(), {}) } }
        compose.waitForIdle()
        compose.onNodeWithTag("career_coach_input").assertIsNotFocused()
    }

    @Test fun oldServerExplainsUpgradeAndKeepsHistory() {
        compose.setContent {
            HermesControlTheme {
                CareerThreadsContent(
                    state.copy(supported = false, threads = listOf(CareerThread("global", "これまでの相談"))),
                    model,
                )
            }
        }
        compose.onNodeWithText("新しい相談").assertIsNotEnabled()
        compose.onNodeWithText("これまでの相談").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("global", (NavigationController.backStack!!.last() as CareerConversationKey).threadId)
        }
    }

    @Test fun conversationKeepsProviderInSheetAndStopsTheSelectedRun() {
        val stops = mutableListOf<String>()
        val execution = CareerExecution("test-provider", "compact-model", "high")
        val coach =
            CareerCoachUiState(
                initialized = true,
                stale = false,
                execution = execution,
                snapshot =
                    CareerCoachSnapshot(
                        messages = listOf(CareerCoachMessage("m1", "r1", "assistant", "面接の準備を始めましょう。")),
                        runs = listOf(CareerCoachRun("r1", "message", "running", execution = execution)),
                    ),
            )
        compose.setContent {
            HermesControlTheme {
                CareerCoachPanel(
                    coach,
                    null,
                    CareerCoachActions(control = {
                        run,
                        action,
                        ->
                        stops += "${run.id}:$action"
                    }),
                    {},
                )
            }
        }
        compose.onNodeWithText("面接の準備を始めましょう。").assertIsDisplayed()
        compose.onNodeWithText("test-provider", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("career_coach_input").assertIsDisplayed()
        compose.onNodeWithText("停止").performClick()
        compose.runOnIdle { assertEquals(listOf("r1:stop"), stops) }
        compose.onNodeWithText("詳細").performClick()
        compose.onNodeWithText("test-provider", substring = true).assertIsDisplayed()
    }
}
