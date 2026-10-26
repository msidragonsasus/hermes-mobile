package com.m57.hermescontrol.career

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.NavigationController
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Dummy UI fixtures only. Requires a device; compiling does not prove a UI pass. */
class CareerCompanyOrderUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val initial =
        CareerCompanyState(
            initialized = true,
            enabled = true,
            stale = false,
            companies =
                CareerCompanies(
                    companies =
                        listOf(
                            CareerCompanyRow("a", "Alpha", new_count = 1),
                            CareerCompanyRow("b", "Beta"),
                            CareerCompanyRow("c", "Gamma", new_count = 1),
                        ),
                    order_version = 0,
                ),
        )

    @Before
    fun navigation() {
        NavigationController.attachAuth(NavBackStack<NavKey>(CareerScreenKey))
    }

    @Test
    fun filteredArrowMovePreservesHiddenSlotAndSubmitsWholeOrder() {
        var submitted: List<String>? = null
        compose.setContent {
            MaterialTheme {
                CareerCompanyContent(initial, null, {}, {}, {}, embedded = true, onSaveOrder = { ids, _, _ ->
                    submitted =
                        ids
                })
            }
        }
        compose.onNodeWithText("新着あり").performClick()
        compose.onNodeWithTag("company_order_begin").performClick()
        compose.onNodeWithTag("company_order_down_a", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("company_order_save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("c", "b", "a"), submitted) }
    }

    @Test
    fun cancelDoesNotSendOrder() {
        var requests = 0
        compose.setContent {
            MaterialTheme {
                CareerCompanyContent(initial, null, {}, {}, {}, embedded = true, onSaveOrder = {
                    _,
                    _,
                    _,
                    ->
                    requests++
                })
            }
        }
        compose.onNodeWithTag("company_order_begin").performClick()
        compose.onNodeWithTag("company_order_down_a", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("キャンセル").performScrollTo().performClick()
        compose.onNodeWithTag("company_order_begin").assertExists()
        compose.runOnIdle { assertEquals(0, requests) }
    }

    @Test
    fun draggingFirstVisibleCompanyDownKeepsGestureActive() {
        var submitted: List<String>? = null
        val companies = checkNotNull(this.initial.companies)
        val initial =
            this.initial.copy(
                companies =
                    companies.copy(
                        companies = companies.companies + (1..12).map { CareerCompanyRow("extra$it", "Extra $it") },
                    ),
            )
        compose.setContent {
            MaterialTheme {
                CareerCompanyContent(initial, null, {}, {}, {}, embedded = true, onSaveOrder = { ids, _, _ ->
                    submitted =
                        ids
                })
            }
        }
        compose.onNodeWithTag("company_order_begin").performClick()
        compose.onNodeWithTag("career_company_list").performScrollToIndex(1)
        val distance =
            compose
                .onNodeWithTag("career_company_a")
                .fetchSemanticsNode()
                .boundsInRoot.height + 24f
        compose.onNodeWithTag("company_order_handle_a", useUnmergedTree = true).performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(Offset(0f, distance), delayMillis = 200)
            advanceEventTime(60)
            moveBy(Offset(0f, 8f), delayMillis = 50)
            up()
        }
        compose.onNodeWithTag("company_order_save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("b", "a", "c"), submitted?.take(3)) }
    }

    @Test
    fun unknownResultRetriesSamePayloadAndOperationId() {
        val state = mutableStateOf(initial)
        val requests = mutableListOf<Triple<List<String>, Int, String>>()
        compose.setContent {
            MaterialTheme {
                CareerCompanyContent(state.value, null, {}, {}, {}, embedded = true, onSaveOrder = {
                    ids,
                    version,
                    requestId,
                    ->
                    requests += Triple(ids, version, requestId)
                    state.value =
                        initial.copy(
                            stale = true,
                            orderWrite = CompanyWriteState(phase = CompanyWritePhase.UNKNOWN, requestId = requestId),
                        )
                })
            }
        }
        compose.onNodeWithTag("company_order_begin").performClick()
        compose.onNodeWithTag("company_order_save").performClick()
        compose.onNodeWithText("同じ内容で再試行").performClick()
        compose.runOnIdle {
            assertEquals(2, requests.size)
            assertEquals(requests[0], requests[1])
        }
    }

    @Test
    fun arrowMoveIntoFirstVisibleSlotKeepsMovedCardDisplayed() {
        val companies = checkNotNull(initial.companies)
        val longList =
            initial.copy(
                companies =
                    companies.copy(
                        companies = companies.companies + (1..12).map { CareerCompanyRow("extra$it", "Extra $it") },
                    ),
            )
        compose.setContent {
            MaterialTheme { CareerCompanyContent(longList, null, {}, {}, {}, embedded = true) }
        }
        compose.onNodeWithTag("company_order_begin").performClick()
        compose.onNodeWithTag("career_company_list").performScrollToIndex(2)
        compose.onNodeWithTag("company_order_up_c", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("career_company_c").assertIsDisplayed()
    }
}
