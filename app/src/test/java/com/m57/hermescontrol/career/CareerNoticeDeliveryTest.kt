package com.m57.hermescontrol.career

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CareerNoticeDeliveryTest {
    @Test
    fun `pages filtered by profile still advance and reach the next page`() =
        runBlocking {
            var cursor = 0L
            val scanned = mutableListOf<Long>()
            drainCareerNoticePages(
                readCursor = { cursor },
                fetch = {
                    scanned += it
                    CareerNoticeFeed(cursor = it + 100, more = it == 0L)
                },
                publish = {
                    assertTrue(it.isEmpty())
                    true
                },
                commit = { cursor = it },
            )
            assertEquals(listOf(0L, 100L), scanned)
            assertEquals(200L, cursor)
        }

    @Test
    fun `failed delivery keeps the page cursor and retry uses the same stable identities`() =
        runBlocking {
            val one =
                CareerNotice(
                    1,
                    "one",
                    "a",
                    kind = "source_changed",
                    title = "提出依頼",
                    created_at = "",
                    eligible = true,
                )
            val two = one.copy(seq = 2, id = "two", company_id = "b")
            var cursor = 0L
            val visible = mutableMapOf<String, List<CareerNotice>>()
            var fail = true

            suspend fun run() =
                drainCareerNoticePages(
                    readCursor = { cursor },
                    fetch = { CareerNoticeFeed(listOf(one, two), cursor = 2) },
                    publish = { notices ->
                        for (notice in notices) {
                            if (fail && notice.company_id == "b") throw IOException("synthetic")
                            visible[notice.company_id] =
                                mergeCareerNotices(visible[notice.company_id].orEmpty(), listOf(notice))
                        }
                        true
                    },
                    commit = { cursor = it },
                )
            try {
                run()
                throw AssertionError("delivery should fail")
            } catch (_: IOException) {
                assertEquals(0L, cursor)
            }
            fail = false
            run()
            assertEquals(2L, cursor)
            assertEquals(listOf(one), visible["a"])
            assertEquals(listOf(two), visible["b"])
        }

    @Test
    fun `scope change aborts without consuming undelivered notices`() =
        runBlocking {
            var cursor = 0L
            drainCareerNoticePages(
                readCursor = { cursor },
                fetch = { CareerNoticeFeed(cursor = 100) },
                publish = { false },
                commit = { cursor = it },
            )
            assertEquals(0L, cursor)
        }

    @Test(expected = IllegalStateException::class)
    fun `non advancing pagination is rejected before posting`() =
        runBlocking {
            drainCareerNoticePages(
                readCursor = { 100L },
                fetch = { CareerNoticeFeed(cursor = 100, more = true) },
                publish = { throw AssertionError("must not post malformed page") },
                commit = { throw AssertionError("must not commit malformed page") },
            )
        }
}
