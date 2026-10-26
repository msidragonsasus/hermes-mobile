package com.m57.hermescontrol.home

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Only compiled native providers can contribute. A manifest alone grants no execution. */
interface HomeContributionProvider {
    val id: String

    suspend fun read(): HomePage
}

/** A date without time remains a date; never manufacture midnight for display. */
fun homeTime(value: String): String =
    runCatching {
        OffsetDateTime
            .parse(value)
            .atZoneSameInstant(ZoneId.of("Asia/Tokyo"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "（日本時間）"
    }.getOrDefault(value)

@Serializable
data class HomeTarget(
    val kind: String,
    val company_id: String? = null,
    val source_id: String? = null,
    val account_id: String? = null,
    val entity_id: String? = null,
    val offer_id: String? = null,
    val section: String? = null,
    val thread_id: String? = null,
    val run_id: String? = null,
    val document_version: Int? = null,
    val session_id: String? = null,
)

@Serializable
data class HomeActionEvidence(
    val field: String,
    val source_id: String,
    val source_version: String,
    val start: Int,
    val end: Int,
    val quote_hash: String = "",
)

@Serializable
data class HomeActionIdentity(
    val key: String,
    val company_id: String,
    val source_id: String,
    val source_version: String,
    val method: String,
    val program: String,
    val cohort: String,
    val round: String,
    val action: String,
    val deadline: String? = null,
    val evidence: List<HomeActionEvidence> = emptyList(),
)

@Serializable
data class HomeItem(
    val id: String,
    val version: String,
    val provider: String,
    val category: String,
    val title: String,
    val target: HomeTarget,
    val detail: String = "",
    val deadline: String? = null,
    val urgent: Boolean = false,
    val overdue: Boolean = false,
    val updated_at: String = "",
    val content_hash: String? = null,
    val action_identity: HomeActionIdentity? = null,
)

@Serializable
data class HomeCoverage(
    val id: String,
    val label: String,
    val state: String,
    val last_success_at: String? = null,
)

@Serializable
data class HomePage(
    val api_version: Int = 1,
    val items: List<HomeItem> = emptyList(),
    val coverage: List<HomeCoverage> = emptyList(),
    val checked_at: String = "",
    val next_cursor: String? = null,
)

fun homeItems(
    items: List<HomeItem>,
    reads: Map<String, String>,
    now: Instant = Instant.now(),
): List<HomeItem> =
    items
        .filter {
            it.category != "action" || homeDeadlineInstant(it.deadline)?.let { due ->
                now.isBefore(due)
            } != false
        }.map { if (it.category == "action" && it.overdue) it.copy(overdue = false) else it }
        .filter {
            it.category != "result" || (
                reads[it.provider + ":" + it.id] != it.version &&
                    (it.content_hash == null || reads[it.provider + ":" + it.id] != "content:" + it.content_hash)
            )
        }.sortedWith(
            compareBy<HomeItem> { it.category != "action" }
                .thenBy { !(it.category == "action" && it.deadline == null && it.target.kind == "conversation") }
                .thenBy { homeDeadlineInstant(it.deadline) ?: Instant.MAX }
                .thenByDescending { it.updated_at },
        )

/** A fixed date is a deterministic Japanese start-of-day clock for callers/tests. */
fun homeItems(
    items: List<HomeItem>,
    reads: Map<String, String>,
    today: LocalDate,
): List<HomeItem> = homeItems(items, reads, today.atStartOfDay(ZoneId.of("Asia/Tokyo")).toInstant())

/** Date-only deadlines expire at the following Japanese midnight; malformed dates stay unknown. */
internal fun homeDeadlineInstant(value: String?): Instant? {
    if (value == null) return null
    val japan = ZoneId.of("Asia/Tokyo")
    return runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value).atZone(japan).toInstant() }.getOrNull()
        ?: runCatching {
            LocalDate
                .parse(value)
                .plusDays(1)
                .atStartOfDay(japan)
                .toInstant()
        }.getOrNull()
}
