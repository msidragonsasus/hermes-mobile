package com.m57.hermescontrol.career

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.home.HomeFeedContent
import com.m57.hermescontrol.home.HomeItem
import com.m57.hermescontrol.home.HomePage
import com.m57.hermescontrol.home.HomeTarget
import com.m57.hermescontrol.home.homeItems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class CareerHomeActionsTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 5)

    private val deadline =
        HomeItem(
            "synthetic-deadline",
            "v1",
            "career",
            "action",
            "合成データ：ES提出の締切",
            HomeTarget("company", company_id = "synthetic-company", entity_id = "synthetic-task"),
            deadline = "2026-10-05",
        )

    @Test
    fun sameServerResponseShowsDeadlineInBothHomesWithoutSnapshotEntity() {
        val career = mutableStateOf(false)
        val page = HomePage(items = listOf(deadline))
        var opened: HomeItem? = null
        compose.setContent {
            MaterialTheme {
                if (career.value) {
                    CareerDashboardContent(
                        state = CareerUiState(initialized = true, enabled = true, snapshot = CareerSnapshot(1)),
                        page = page,
                        pageError = null,
                        pageLoading = false,
                        onSelect = {},
                        onTask = {},
                        onAttention = {},
                        onResult = { opened = it },
                        onSettings = {},
                        today = today,
                    )
                } else {
                    HomeFeedContent(
                        scope = "synthetic-only",
                        actions = homeItems(page.items, emptyMap(), today).filter { it.category == "action" },
                        results = emptyList(),
                        pending = emptyList(),
                        linkState = LinkState.CONNECTED,
                        loading = false,
                        error = null,
                        storageError = null,
                        metadata = page,
                        careerVisible = true,
                        careerEnabled = true,
                        chatVisible = true,
                        onRefresh = {},
                        onProviderChange = { _, _ -> },
                        onOpen = { opened = it },
                        onAttention = {},
                        onConnectionSettings = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("home_list").performScrollToNode(hasText(deadline.title))
        compose.onNodeWithText(deadline.title).assertIsDisplayed()
        compose.runOnIdle { career.value = true }
        compose.onNodeWithTag("career_agenda").performScrollToNode(hasText(deadline.title))
        compose.onNodeWithText(deadline.title).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(deadline, opened) }
        compose.onNodeWithText("これからの作業・予定はありません。企業から募集・締切を確認できます。").assertDoesNotExist()
    }

    @Test
    fun expiredSourceDisappearsAtExactDeadlineWhileUpcomingAndOriginalFeedRemain() {
        val now = mutableStateOf(Instant.parse("2026-10-05T05:59:59Z"))
        val exact = deadline.copy(id = "exact", title = "synthetic exact 15:00", deadline = "2026-10-05T15:00+09:00")
        val expired = deadline.copy(id = "expired", title = "synthetic expired", deadline = "2026-10-04")
        val unknown = deadline.copy(id = "unknown", title = "synthetic unknown", deadline = null, overdue = true)
        val page = HomePage(items = listOf(expired, exact, deadline, unknown))
        compose.setContent {
            MaterialTheme {
                CareerDashboardContent(
                    state = CareerUiState(initialized = true, enabled = true, snapshot = CareerSnapshot(1)),
                    page = page,
                    pageError = null,
                    pageLoading = false,
                    onSelect = {},
                    onTask = {},
                    onAttention = {},
                    onResult = {},
                    onSettings = {},
                    today = today,
                    now = now.value,
                )
            }
        }
        compose.onNodeWithText(expired.title).assertDoesNotExist()
        compose.onNodeWithTag("career_agenda").performScrollToNode(hasText(exact.title))
        compose.onNodeWithText(exact.title).assertIsDisplayed()
        compose.runOnIdle { now.value = Instant.parse("2026-10-05T06:00:00Z") }
        compose.onNodeWithText(exact.title).assertDoesNotExist()
        compose.onNodeWithTag("career_agenda").performScrollToNode(hasText(deadline.title))
        compose.onNodeWithText(deadline.title).assertIsDisplayed()
        compose.onNodeWithTag("career_agenda").performScrollToNode(hasText(unknown.title))
        compose.onNodeWithText(unknown.title).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(expired, exact, deadline, unknown), page.items) }
    }

    @Test
    fun unavailableFeedDoesNotClaimThereAreNoUpcomingTasks() {
        compose.setContent {
            MaterialTheme {
                CareerDashboardContent(
                    state = CareerUiState(initialized = true, enabled = true, snapshot = CareerSnapshot(1)),
                    page = null,
                    pageError = "synthetic offline",
                    pageLoading = false,
                    onSelect = {},
                    onTask = {},
                    onAttention = {},
                    onResult = {},
                    onSettings = {},
                )
            }
        }
        val text = "最新の作業・予定は未確認です。更新するか企業から募集・締切を確認してください。"
        compose.onNodeWithTag("career_agenda").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithText("これからの作業・予定はありません。企業から募集・締切を確認できます。").assertDoesNotExist()
    }
}
