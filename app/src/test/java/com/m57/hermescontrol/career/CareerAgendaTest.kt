package com.m57.hermescontrol.career

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CareerAgendaTest {
    @Test
    fun homeAgendaHidesPastEntriesWithoutRemovingCompanyRecords() {
        val today = LocalDate.of(2026, 10, 2)
        val entities =
            listOf("offers", "tasks", "deadlines", "events").flatMap { kind ->
                listOf("2026-09-30", "2026-10-02", "2026-10-03").map { date ->
                    CareerEntity(kind, "$kind:$date", 1, buildJsonObject { put("deadline", date) })
                }
            }
        val agenda = careerHomeAgenda(entities, today)
        assertEquals(8, agenda.size)
        assertEquals(listOf("2026-10-02", "2026-10-03"), agenda.map { it.data.text("deadline") }.distinct())
        assertEquals(12, entities.size)
    }

    @Test
    fun homeAgendaUsesJapanDateAndKeepsUnknownDates() {
        val today = LocalDate.of(2026, 10, 2)
        val entities =
            listOf("2026-10-01T14:59:00Z", "2026-10-01T15:00:00Z", "調整中").map { date ->
                CareerEntity("events", date, 1, buildJsonObject { put("scheduledAt", date) })
            } + CareerEntity("tasks", "undated", 1, buildJsonObject {})
        assertEquals(entities.drop(1), careerHomeAgenda(entities, today))
    }

    @Test
    fun homeAgendaStillExcludesCompletedTasksAndReplacedOffers() {
        val entities =
            listOf(
                CareerEntity(
                    "tasks",
                    "done",
                    1,
                    buildJsonObject {
                        put("deadline", "2026-10-02")
                        put("status", "done")
                    },
                ),
                CareerEntity(
                    "offers",
                    "replaced",
                    1,
                    buildJsonObject {
                        put("deadline", "2026-10-03")
                        put("is_current", false)
                    },
                ),
            )
        assertEquals(emptyList<CareerEntity>(), careerHomeAgenda(entities, LocalDate.of(2026, 10, 2)))
    }

    @Test
    fun deadlineOffsetCanCrossIntoTheNextDayInJapan() {
        val date = agendaDate("2026-09-30T23:30:00Z")
        assertEquals(LocalDate.of(2026, 10, 1), date.date)
        assertEquals("08:30", date.time)
    }

    @Test
    fun dateOnlyUnknownAndMalformedDeadlinesNeverInventMidnight() {
        assertNull(agendaDate("2026-09-30").time)
        assertEquals(LocalDate.of(2026, 9, 30), agendaDate("2026-09-30").date)
        assertNull(agendaDate(null).date)
        assertNull(agendaDate("調整中").date)
        assertEquals("調整中", agendaDate("調整中").original)
    }

    @Test
    fun taskResolvesCompanyThroughItsOfferWithoutShowingAnInternalId() {
        val company = CareerEntity("companies", "company", 1, buildJsonObject { put("name", "サンプル商事") })
        val offer = CareerEntity("offers", "offer", 1, buildJsonObject { put("company_id", "company") })
        val task = CareerEntity("tasks", "task", 1, buildJsonObject { put("offer_id", "offer") })
        assertEquals("サンプル商事", agendaCompany(task, listOf(company, offer, task)))
        assertNull(agendaCompany(task, listOf(task)))
    }
}
