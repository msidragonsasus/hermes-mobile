package com.m57.hermescontrol.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class HomeItemsTest {
    private val today = LocalDate.of(2026, 9, 18)

    private fun result(version: String = "one") =
        HomeItem("reply", version, "career", "result", "回答", HomeTarget("conversation"))

    @Test
    fun readingAResultDoesNotResolveActionsAndUpdatedResultsReappear() {
        val result = result()
        val action = result.copy(id = "deadline", category = "action", deadline = "2026-09-18")
        val read = mapOf("career:reply" to "one", "career:deadline" to "one")
        assertEquals(listOf(action), homeItems(listOf(result, action), read, today))
        assertEquals(listOf(action, result("two")), homeItems(listOf(result("two"), action), read, today))
    }

    @Test
    fun contentReadInsideAppBeforeHomeVisitIsRespectedButChangesReappear() {
        val old = result().copy(content_hash = homeVersion("件名\n本文"))
        val receipts = mapOf("career:reply" to "content:${old.content_hash}")
        assertEquals(emptyList<HomeItem>(), homeItems(listOf(old), receipts, today))
        val changed = old.copy(version = "new", content_hash = homeVersion("件名\n新しい本文"))
        assertEquals(listOf(changed), homeItems(listOf(changed), receipts, today))
    }

    @Test
    fun providerReadKeysDoNotHideAnotherProvidersResult() {
        assertEquals(1, homeItems(listOf(result()), mapOf("chat:reply" to "one"), today).size)
    }

    private val now = Instant.parse("2026-09-18T06:00:00Z")

    private fun action(
        id: String,
        due: String?,
    ) = result().copy(id = id, category = "action", target = HomeTarget("mail"), deadline = due)

    @Test
    fun upcomingDeadlinesSortByActualInstantBeforeUnknownDates() {
        val utc = action("utc-near", "2026-09-18T07:00:00Z")
        val jst = action("jst-later", "2026-09-18T17:00:00+09:00")
        val date = action("date", "2026-09-18")
        val unknown = action("unknown", "調整中")
        val expired = action("expired", "2026-09-17")
        assertEquals(
            listOf(utc, jst, date, unknown),
            homeItems(listOf(unknown, jst, expired, date, utc), emptyMap(), now),
        )
        assertFalse(homeVersion("answer") == homeVersion("updated answer"))
    }

    @Test
    fun everyExpiredCalendarDayIsHiddenIncludingYesterday() {
        val items = (-9L..2L).map { offset -> action(offset.toString(), today.plusDays(offset).toString()) }
        assertEquals((0L..2L).map { it.toString() }, homeItems(items, emptyMap(), today).map { it.id })
    }

    @Test
    fun exactDeadlineExpiresAtItsInstantAcrossOffsetsAndJapaneseLocalTimes() {
        val values = listOf("2026-09-18T06:00:00Z", "2026-09-18T15:00:00+09:00", "2026-09-18T15:00:00")
        values.forEach { value ->
            val item = action("at", value)
            assertEquals(listOf(item), homeItems(listOf(item), emptyMap(), now.minusNanos(1)))
            assertEquals(emptyList<HomeItem>(), homeItems(listOf(item), emptyMap(), now))
            assertEquals(emptyList<HomeItem>(), homeItems(listOf(item), emptyMap(), now.plusNanos(1)))
        }
    }

    @Test
    fun dateOnlyRemainsVisibleUntilNextJapaneseMidnight() {
        val item = action("date", "2026-09-18")
        val midnight = Instant.parse("2026-09-18T15:00:00Z")
        assertEquals(listOf(item), homeItems(listOf(item), emptyMap(), midnight.minusNanos(1)))
        assertEquals(emptyList<HomeItem>(), homeItems(listOf(item), emptyMap(), midnight))
    }

    @Test
    fun missingMalformedAndRelativeDeadlinesNeverInferExpiration() {
        val values = listOf(null, "", "調整中", "2026-02-30", "本日15時", "not-a-date")
        val items =
            values.mapIndexed { index, due ->
                action(index.toString(), due).copy(overdue = true, updated_at = "2001-01-01T00:00:00Z")
            }
        assertEquals(items.map { it.copy(overdue = false) }.toSet(), homeItems(items, emptyMap(), now).toSet())
        assertEquals(true, items.all { it.overdue })
    }

    @Test
    fun staleServerFlagsDoNotKeepExpiredActionsOrMislabelFutureActions() {
        val stale = action("old", "2026-09-17").copy(updated_at = "2099-01-01T00:00:00Z")
        val future = action("future", "2026-09-19").copy(overdue = true)
        assertEquals(listOf(future.copy(overdue = false)), homeItems(listOf(stale, future), emptyMap(), now))
    }

    @Test
    fun otherCategoriesAndResultReadSemanticsAreUnchanged() {
        val old = result().copy(deadline = "2020-01-01")
        val resolved = old.copy(id = "resolved", category = "resolved")
        assertEquals(listOf(old, resolved), homeItems(listOf(old, resolved), emptyMap(), now))
        assertEquals(listOf(resolved), homeItems(listOf(old, resolved), mapOf("career:reply" to "one"), now))
    }

    @Test
    fun expiryIsDisplayOnlyAndReevaluatesWithoutChangingIdentityOrCompletion() {
        val item = action("exact", "2026-09-18T15:00+09:00")
        val items = mutableListOf(item)
        assertEquals(listOf(item), homeItems(items, emptyMap(), now.minusSeconds(1)))
        assertEquals(emptyList<HomeItem>(), homeItems(items, emptyMap(), now))
        assertEquals(listOf(item), items)
        assertEquals("one", items.single().version)
        assertEquals("action", items.single().category)
    }
}
