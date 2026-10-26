package com.m57.hermescontrol.career

import java.text.BreakIterator
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Compact display text; never changes the stored message. */
internal fun careerThreadPreview(value: String): String =
    value
        .take(4000)
        .replace(Regex("!?(?:\\[([^]\\n]+)]\\([^\\n)]*\\))"), "$1")
        .replace(Regex("(?m)^\\s*(?:#{1,6}|[-*>]|\\d+[.)])\\s+"), "")
        .replace(Regex("[*_`~]+"), "")
        .filterNot { Character.isISOControl(it) && !it.isWhitespace() }
        .replace(Regex("\\s+"), " ")
        .trim()

internal fun careerConversationTitle(message: String): String {
    val plain = careerThreadPreview(message).ifBlank { "新しい相談" }
    if (plain.codePointCount(0, plain.length) <= 36) return plain
    val limit = plain.offsetByCodePoints(0, 35)
    val boundary = BreakIterator.getCharacterInstance(Locale.JAPAN).apply { setText(plain) }
    val end = if (boundary.isBoundary(limit)) limit else boundary.preceding(limit)
    return plain.substring(0, end.coerceAtLeast(0)) + "…"
}

/** One-line kind of conversation: general, or company/offer with whatever names are known. */
internal fun careerThreadScopeLabel(thread: CareerThread): String =
    if (thread.company_id == null && thread.offer_id == null) {
        "全体相談"
    } else {
        listOf("企業の相談")
            .plus(listOfNotNull(thread.company_name, thread.offer_title).filter { it.isNotBlank() })
            .joinToString(" · ")
    }

/** Short list badge; the full explanation stays in [careerRunStatus] inside the conversation. */
internal fun careerThreadRunBadge(runState: String?): String? =
    when (runState) {
        null, "", "completed" -> null
        "queued" -> "待機中"
        "running" -> "実行中"
        "waiting_user" -> "回答待ち"
        "paused" -> "中断中"
        "cancelled" -> "停止済み"
        "failed" -> "失敗"
        else -> careerRunStatus(runState)
    }

internal fun careerThreadUpdatedAt(
    value: String,
    clock: Clock = Clock.systemDefaultZone(),
    zone: ZoneId = clock.zone,
): String {
    if (value.isBlank()) return ""
    val time = runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zone) }.getOrNull() ?: return "更新日時を確認できません"
    val today = clock.instant().atZone(zone).toLocalDate()
    val day = time.toLocalDate()
    val pattern =
        when (day) {
            today -> "'今日' HH:mm"
            today.minusDays(1) -> "'昨日' HH:mm"
            else -> if (day.year == today.year) "M月d日 HH:mm" else "yyyy年M月d日 HH:mm"
        }
    return time.format(DateTimeFormatter.ofPattern(pattern, Locale.JAPAN))
}
