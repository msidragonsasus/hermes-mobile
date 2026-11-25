package com.m57.hermescontrol.home

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.m57.hermescontrol.NavigationController
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Rule
import org.junit.Test

class HomeCardTest {
    @get:Rule val compose = createComposeRule()

    @After fun cleanup() {
        unmockkObject(NavigationController)
    }

    @Test
    fun resultOpensExactSourceWithoutDiscardingItsVersionOrScope() {
        mockkObject(NavigationController)
        every { NavigationController.navigateTo(any()) } returns Unit
        val item =
            HomeItem(
                "source:mail:a:m",
                "revision",
                "career",
                "result",
                "選考結果のご案内",
                HomeTarget("mail", company_id = "company", source_id = "m", account_id = "a"),
            )
        compose.setContent { HomeCard(item, "server/profile") }
        compose.onNodeWithText("就活").assertIsDisplayed()
        compose.onNodeWithText("選考結果のご案内").assertIsDisplayed()
        compose.onNodeWithText(item.title).assertHasClickAction().performClick()
        verify(exactly = 1) { NavigationController.navigateTo(HomeDetailKey("server/profile", item)) }
    }

    @Test
    fun overdueActionDoesNotOfferCompletionOnHome() {
        mockkObject(NavigationController)
        every { NavigationController.navigateTo(any()) } returns Unit
        val item =
            HomeItem(
                "task",
                "v1",
                "career",
                "action",
                "書類提出",
                HomeTarget("company", company_id = "c"),
                deadline = "2020-09-15",
                overdue = true,
            )
        compose.setContent { HomeCard(item, "scope") }
        compose.onNodeWithText("期限超過・未完了：2020年9月15日").assertIsDisplayed()
        compose.onNodeWithText("対応完了").assertDoesNotExist()
        compose.onNodeWithText(item.title).assertHasClickAction().performClick()
        verify(exactly = 1) { NavigationController.navigateTo(HomeDetailKey("scope", item)) }
    }
}
