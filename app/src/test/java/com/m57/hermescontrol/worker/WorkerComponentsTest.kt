package com.m57.hermescontrol.worker

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WorkerComponentsTest {
    private val now = ZonedDateTime.of(2026, 11, 16, 9, 0, 0, 0, ZoneId.of("Asia/Tokyo"))
    private val tomorrow: (String) -> String = { "明日 $it" }

    @Test
    fun todayShowsClockOnly() {
        assertEquals("13:05", workerClockLabel("2026-11-16T04:05:00+00:00", now, tomorrow))
    }

    @Test
    fun tomorrowIsPrefixedInTokyoTime() {
        // 19:30 UTC on the 16th is 04:30 JST on the 17th.
        assertEquals("明日 04:30", workerClockLabel("2026-11-16T19:30:00Z", now, tomorrow))
    }

    @Test
    fun unreadableValuesNeverLeakRawText() {
        assertNull(workerClockLabel("soon", now, tomorrow))
        assertNull(workerClockLabel("", now, tomorrow))
        assertNull(workerClockLabel(null, now, tomorrow))
    }

    @Test
    fun upcomingOrderPutsNextRunFirstAndPausedLast() {
        fun job(
            name: String,
            enabled: Boolean,
            next: String?,
        ) = WorkerScheduledJob(name, name, "0 7 * * *", enabled, next, null)

        val ordered =
            upcomingOrder(
                listOf(
                    job("paused", false, "2026-11-16T00:00:00Z"),
                    job("later", true, "2026-11-17T00:00:00Z"),
                    job("unknown", true, null),
                    job("sooner", true, "2026-11-16T05:00:00Z"),
                ),
            ).map { it.name }

        assertEquals(listOf("sooner", "later", "unknown", "paused"), ordered)
    }

    @Test
    fun liveWsJsonResultsAreParsed() {
        // The live client hands parsers a JsonElement with the server's job_id/next_run_at keys.
        val raw =
            Json.parseToJsonElement(
                """{"success":true,"count":1,"jobs":[{"job_id":"f3340f6f7895","name":"記憶の整理",""" +
                    """"schedule":"30 4 * * *","next_run_at":"2026-11-17T04:30:00+09:00","enabled":true}]}""",
            )
        val jobs = WorkerWsParsers.parseCronManageJobs(raw)
        assertEquals(1, jobs.size)
        assertEquals("f3340f6f7895", jobs[0].id)
        assertEquals("2026-11-17T04:30:00+09:00", jobs[0].nextRunAt)

        val subagents =
            WorkerWsParsers.parseSubagents(
                Json.parseToJsonElement("""{"subagents":[{"subagent_id":"s1","status":"running","goal":"g"}]}"""),
            )
        assertEquals(listOf("s1"), subagents.map { it.subagentId })
    }
}
