package com.m57.hermescontrol.home

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
data class TodayTask(
    val id: String,
    val title: String,
    val reason: String,
    val source: String,
    val source_title: String,
    val url: String,
    val priority: Int,
    val deadline: String? = null,
    val done: Boolean = false,
    val stale: Boolean = false,
    val day: String = "",
    val source_checked_at: String = "",
    /**
     * `actionable` | `review` | `dismissed`. Only [TODAY_ACTIONABLE] is today's work. Blank means
     * an older server that classifies nothing, so the task is an unverified candidate, not a duty.
     */
    val actionability: String = "",
    /** Why a task is a candidate (`unclassified`, `expired`, `optional`, …); null when actionable. */
    val review_reason: String? = null,
    /** The user's persisted choice: true = やることに追加, false = 対象外, null = none. */
    val user_interest: Boolean? = null,
)

@Serializable
data class TodayPlan(
    val day: String = "",
    val plan_day: String = "",
    val checked_at: String = "",
    val state: String = "idle",
    val fresh: Boolean = false,
    val tasks: List<TodayTask> = emptyList(),
    val coverage: List<TodayCoverage> = emptyList(),
    /** Not confirmed as the user's own commitment; shown folded as candidates with a count. */
    val review_tasks: List<TodayTask> = emptyList(),
    /** Marked 対象外 by the user; shown folded so the choice stays reversible. */
    val dismissed_tasks: List<TodayTask> = emptyList(),
)

internal const val TODAY_ACTIONABLE = "actionable"
internal const val TODAY_DISMISSED = "dismissed"

/** Reasons meaning nobody has verified whether the item is required (e.g. coursework). */
private val TODAY_UNVERIFIED = setOf("unclassified", "unknown_relevance", "status_unknown")

/** Effective candidate reason, including the older-server fallback for blank classification. */
internal fun TodayTask.reviewReason(): String =
    when {
        actionability == TODAY_DISMISSED || user_interest == false -> "dismissed"
        !review_reason.isNullOrBlank() -> review_reason
        actionability.isBlank() -> "unclassified"
        else -> ""
    }

internal fun todayReviewLabel(reason: String): String =
    when (reason) {
        "unclassified" -> "分類未確認"
        "expired" -> "期限切れ"
        "optional" -> "任意の案内"
        "unknown_relevance" -> "関係するか未確認"
        "interest_unconfirmed" -> "参加・応募の意思が未確認"
        "status_unknown" -> "対応状況が未確認"
        "closed" -> "受付終了"
        "dismissed" -> "対象外"
        else -> "要確認"
    }

/**
 * Whether やることに追加 makes sense: open, not past its deadline, not expired or closed per the
 * server. An expired or closed candidate is never promoted into today's work.
 */
internal fun TodayTask.canAdd(now: Instant): Boolean =
    !done && review_reason !in setOf("expired", "closed") && todayDeadline(deadline)?.isPast(now) != true

@Serializable
data class TodayInterest(
    val interested: Boolean,
)

private val JST: ZoneId = ZoneId.of("Asia/Tokyo")

/** A task deadline as sent by the server: a Japan-time calendar day, an exact instant, or unreadable. */
internal sealed interface TodayDeadline {
    /** Due through the end of [date] in Japan time. */
    data class Day(
        val date: LocalDate,
    ) : TodayDeadline

    data class At(
        val instant: Instant,
    ) : TodayDeadline

    /** Present but unreadable: never treated as expired or as due today. */
    data object Unknown : TodayDeadline
}

/** `null` when there is no deadline. A time without an offset is Japan time, as the plan is. */
internal fun todayDeadline(value: String?): TodayDeadline? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    return runCatching<TodayDeadline> { TodayDeadline.At(OffsetDateTime.parse(text).toInstant()) }
        .recoverCatching { TodayDeadline.At(LocalDateTime.parse(text).atZone(JST).toInstant()) }
        .recoverCatching { TodayDeadline.Day(LocalDate.parse(text)) }
        .getOrDefault(TodayDeadline.Unknown)
}

internal fun TodayDeadline.isPast(now: Instant): Boolean =
    when (this) {
        is TodayDeadline.Day -> now.atZone(JST).toLocalDate().isAfter(date)
        is TodayDeadline.At -> now.isAfter(instant)
        TodayDeadline.Unknown -> false
    }

private fun TodayDeadline?.sortKey(): Instant =
    when (this) {
        is TodayDeadline.Day -> date.plusDays(1).atStartOfDay(JST).toInstant()
        is TodayDeadline.At -> instant
        else -> Instant.MAX
    }

/** `今日 17:00` / `明日` / `11月2日` / `2027年1月5日 09:00` in Japan time; never raw ISO text. */
internal fun TodayDeadline.label(now: Instant): String {
    val today = now.atZone(JST).toLocalDate()

    fun day(date: LocalDate) =
        when (date) {
            today -> "今日"
            today.plusDays(1) -> "明日"
            today.minusDays(1) -> "昨日"
            else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "M月d日" else "yyyy年M月d日"))
        }
    return when (this) {
        is TodayDeadline.Day -> day(date)
        is TodayDeadline.At -> instant.atZone(JST).let { day(it.toLocalDate()) + " " + it.format(CLOCK) }
        TodayDeadline.Unknown -> "不明"
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN)

private fun TodayTask.dueKey() = todayDeadline(deadline).sortKey()

internal data class TodayGroups(
    /** Server-confirmed, open, not past due: the actual list for today. */
    val today: List<TodayTask>,
    /** Server-confirmed and past due. Kept visible, never truncated: it may still matter. */
    val overdue: List<TodayTask>,
    /** Candidates (review or unclassified), expired ones last. */
    val review: List<TodayTask>,
    /** Marked 対象外 by the user. */
    val excluded: List<TodayTask>,
    val done: List<TodayTask>,
    /** Candidates whose need or status nobody has verified; disclosed so empty ≠ nothing due. */
    val unverified: Int,
    /** The server sent unclassified rows (older version): everything is shown as a candidate. */
    val legacy: Boolean,
) {
    val actionable get() = today + overdue
}

internal fun TodayPlan.groups(now: Instant): TodayGroups {
    // One row per task ID; the first list wins (tasks, then review, then dismissed).
    val all = (tasks + review_tasks + dismissed_tasks).distinctBy { it.id }
    val dismissedIds = dismissed_tasks.mapTo(HashSet()) { it.id }
    val (excluded, kept) = all.partition { it.id in dismissedIds || it.reviewReason() == "dismissed" }
    val (done, open) = kept.partition { it.done }
    val (actionable, candidates) = open.partition { it.actionability == TODAY_ACTIONABLE }
    val (overdue, current) = actionable.partition { todayDeadline(it.deadline)?.isPast(now) == true }
    val byDue = compareBy<TodayTask>({ todayDeadline(it.deadline)?.isPast(now) == true }, { it.dueKey() }, { it.id })
    return TodayGroups(
        today = current.sortedWith(compareBy<TodayTask>({ it.priority }, { it.dueKey() }, { it.id })),
        // Most recently missed first.
        overdue = overdue.sortedWith(compareByDescending<TodayTask> { it.dueKey() }.thenBy { it.id }),
        review = candidates.sortedWith(byDue),
        excluded = excluded.sortedWith(byDue),
        done = done,
        unverified = candidates.count { it.reviewReason() in TODAY_UNVERIFIED },
        legacy = all.any { it.actionability.isBlank() },
    )
}

/**
 * Status first, then deadline and source. Only server-confirmed open work can read as 今日必須;
 * an expired task says 期限切れ and a candidate says why it is one.
 */
internal fun todayTaskMeta(
    task: TodayTask,
    stale: Boolean,
    now: Instant,
    candidate: Boolean = task.actionability != TODAY_ACTIONABLE,
): String {
    val deadline = todayDeadline(task.deadline)
    val expired = deadline?.isPast(now) == true
    return buildList {
        when {
            task.done -> {
                deadline?.let { add("期限 " + it.label(now)) }
            }

            candidate -> {
                val reason = task.reviewReason()
                // An expired deadline already says why; do not repeat 期限切れ.
                if (!(expired && reason == "expired")) add(todayReviewLabel(reason))
                deadline?.let { add(deadlineText(it, expired, now)) }
            }

            expired -> {
                add(deadlineText(deadline, true, now))
            }

            else -> {
                add(
                    when (task.priority) {
                        1 -> "今日必須"
                        2 -> "今日準備"
                        else -> "要確認"
                    },
                )
                deadline?.let { add(deadlineText(it, false, now)) }
            }
        }
        add(if (task.source == "gmail") "メール" else "manaba")
        if (stale) add("前回の情報")
    }.joinToString(" · ")
}

private fun deadlineText(
    deadline: TodayDeadline,
    expired: Boolean,
    now: Instant,
) = when {
    expired -> "期限切れ（${deadline.label(now)}）"
    deadline == TodayDeadline.Unknown -> "期限不明"
    else -> "期限 " + deadline.label(now)
}

@Serializable
data class TodayCoverage(
    val id: String,
    val label: String,
    val state: String,
    val checked_at: String = "",
)

@Serializable
data class TodaySettings(
    val email: String = "",
    val manaba_url: String = "",
    val enabled: Boolean = false,
)

@Serializable
data class TodayRefresh(
    val request_id: String,
    val automatic: Boolean = false,
)

@Serializable
data class TodayCompletion(
    val done: Boolean,
)

internal fun TodayPlan.isCurrent(now: Instant = Instant.now()): Boolean =
    fresh && plan_day == now.atZone(ZoneId.of("Asia/Tokyo")).toLocalDate().toString()

internal fun todayCoverageLabel(state: String): String =
    when (state) {
        "updated" -> "確認済み"
        "checked" -> "検索・閲覧した範囲を確認済み"
        "partial" -> "一部確認・未確認の情報があります"
        "auth_required" -> "ログインが必要"
        "not_configured" -> "未設定"
        "setup_required" -> "接続の準備が必要"
        "failed" -> "確認できませんでした"
        "stale" -> "前回の情報"
        "cached" -> "キャッシュを使用・最新の状況は未確認"
        else -> "未確認"
    }
