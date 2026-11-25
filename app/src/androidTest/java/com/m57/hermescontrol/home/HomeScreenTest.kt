package com.m57.hermescontrol.home

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.HermesControlApp
import com.m57.hermescontrol.career.CareerLocalStore
import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.ProfileContext
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatUiState
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.common.LocalAppUpdateViewModel
import com.m57.hermescontrol.ui.settings.AppUpdateViewModel
import com.m57.hermescontrol.worker.DefaultWorkGatewayClient
import com.m57.hermescontrol.worker.EscrowDisplay
import com.m57.hermescontrol.worker.EscrowRecord
import com.m57.hermescontrol.worker.WorkerScheduledJob
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class HomeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesControlApp
    private val api = mockk<HermesApiService>()
    private val updater = mockk<AppUpdateViewModel>(relaxed = true)
    private val link =
        MutableStateFlow(
            com.m57.hermescontrol.domain.hermes
                .RunSnapshot(),
        )
    private val scope = "home-fixture:${UUID.randomUUID()}"

    @Test fun chatResultIsReadOnlyWhenTheCompletedAnswerIsActuallyVisible() {
        val message = ChatMessage(id = "answer", role = MessageRole.ASSISTANT, content = "保存された回答")
        val visible = mutableStateOf(false)
        val item =
            HomeItem(
                "saved",
                homeVersion(message.content),
                "chat",
                "result",
                "回答",
                HomeTarget("chat", session_id = "saved"),
            )
        runBlocking {
            app.homeRepository.dao.save(HomeRow(scope, "chat", item.id, app.homeRepository.json.encodeToString(item)))
        }
        compose.setContent {
            val list = rememberLazyListState()
            HomeChatReadEffect(ChatUiState(messages = listOf(message), currentSessionId = "saved"), list)
            LazyColumn(state = list) {
                if (visible.value) {
                    item(key = "prose-answer") { Text(message.content) }
                } else {
                    item(key = "older") { Text("以前の会話") }
                }
            }
        }
        compose.onNodeWithText("以前の会話").assertIsDisplayed()
        runBlocking {
            org.junit.Assert.assertTrue(
                app.homeRepository.dao
                    .reads(scope)
                    .first()
                    .isEmpty(),
            )
        }
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText(message.content).assertIsDisplayed()
        compose.waitUntil(5_000) {
            runBlocking {
                app.homeRepository.dao.reads(scope).first().any {
                    it.version ==
                        item.version
                }
            }
        }
    }

    @Before fun prepare() {
        mockkObject(AuthManager, ApiClient, app.runRepository, DefaultWorkGatewayClient)
        link.value = link.value.copy(connection = link.value.connection.copy(state = LinkState.CONNECTING))
        every { app.runRepository.snapshot } returns link
        every { updater.homeUpdateCandidate } returns MutableStateFlow(null)
        every { ApiClient.hermesApi } returns api
        coEvery { api.todayPlan("default") } returns TodayPlan()
        coEvery { api.todaySettings("default") } returns TodaySettings()
        coEvery { DefaultWorkGatewayClient.fetchPendingEscrows() } returns Result.success(emptyList())
        coEvery { DefaultWorkGatewayClient.listSubagents() } returns Result.success(emptyList())
        coEvery { DefaultWorkGatewayClient.listScheduledJobs() } returns Result.success(emptyList())
        mockkObject(AppScope)
        every { AppScope.connectionKey() } returns scope
        every { AuthManager.uiPreferenceScope() } returns scope
        every { AuthManager.contextFlow } returns MutableStateFlow<ProfileContext?>(null)
        every { AuthManager.activeProfileId } returns MutableStateFlow<String?>(null)
        every { AuthManager.isGatedMode() } returns true
        every { AuthManager.connectionEpoch } returns MutableStateFlow(0L)
        mockkConstructor(CareerLocalStore::class)
        coEvery { anyConstructed<CareerLocalStore>().enabled() } returns false
    }

    @After fun cleanup() {
        unmockkConstructor(CareerLocalStore::class)
        unmockkObject(AppScope, AuthManager, ApiClient, app.runRepository, DefaultWorkGatewayClient)
    }

    private fun showHome() {
        compose.setContent {
            CompositionLocalProvider(LocalAppUpdateViewModel provides updater) { HomeScreen() }
        }
    }

    @Test
    fun approvalDeadlineIsVisibleWithTheDecisionPillClosed() {
        val now = Instant.parse("2026-11-23T00:00:00Z")
        val urgent =
            EscrowRecord(
                id = "urgent",
                digest = "fixture",
                display = EscrowDisplay(title = "期限の近い承認"),
                expires_at = now.plusSeconds(1800).toString(),
            )
        val later =
            urgent.copy(
                id = "later",
                display = EscrowDisplay(title = "明日以降の承認"),
                expires_at = now.plusSeconds(172800).toString(),
            )
        val careerReply =
            HomeItem(
                id = "reply",
                version = "one",
                provider = "career",
                category = "action",
                title = "相談への回答が必要です",
                target = HomeTarget("conversation", thread_id = "thread", run_id = "run"),
            )
        var openedWork = false
        var openedItem: HomeItem? = null
        compose.setContent {
            HomeFeedContent(
                scope = scope,
                actions = listOf(careerReply),
                results = emptyList(),
                pending = emptyList(),
                linkState = LinkState.CONNECTED,
                onOpen = { openedItem = it },
                onAttention = {},
                approvals = listOf(later, urgent),
                now = now,
                onOpenWork = { openedWork = true },
            )
        }
        val pill = compose.onNodeWithTag("home_status_pill")
        pill.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "折りたたみ中"))
        compose.onNodeWithText("Hermes · 判断待ち 3件").assertIsDisplayed()
        compose.onNodeWithText("期限の近い承認").assertIsDisplayed()
        compose.onNodeWithText("期限 今日 09:30 · 残り30分").assertIsDisplayed()
        compose.onNodeWithText("明日以降の承認").assertDoesNotExist()
        compose.onNodeWithTag("home_focus_action").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(openedWork) }
        pill.performClick().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "展開中"))
        compose.onNodeWithText("明日以降の承認").assertIsDisplayed()
        compose.onNodeWithText(careerReply.title).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(careerReply, openedItem) }
    }

    private fun seedResults(count: Int) =
        runBlocking {
            repeat(count) { index ->
                val item =
                    HomeItem(
                        "session:$index",
                        "v1",
                        "chat",
                        "result",
                        "回答 ${index + 1}",
                        HomeTarget("chat", session_id = "session:$index"),
                        updated_at = "2026-09-${(20 - index).toString().padStart(2, '0')}",
                    )
                app.homeRepository.dao.save(
                    HomeRow(scope, "chat", item.id, app.homeRepository.json.encodeToString(item)),
                )
            }
        }

    @Test fun showsFiveResultsThenExpandsAndHidesEmptyActionHeading() {
        seedResults(6)
        showHome()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("届いた結果").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("届いた結果").assertIsDisplayed()
        compose.onNodeWithText("対応が必要").assertDoesNotExist()
        compose.onNodeWithText("回答 6").assertDoesNotExist()
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("残り1件を表示"))
        compose.onNodeWithText("残り1件を表示").performClick()
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("回答 6"))
        compose.onNodeWithText("回答 6").assertIsDisplayed()
    }

    @Test fun homeVisibilityCanBeDisabledWithoutDeletingResults() {
        seedResults(1)
        showHome()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("回答 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("回答 1").assertIsDisplayed()
        compose.onNodeWithTag("home_settings").performClick()
        compose.onNodeWithTag("home_settings_sheet").assertIsDisplayed()
        compose.onNodeWithTag("home_show_chat").performClick()
        compose.waitUntil(5_000) {
            runBlocking {
                app.homeRepository.dao
                    .provider(scope, "chat")
                    ?.enabled == false
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("回答 1").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("回答 1").assertDoesNotExist()
        runBlocking {
            assertFalse(
                app.homeRepository.dao
                    .provider(scope, "chat")!!
                    .enabled,
            )
            org.junit.Assert.assertNotNull(app.homeRepository.dao.item(scope, "chat", "session:0"))
        }
    }

    @Test fun failedRefreshRetainsResultsAndNeverClaimsAnEmptyCheckedFeed() {
        coEvery { anyConstructed<CareerLocalStore>().enabled() } returns true
        coEvery { api.careerHome(any(), any()) } throws
            HttpException(Response.error<Any>(503, """{"detail":{"code":"storage_unavailable"}}""".toResponseBody()))
        val item = HomeItem("saved", "one", "career", "result", "保存済みの結果", HomeTarget("conversation"))
        runBlocking {
            app.homeRepository.dao.replace(
                scope,
                listOf(HomeRow(scope, "career", item.id, app.homeRepository.json.encodeToString(item))),
                app.homeRepository.json.encodeToString(HomePage(checked_at = "2026-09-16T00:00:00Z")),
            )
        }
        showHome()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("保存済みの結果").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("保存済みの結果").assertIsDisplayed()
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("就活情報を読み直せません。前回の情報を表示しています。"))
        compose.onNodeWithText("就活情報を読み直せません。前回の情報を表示しています。").assertIsDisplayed()
        compose.onNodeWithText("確認できた情報には、ホームに表示する対応・結果はありません。").assertDoesNotExist()
    }

    @Test
    fun disabledTodayShowsOnlySetupLineUntilSettingsOpens() {
        showHome()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("設定する").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("設定する").performClick()
        compose.onNodeWithTag("home_settings_sheet").assertIsDisplayed()
        compose.onNodeWithText("確認するメールアドレス").assertIsDisplayed()
    }

    @Test
    fun pullReloadsPlanWithoutStartingSynthesis() {
        val calls = AtomicInteger()
        val currentDay = LocalDate.now(ZoneId.of("Asia/Tokyo")).toString()
        coEvery { api.todayPlan("default") } coAnswers {
            val day = if (calls.incrementAndGet() == 1) currentDay else "2025-01-01"
            TodayPlan(plan_day = day)
        }
        coEvery { api.todaySettings("default") } returns TodaySettings(email = "test@example.org", enabled = true)
        showHome()
        compose.waitUntil(10_000) { calls.get() == 1 }
        compose.onNodeWithTag("today_refresh").assertIsEnabled()
        compose.onNodeWithTag("home_list").performTouchInput { swipeDown() }
        compose.waitUntil(10_000) { calls.get() >= 2 }
        coVerify(exactly = 2) { api.todayPlan("default") }
        coVerify(exactly = 0) { api.refreshToday(any(), any()) }
    }

    @Test
    fun reconnectReloadsRoutinesWhileInitialWorkerPassIsStillInFlight() {
        val calls = AtomicInteger()
        val firstPass = CompletableDeferred<Unit>()
        coEvery { DefaultWorkGatewayClient.listScheduledJobs() } coAnswers {
            if (calls.incrementAndGet() == 1) {
                firstPass.await()
                Result.success(emptyList())
            } else {
                Result.success(
                    listOf(WorkerScheduledJob("next", "確認予定", "0 8 * * *", true, "2027-01-01T09:00:00+09:00", null)),
                )
            }
        }
        showHome()
        compose.waitUntil(10_000) { calls.get() == 1 }
        try {
            compose.runOnIdle {
                link.value = link.value.copy(connection = link.value.connection.copy(state = LinkState.CONNECTED))
            }
            compose.waitForIdle()
            assertEquals(1, calls.get())
        } finally {
            firstPass.complete(Unit)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("確認予定").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("確認予定").assertIsDisplayed()
        assertEquals(2, calls.get())
    }
}
