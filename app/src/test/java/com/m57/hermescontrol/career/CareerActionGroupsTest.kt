package com.m57.hermescontrol.career

import com.m57.hermescontrol.home.HomeActionEvidence
import com.m57.hermescontrol.home.HomeActionIdentity
import com.m57.hermescontrol.home.HomeItem
import com.m57.hermescontrol.home.HomePage
import com.m57.hermescontrol.home.HomeTarget
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CareerActionGroupsTest {
    private fun source(
        id: String,
        program: String = "冬季3days",
        round: String = "一次募集",
        action: String = "submit_es",
        due: String = "2026-09-30T23:59+09:00",
    ): HomeItem {
        val proof =
            listOf("program", "cohort", "round", "action", "deadline").map {
                HomeActionEvidence(it, id, "v1", 0, 2, "synthetic-quote-hash")
            }
        return HomeItem(
            id,
            "v1",
            "career",
            "action",
            "リマインド " + id,
            HomeTarget("mail", company_id = "synthetic-company", source_id = id, account_id = "synthetic-account"),
            deadline = due,
            action_identity =
                HomeActionIdentity(
                    "synthetic-key",
                    "synthetic-company",
                    id,
                    "v1",
                    "model_evidence",
                    program,
                    "2026年度",
                    round,
                    action,
                    due,
                    proof,
                ),
        )
    }

    @Test
    fun fiveRemindersCollapseAsProjectionWithEveryOriginalVersionAndTargetRetained() {
        val originals = (1..5).map { source("mail-" + it) }
        val page = HomePage(items = originals)
        val group = careerActionGroups(careerHomeActions(page, LocalDate.of(2026, 9, 30))).single()
        assertEquals(originals, group.sources)
        assertEquals(originals.first(), group.item)
        assertEquals(originals, page.items)
        // Resolving/removing one original leaves the remaining sources readable.
        assertEquals(originals.drop(1), careerActionGroups(originals.drop(1)).single().sources)
    }

    @Test
    fun sameFormRoundDeadlineAndSeparateSurveyNeverCollapse() {
        val first = source("first")
        val second = source("second", round = "二次募集", due = "2026-10-22T23:59+09:00")
        val survey =
            source("survey", program = "1Day", round = "参加後", action = "answer_survey", due = "2026-08-14T23:59+09:00")
        val sameDayDifferentTask = source("document", action = "submit_document")
        val changedDeadline = source("changed", due = "2026-10-01T23:59+09:00")
        assertEquals(5, careerActionGroups(listOf(first, second, survey, sameDayDifferentTask, changedDeadline)).size)
    }

    @Test
    fun staleMissingCrossSourceOrUnknownClassificationPreservesSeparateRows() {
        val first = source("first")
        val second = source("second")
        val identity = second.action_identity!!
        val variants =
            listOf(
                second.copy(action_identity = null),
                second.copy(action_identity = identity.copy(source_version = "old")),
                second.copy(action_identity = identity.copy(source_id = first.id)),
                second.copy(action_identity = identity.copy(company_id = "other-company")),
                second.copy(action_identity = identity.copy(deadline = "2026-08-14")),
                second.copy(action_identity = identity.copy(round = "")),
                second.copy(action_identity = identity.copy(action = "unknown")),
                second.copy(action_identity = identity.copy(evidence = emptyList())),
                second.copy(target = second.target.copy(kind = "company")),
            )
        variants.forEach { assertEquals(2, careerActionGroups(listOf(first, it)).size) }
    }

    @Test
    fun exactContentFallbackRequiresSameCompanyAndDeadlineDoesNotNormalizeReminderKeywords() {
        val first = source("first").copy(action_identity = null, content_hash = "exact-subject-and-body")
        val second = source("second").copy(action_identity = null, content_hash = first.content_hash)
        assertEquals(1, careerActionGroups(listOf(first, second)).size)
        assertEquals(2, careerActionGroups(listOf(first, second.copy(content_hash = "different-subject-or-body"))).size)
        assertEquals(2, careerActionGroups(listOf(first, second.copy(deadline = "2026-10-22"))).size)
        assertEquals(
            2,
            careerActionGroups(listOf(first, second.copy(target = second.target.copy(company_id = "other")))).size,
        )
    }

    @Test
    fun expiryRunsBeforeGroupingAndRetainsOriginalFeed() {
        val old = source("old", due = "2026-09-29")
        val boundary = source("boundary", due = "2026-10-07")
        val utcBoundary = source("utc", due = "2026-10-06T15:00:01Z")
        val page = HomePage(items = listOf(old, boundary, utcBoundary))
        val groups = careerActionGroups(careerHomeActions(page, LocalDate.of(2026, 10, 7)))
        assertEquals(setOf("boundary", "utc"), groups.flatMap { it.sources }.map { it.id }.toSet())
        assertEquals(listOf(old, boundary, utcBoundary), page.items)
    }

    @Test
    fun oldServerAndCacheJsonWithoutClassificationStillDecode() {
        val json =
            """
            {"id":"old","version":"v1","provider":"career","category":"action",
             "title":"Original","target":{"kind":"mail"}}
            """.trimIndent()
        val item = Json.decodeFromString<HomeItem>(json)
        assertNull(item.action_identity)
        assertEquals(listOf(item), careerActionGroups(listOf(item)).single().sources)
    }
}
