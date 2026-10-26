package com.m57.hermescontrol.career

import com.m57.hermescontrol.home.HomeItem
import com.m57.hermescontrol.home.HomePage
import com.m57.hermescontrol.home.HomeTarget
import com.m57.hermescontrol.home.homeItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CareerHomeFeedTest {
    private val today = LocalDate.of(2026, 10, 5)

    private fun action(
        id: String,
        kind: String,
        deadline: String? = null,
    ) = HomeItem(id, "v1", "career", "action", id, HomeTarget(kind), deadline = deadline)

    @Test
    fun sameResponseKeepsAllShellHomeActionsEvenWithoutMatchingSnapshotEntities() {
        val items =
            listOf(
                action("offer-deadline", "company", "2026-10-05"),
                action("mail-deadline", "mail", "2026-10-06T00:30:00+09:00"),
                action("needs-reply", "conversation"),
                action("needs-settings", "settings"),
                action("result", "company").copy(category = "result"),
            )
        val shell = homeItems(items, emptyMap(), today).filter { it.category == "action" }
        val career = careerHomeActions(HomePage(items = items), today)
        assertEquals(shell, career)
        assertEquals(4, career.size)
        // The old conversation-only filter dropped the three other action types.
        assertEquals(1, items.count { it.category == "action" && it.target.kind == "conversation" })
        assertEquals(emptyList<CareerEntity>(), careerHomeAgenda(emptyList(), LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun actionDeadlinesKeepTimezoneDateOnlyAndUnknownWhileHidingExpiredValues() {
        val items =
            listOf(
                action("utc", "company", "2026-10-04T15:00:01Z"),
                action("date", "mail", "2026-10-05"),
                action("unknown", "mail", "調整中"),
                action("overdue", "company", "2026-10-04").copy(overdue = true),
            )
        val actions = careerHomeActions(HomePage(items = items), today)
        assertEquals(items.filterNot { it.id == "overdue" }.associateBy { it.id }, actions.associateBy { it.id })
        assertEquals(LocalDate.of(2026, 10, 5), agendaDate(actions.single { it.id == "utc" }.deadline).date)
        assertNull(agendaDate(actions.single { it.id == "date" }.deadline).time)
        assertFalse(actions.any { it.id == "overdue" })
    }

    @Test
    fun pastCalendarEntriesAndExpiredServerActionsStayHiddenWithoutResolvingSources() {
        val entity =
            CareerEntity("tasks", "past-task", 1, buildJsonObject { put("deadline", "2026-10-04") })
        assertEquals(emptyList<CareerEntity>(), careerHomeAgenda(listOf(entity), LocalDate.of(2026, 10, 5)))
        val overdue = action("past-task", "company", "2026-10-04").copy(overdue = true)
        assertEquals(emptyList<HomeItem>(), careerHomeActions(HomePage(items = listOf(overdue)), today))
    }

    @Test
    fun otherProvidersAndResultsDoNotBecomeCareerActions() {
        val item = action("item", "company")
        assertEquals(
            emptyList<HomeItem>(),
            careerHomeActions(
                HomePage(items = listOf(item.copy(provider = "chat"), item.copy(category = "result"))),
                today,
            ),
        )
        assertEquals(emptyList<HomeItem>(), careerHomeActions(null, today))
    }

    @Test
    fun bothHomesExcludeExpiredActionsWithoutDroppingRawFeedItems() =
        runTest {
            val items =
                listOf(
                    action("seven-days", "company", "2026-09-28").copy(overdue = true),
                    action("eight-days", "mail", "2026-09-27").copy(overdue = true),
                    action("utc-boundary", "company", "2026-09-27T15:00:00Z"),
                    action("unknown", "mail", "調整中"),
                    action("undated", "conversation"),
                    action("future", "company", "2026-10-06"),
                    action("resolved", "company", "2026-09-27").copy(category = "resolved"),
                    action("result", "company", "2026-09-27").copy(category = "result"),
                )
            val page = HomePage(items = items)
            val feed = CareerHomeFeed { page }
            feed.refresh()
            val shell = homeItems(items, emptyMap(), today).filter { it.category == "action" }
            val career = careerHomeActions(feed.state.value.page, today)
            assertEquals(shell, career)
            assertEquals(
                setOf("unknown", "undated", "future"),
                career.map { it.id }.toSet(),
            )
            assertEquals(page, feed.state.value.page)
            assertEquals(items, page.items)
        }

    @Test
    fun refreshCanReplaceOrRemoveResolvedActionsWithoutAnEntitySnapshotChange() =
        runTest {
            val deadline = action("deadline", "company", "2026-10-05")
            var response = HomePage(items = listOf(deadline), checked_at = "2026-10-05T00:00:00Z")
            var reads = 0
            val feed =
                CareerHomeFeed {
                    reads++
                    response
                }
            feed.refresh()
            assertEquals(
                listOf(deadline),
                feed.state.value.page
                    ?.items,
            )
            response = HomePage(checked_at = "2026-10-05T00:01:00Z")
            feed.refresh()
            assertEquals(2, reads)
            assertEquals(
                emptyList<HomeItem>(),
                feed.state.value.page
                    ?.items,
            )
            assertEquals(
                response.checked_at,
                feed.state.value.page
                    ?.checked_at,
            )
            assertNull(feed.state.value.error)
        }

    @Test
    fun failedRefreshKeepsLastConfirmedPageAndSuccessfulRetryClearsError() =
        runTest {
            var failing = false
            val page = HomePage(items = listOf(action("deadline", "mail")))
            val feed = CareerHomeFeed { if (failing) error("synthetic offline") else page }
            feed.refresh()
            failing = true
            feed.refresh()
            assertEquals(page, feed.state.value.page)
            assertNotNull(feed.state.value.error)
            assertFalse(feed.state.value.loading)
            failing = false
            feed.refresh()
            assertNull(feed.state.value.error)
            assertEquals(page, feed.state.value.page)
        }

    @Test
    fun leavingScreenCancelsReadWithoutInventingAnEmptySuccessfulPage() =
        runTest {
            val feed = CareerHomeFeed { throw CancellationException("synthetic navigation") }
            try {
                feed.refresh()
                error("Expected cancellation")
            } catch (_: CancellationException) {
                assertNull(feed.state.value.page)
                assertNull(feed.state.value.error)
                assertFalse(feed.state.value.loading)
            }
        }
}
