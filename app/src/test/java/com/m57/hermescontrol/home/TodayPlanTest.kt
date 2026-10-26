package com.m57.hermescontrol.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TodayPlanTest {
    @Test
    fun yesterdayIsNeverCurrentAfterJapaneseMidnightEvenWhenServerSaysFresh() {
        val plan = TodayPlan(plan_day = "2026-10-23", fresh = true)
        assertTrue(plan.isCurrent(Instant.parse("2026-10-23T14:59:59Z")))
        assertFalse(plan.isCurrent(Instant.parse("2026-10-23T15:00:00Z")))
        assertFalse(plan.copy(fresh = false).isCurrent(Instant.parse("2026-10-23T14:59:59Z")))
    }
}
