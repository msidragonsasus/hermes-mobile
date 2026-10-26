package com.m57.hermescontrol.home

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.HermesControlApp
import com.m57.hermescontrol.career.CareerLocalStore
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.ProfileContext
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatUiState
import com.m57.hermescontrol.ui.chat.MessageRole
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.UUID

class HomeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesControlApp
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
        mockkObject(AuthManager)
        every { AuthManager.uiPreferenceScope() } returns scope
        every { AuthManager.contextFlow } returns MutableStateFlow<ProfileContext?>(null)
        every { AuthManager.activeProfileId } returns MutableStateFlow<String?>(null)
        every { AuthManager.isGatedMode() } returns true
        mockkConstructor(CareerLocalStore::class)
        coEvery { anyConstructed<CareerLocalStore>().enabled() } returns false
    }

    @After fun cleanup() {
        unmockkConstructor(CareerLocalStore::class)
        unmockkObject(AuthManager, ApiClient)
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
        compose.setContent { HomeScreen() }
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
        compose.setContent { HomeScreen() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("回答 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("回答 1").assertIsDisplayed()
        compose.onNodeWithText("表示するApp").performClick()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.waitForIdle()
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
        val api = mockk<HermesApiService>()
        coEvery { api.careerHome(any(), any()) } throws IOException("synthetic")
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns api
        val item = HomeItem("saved", "one", "career", "result", "保存済みの結果", HomeTarget("conversation"))
        runBlocking {
            app.homeRepository.dao.replace(
                scope,
                listOf(HomeRow(scope, "career", item.id, app.homeRepository.json.encodeToString(item))),
                app.homeRepository.json.encodeToString(HomePage(checked_at = "2026-09-16T00:00:00Z")),
            )
        }
        compose.setContent { HomeScreen() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("保存済みの結果").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("保存済みの結果").assertIsDisplayed()
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("就活情報を読み直せません。前回の情報を表示しています。"))
        compose.onNodeWithText("就活情報を読み直せません。前回の情報を表示しています。").assertIsDisplayed()
        compose.onNodeWithText("確認できた情報には、ホームに表示する対応・結果はありません。").assertDoesNotExist()
    }
}
