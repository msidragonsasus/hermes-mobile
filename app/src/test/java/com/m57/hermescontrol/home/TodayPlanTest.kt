package com.m57.hermescontrol.home

import com.m57.hermescontrol.data.remote.OkHttpProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TodayPlanTest {
    // 2026-10-28 16:30 JST.
    private val now = Instant.parse("2026-10-28T07:30:00Z")
    private val fivePm = Instant.parse("2026-10-28T08:00:00Z")

    private fun task(
        id: String,
        priority: Int = 1,
        deadline: String? = null,
        done: Boolean = false,
        actionability: String = "actionable",
        reason: String? = null,
        interest: Boolean? = null,
    ) = TodayTask(
        id,
        "title $id",
        "reason",
        "gmail",
        "source",
        "https://example.org/$id",
        priority,
        deadline,
        done,
        actionability = actionability,
        review_reason = reason,
        user_interest = interest,
    )

    private fun meta(
        priority: Int = 2,
        deadline: String? = null,
        stale: Boolean = false,
    ) = todayTaskMeta(task("a", priority, deadline), stale, now)

    @Test
    fun yesterdayIsNeverCurrentAfterJapaneseMidnightEvenWhenServerSaysFresh() {
        val plan = TodayPlan(plan_day = "2026-10-23", fresh = true)
        assertTrue(plan.isCurrent(Instant.parse("2026-10-23T14:59:59Z")))
        assertFalse(plan.isCurrent(Instant.parse("2026-10-23T15:00:00Z")))
        assertFalse(plan.copy(fresh = false).isCurrent(Instant.parse("2026-10-23T14:59:59Z")))
    }

    @Test
    fun deadlineParsesPreciseOffsetLessDateOnlyNullAndMalformed() {
        assertEquals(TodayDeadline.At(fivePm), todayDeadline("2026-10-28T17:00:00+09:00"))
        assertEquals(TodayDeadline.At(fivePm), todayDeadline("2026-10-28T08:00Z"))
        // The server accepts a time without an offset; the plan is defined in Japan time.
        assertEquals(TodayDeadline.At(fivePm), todayDeadline("2026-10-28T17:00"))
        assertEquals(TodayDeadline.Day(LocalDate.parse("2026-10-28")), todayDeadline("2026-10-28"))
        assertNull(todayDeadline(null))
        assertNull(todayDeadline(" "))
        assertEquals(TodayDeadline.Unknown, todayDeadline("10月28日"))
        assertEquals(TodayDeadline.Unknown, todayDeadline("2026-02-30"))
    }

    @Test
    fun deadlinePastUsesJapanTimeAndKeepsDateOnlyThroughTheDay() {
        assertFalse(todayDeadline("2026-10-28T17:00:00+09:00")!!.isPast(now))
        assertTrue(todayDeadline("2026-10-28T16:00:00+09:00")!!.isPast(now))
        // 07:00Z is 16:00 JST: already past even though the UTC date reads the same.
        assertTrue(todayDeadline("2026-10-28T07:00:00Z")!!.isPast(now))
        assertFalse(todayDeadline("2026-10-28")!!.isPast(now))
        assertFalse(todayDeadline("2026-10-28")!!.isPast(Instant.parse("2026-10-28T14:59:59Z")))
        assertTrue(todayDeadline("2026-10-28")!!.isPast(Instant.parse("2026-10-28T15:00:00Z")))
        assertTrue(todayDeadline("2026-10-27")!!.isPast(now))
        // An unreadable deadline is neither expired nor due today.
        assertFalse(TodayDeadline.Unknown.isPast(now))
    }

    @Test
    fun expiredTaskIsNeverLabelledAsRequiredToday() {
        val expired = meta(priority = 1, deadline = "2026-10-27T17:00:00+09:00")
        assertFalse(expired, expired.contains("今日必須"))
        assertEquals("期限切れ（昨日 17:00） · メール", expired)
        assertEquals("期限切れ（10月20日） · メール", meta(priority = 1, deadline = "2026-10-20"))
        assertEquals("今日必須 · 期限 今日 17:00 · メール", meta(priority = 1, deadline = "2026-10-28T17:00:00+09:00"))
    }

    @Test
    fun deadlineLabelsAreHumanReadableNeverRawIso() {
        assertEquals("今日準備 · 期限 今日 · メール", meta(deadline = "2026-10-28"))
        assertEquals("今日準備 · 期限 明日 · メール", meta(deadline = "2026-10-29"))
        assertEquals("今日準備 · 期限 11月2日 · メール", meta(deadline = "2026-11-02"))
        assertEquals("今日準備 · 期限 明日 09:00 · メール", meta(deadline = "2026-10-29T09:00"))
        assertEquals("今日準備 · 期限 2027年1月5日 · メール", meta(deadline = "2027-01-05"))
        assertEquals("今日準備 · 期限不明 · メール", meta(deadline = "来週"))
        assertEquals("今日準備 · メール", meta())
        assertEquals("今日準備 · メール · 前回の情報", meta(stale = true))
    }

    @Test
    fun candidateMetaShowsReadableReasonAndNeverRequiredToday() {
        fun candidate(
            reason: String?,
            deadline: String? = null,
            actionability: String = "review",
        ) = todayTaskMeta(task("c", 1, deadline, actionability = actionability, reason = reason), false, now)
        assertEquals("分類未確認 · 期限 明日 · メール", candidate("unclassified", "2026-10-29"))
        assertEquals("任意の案内 · メール", candidate("optional"))
        assertEquals("関係するか未確認 · メール", candidate("unknown_relevance"))
        assertEquals("参加・応募の意思が未確認 · メール", candidate("interest_unconfirmed"))
        assertEquals("対応状況が未確認 · メール", candidate("status_unknown"))
        assertEquals("受付終了 · メール", candidate("closed"))
        // The deadline already says expired; the reason is not repeated.
        assertEquals("期限切れ（10月25日） · メール", candidate("expired", "2026-10-25"))
        assertEquals("分類未確認 · 期限切れ（10月25日 23:59） · メール", candidate("unclassified", "2026-10-25T23:59:00+09:00"))
        // Older server: no classification at all.
        assertEquals("分類未確認 · メール", candidate(null, actionability = ""))
        assertEquals("対象外 · メール", candidate("dismissed", actionability = "dismissed"))
        assertEquals("要確認 · メール", candidate("something_new"))
    }

    @Test
    fun olderServerFortySevenUnclassifiedTasksAreNeverPresentedAsTodaysWork() {
        // The real complaint: 47 blank-classification rows, most with past deadlines, all priority 1.
        val legacy =
            (1..47).map {
                task(
                    "l$it",
                    priority = 1,
                    deadline = if (it % 2 == 0) "2026-10-2${it % 8}T17:00:00+09:00" else "2026-10-29",
                    actionability = "",
                )
            }
        val oldDone = task("old-done", done = true, actionability = "")
        val plan = TodayPlan(plan_day = "2026-10-28", tasks = legacy + oldDone)
        val groups = plan.groups(now)
        assertTrue(groups.today.isEmpty())
        assertTrue(groups.overdue.isEmpty())
        assertEquals(47, groups.review.size)
        assertEquals(47, groups.unverified)
        assertTrue(groups.legacy)
        // A completed legacy row stays restorable instead of disappearing.
        assertEquals(listOf("old-done"), groups.done.map { it.id })
        assertTrue(groups.review.none { todayTaskMeta(it, false, now, candidate = true).contains("今日必須") })
    }

    @Test
    fun groupsFollowServerClassificationAndKeepConfirmedOverdueWorkVisible() {
        val plan =
            TodayPlan(
                tasks =
                    listOf(
                        task("later", priority = 2, deadline = "2026-10-30"),
                        task("now", priority = 1, deadline = "2026-10-28T17:00:00+09:00"),
                        task("missed", priority = 1, deadline = "2026-10-27"),
                        task("missed-older", priority = 1, deadline = "2026-10-20"),
                        task("unreadable", priority = 3, deadline = "soon"),
                        task("done", done = true),
                        task("shared", priority = 3),
                    ),
                review_tasks =
                    listOf(
                        task("expired-ad", deadline = "2026-10-20", actionability = "review", reason = "expired"),
                        task("candidate", priority = 1, actionability = "review", reason = "unclassified"),
                        task("ad", priority = 1, actionability = "review", reason = "optional"),
                        task("review-done", done = true, actionability = "review", reason = "unclassified"),
                        // The same ID in two lists is shown once, as its first (actionable) entry.
                        task("shared", actionability = "review", reason = "optional"),
                    ),
                dismissed_tasks =
                    listOf(task("no", actionability = "dismissed", reason = "dismissed", interest = false)),
            )
        val groups = plan.groups(now)
        assertEquals(listOf("now", "later", "shared", "unreadable"), groups.today.map { it.id })
        // Confirmed obligations stay listed after their deadline, newest first.
        assertEquals(listOf("missed", "missed-older"), groups.overdue.map { it.id })
        assertEquals(listOf("ad", "candidate", "expired-ad"), groups.review.map { it.id })
        assertEquals(listOf("no"), groups.excluded.map { it.id })
        assertEquals(listOf("done", "review-done"), groups.done.map { it.id })
        assertEquals(1, groups.unverified)
        assertFalse(groups.legacy)
    }

    @Test
    fun addToTodayIsOfferedOnlyForOpenNotExpiredNotClosedItems() {
        assertTrue(task("f", deadline = "2026-10-29", actionability = "review", reason = "optional").canAdd(now))
        assertTrue(task("n", actionability = "", reason = null).canAdd(now))
        assertFalse(task("e", deadline = "2026-10-27", actionability = "", reason = null).canAdd(now))
        assertFalse(task("x", actionability = "review", reason = "expired").canAdd(now))
        assertFalse(task("c", actionability = "review", reason = "closed").canAdd(now))
        assertFalse(task("d", done = true, actionability = "review", reason = "optional").canAdd(now))
        assertTrue(task("z", actionability = "dismissed", reason = "dismissed", interest = false).canAdd(now))
    }

    @Test
    fun wireJsonAcceptsLegacyAndServerSnapshotFields() {
        val json = OkHttpProvider.json
        val legacy =
            json.decodeFromString<TodayPlan>(
                """{"plan_day":"2026-10-28","tasks":[{"id":"a","title":"t","reason":"r","source":"manaba",""" +
                    """"source_title":"s","url":"https://example.org","priority":1,"deadline":null,"done":false}]}""",
            )
        assertEquals("", legacy.tasks.single().actionability)
        assertNull(legacy.tasks.single().review_reason)
        assertEquals(emptyList<TodayTask>(), legacy.review_tasks)
        val common = """"title":"t","reason":"r","source":"gmail","source_title":"s","url":"https://example.org""""
        val snapshot =
            json.decodeFromString<TodayPlan>(
                """{"day":"2026-10-28","plan_day":"2026-10-28","checked_at":"","state":"complete","fresh":true,""" +
                    """"tasks":[{"id":"a",$common,"priority":2,"deadline":"2026-10-29","done":true,""" +
                    """"task_key":"k","source_id":"m","resolved":false,"eligibility":{"kind":"coursework"},""" +
                    """"source_checked_at":"2026-10-28T09:00:00+09:00","actionability":"actionable",""" +
                    """"review_reason":null,"user_interest":null}],""" +
                    """"review_tasks":[{"id":"b",$common,"priority":3,"actionability":"review",""" +
                    """"review_reason":"interest_unconfirmed","user_interest":null}],""" +
                    """"dismissed_tasks":[{"id":"c",$common,"priority":1,"actionability":"dismissed",""" +
                    """"review_reason":"dismissed","user_interest":false}],""" +
                    """"coverage":[],"resolved_tasks":[]}""",
            )
        assertEquals("actionable", snapshot.tasks.single().actionability)
        assertEquals("2026-10-28T09:00:00+09:00", snapshot.tasks.single().source_checked_at)
        assertEquals("interest_unconfirmed", snapshot.review_tasks.single().review_reason)
        assertEquals(false, snapshot.dismissed_tasks.single().user_interest)
    }
}
