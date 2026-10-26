package com.m57.hermescontrol.career

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

internal fun careerNoticeDestination(
    route: JsonObject,
    scope: String,
): NavKey {
    val company = requireNotNull(route.text("company_id"))
    require(route.text("scope") == scope)
    return when (route.text("kind")) {
        "settings" -> {
            CareerCompanySettingsKey(company)
        }

        "mail_settings" -> {
            CareerMailSettingsKey
        }

        else -> {
            val notices = route["notices"]?.let { Json.decodeFromJsonElement<List<CareerNotice>>(it) }.orEmpty()
            val id = route.text("notice_id") ?: notices.firstOrNull()?.id
            if (id !=
                null
            ) {
                CareerNoticeDetailKey(company, id, scope, notices)
            } else {
                CareerCompanyKey(company, CareerCompanyTab.OFFERS)
            }
        }
    }
}

internal fun careerNoticeGroups(notices: List<CareerNotice>): Map<String, List<CareerNotice>> =
    notices
        .filter {
            it.eligible && it.company_name.isNotBlank() && !it.label.isNullOrBlank() &&
                it.category in setOf("action", "result", "auth") && it.source_ref != null && it.quote.isNotBlank()
        }.groupBy { it.company_id }

internal fun mergeCareerNotices(
    previous: List<CareerNotice>,
    incoming: List<CareerNotice>,
): List<CareerNotice> =
    (previous + incoming)
        .associateBy { it.source_ref?.key ?: it.id }
        .values
        .filter { it.eligible }
        .sortedBy { it.seq }

internal fun careerNoticeTitle(notices: List<CareerNotice>): String =
    "${notices.last().company_name}｜" + if (notices.size == 1) notices.single().label else "対応・結果 ${notices.size}件"

internal fun careerNoticeBody(notices: List<CareerNotice>): String =
    notices.joinToString("\n\n") {
        listOf(it.title, it.quote, it.deadline_text).filter(String::isNotBlank).distinct().joinToString("\n")
    }
