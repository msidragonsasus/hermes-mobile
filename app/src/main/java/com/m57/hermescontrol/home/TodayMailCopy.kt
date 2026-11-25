package com.m57.hermescontrol.home

import java.time.LocalDate
import java.time.ZoneId

/** Compact display of stored Today copy. Source text, task identity and decisions stay intact. */
internal data class TodayMailCopy(
    val title: String,
    val facts: List<String>,
    val additional: List<String>,
)

private val conditionalMailTitle = Regex("^(.{1,40}?)を希望する場合(だけ|のみ|は)[、,]\\s*(.+)$")
private val genericMailSubjects = setOf("応募", "参加", "申込", "申し込み", "受講", "予約")
private val preparationTime = Regex("^((?:今日|明日|明後日|[0-9０-９]{1,2}月[0-9０-９]{1,2}日)[^、,。]{0,12})に備えて(.+)$")
private val enumeratedReceipt = Regex("^[^。、]+・[^。、]+の(受付記録を確認)(?:する)?$")
private val mailFactBoundary = Regex("[。\\r\\n]+")
private val mailIntro = Regex("^(?:今回(?:再)?取得した)?(?:元の|公式)?(?:メール|案内)(?:によると|では)[、,]\\s*")
private val mailDeadlineWording =
    Regex("期限|締め?切り?|受付終了|までに|まで$|正午|[0-9０-９][：:時日分]|今日中|今夜|明日|明後日|今週中|来週|以内")
private val datedStep = Regex("^([^、。]{1,24}?)は(?=[0-9０-９]{1,2}月[0-9０-９]{1,2}日)")
private val mailCaveat = Regex("場合|不明|未確認|必要|注意|失敗|完了しな")

internal fun TodayTask.mailCopy(): TodayMailCopy {
    val savedTitle = title.trim()
    val conditional = conditionalMailTitle.matchEntire(savedTitle)
    val action = conditional?.groupValues?.get(3) ?: savedTitle
    val preparation = preparationTime.matchEntire(action)
    val subject = conditional?.groupValues?.get(1)?.takeUnless { it in genericMailSubjects }
    val fullAction = preparation?.groupValues?.get(2) ?: action
    val receiptAction =
        enumeratedReceipt
            .matchEntire(fullAction)
            ?.groupValues
            ?.get(1)
            .takeIf { subject != null }
    val shortAction = receiptAction ?: fullAction
    val displayTitle = if (subject == null) shortAction else "$subject $shortAction"

    val sentences =
        reason
            .split(mailFactBoundary)
            .map { it.trim().replaceFirst(mailIntro, "") }
            .map { sentence -> datedStep.replaceFirst(sentence, "$1 ") }
            .filter { it.isNotBlank() }
            .distinct()
    // Never cap date-bearing text. Some saved plans put other steps' deadlines in prose.
    val preparationFact =
        preparation
            ?.groupValues
            ?.get(1)
            ?.takeUnless { repeatsDeadline(it) }
            ?.let { "${it}に備える" }
    val deadlines = sentences.filter { mailDeadlineWording.containsMatchIn(it) }
    val other = sentences.filterNot { it in deadlines || it == displayTitle }
    val condition = conditional?.let { if (it.groupValues[2] == "は") "希望する場合" else "希望する場合のみ" }
    val shortFact =
        other.firstOrNull { it.length <= 48 && mailCaveat.containsMatchIn(it) }
            ?: other.firstOrNull { it.length <= 48 }
    val timedFacts = listOfNotNull(preparationFact) + deadlines
    val extraFactCount = (3 - timedFacts.size).coerceAtLeast(0)
    val facts = (timedFacts + listOfNotNull(condition, shortFact).take(extraFactCount)).distinct()
    // Preserve legacy wording for dates or caveats outside the compact projection. Never show
    // the raw mail subject next to the summary. The original message remains directly accessible.
    val additional =
        buildList {
            if (receiptAction != null) add(fullAction)
            if (displayTitle.length > 42) add(displayTitle)
            if (condition != null && condition !in facts) add(condition)
            addAll(other.filterNot { it in facts })
        }.distinct()
    return TodayMailCopy(displayTitle, facts, additional)
}

private fun TodayTask.repeatsDeadline(time: String): Boolean {
    val planDay = runCatching { LocalDate.parse(day) }.getOrNull() ?: return false
    val date = todayDeadline(deadline)?.takeUnless { it == TodayDeadline.Unknown } ?: return false
    val planStart = planDay.atStartOfDay(ZoneId.of("Asia/Tokyo")).toInstant()
    return time.replace("正午", "12:00").replace(" ", "") == date.label(planStart).replace(" ", "")
}
