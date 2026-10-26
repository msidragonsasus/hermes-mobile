package com.m57.hermescontrol.career

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CareerNoticePresentationTest {
    private fun notice(
        id: String = "one",
        company: String = "a",
        seq: Long = 1,
    ) = CareerNotice(
        seq,
        id,
        company,
        kind = "source_changed",
        title = "ESの提出",
        created_at = "",
        eligible = true,
        company_name = "企業$company",
        category = "action",
        label = "提出依頼",
        quote = "ESを提出してください",
        deadline_text = "提出期限 2026年10月1日",
        source_ref = CareerNoticeSource("mypage", id),
    )

    @Test
    fun `resolved authentication and no longer actionable sources disappear from displayed group`() {
        val old = notice().copy(source_ref = CareerNoticeSource("settings", "connection", "stable"))
        val resolved = old.copy(id = "resolved", seq = 2, eligible = false)
        assertTrue(mergeCareerNotices(listOf(old), listOf(resolved)).isEmpty())
    }

    @Test
    fun `new version replaces an older version of the same source`() {
        val old = notice().copy(source_ref = CareerNoticeSource("mypage", "old", "stable"))
        val updated = notice("new", seq = 2).copy(source_ref = CareerNoticeSource("mypage", "new", "stable"))
        assertEquals(listOf(updated), mergeCareerNotices(listOf(old), listOf(updated)))
    }

    @Test
    fun `groups actionable notices per company and rejects legacy or incomplete notices`() {
        val one = notice()
        val groups =
            careerNoticeGroups(
                listOf(
                    one,
                    notice("two", seq = 2),
                    notice("other", "b"),
                    one.copy(eligible = false),
                    one.copy(source_ref = null),
                    one.copy(category = null),
                    one.copy(quote = ""),
                ),
            )
        assertEquals(listOf("one", "two"), groups.getValue("a").map { it.id })
        assertEquals(1, groups.getValue("b").size)
    }

    @Test
    fun `retry merges without duplicating old notices and includes all original details`() {
        val notices = mergeCareerNotices(listOf(notice()), listOf(notice(), notice("two", seq = 2)))
        assertEquals(2, notices.size)
        assertEquals("企業a｜対応・結果 2件", careerNoticeTitle(notices))
        assertTrue(careerNoticeBody(notices).contains("ESを提出してください"))
        assertTrue(careerNoticeBody(notices).contains("提出期限 2026年10月1日"))
        assertEquals("企業a｜提出依頼", careerNoticeTitle(listOf(notice())))
    }

    @Test
    fun `old server payload has no OS notification eligibility`() {
        val old =
            Json.decodeFromString<CareerNotice>(
                """{"seq":1,"id":"old","company_id":"a","kind":"source_changed","title":"更新","created_at":""}""",
            )
        assertFalse(old.eligible)
        assertTrue(careerNoticeGroups(listOf(old)).isEmpty())
    }

    private fun route(
        notices: List<CareerNotice>,
        kind: String? = null,
    ) = buildJsonObject {
        put("scope", "phone")
        put("company_id", "a")
        put("notices", Json.encodeToJsonElement(notices))
        if (notices.size == 1) put("notice_id", notices.single().id)
        if (kind != null) put("kind", kind)
    }

    @Test
    fun `single notice opens saved evidence and group opens its company notice list`() {
        val single = careerNoticeDestination(route(listOf(notice())), "phone") as CareerNoticeDetailKey
        assertEquals("one", single.noticeId)
        val grouped = careerNoticeDestination(route(listOf(notice(), notice("two"))), "phone") as CareerNoticeDetailKey
        assertEquals(2, grouped.notices.size)
        assertEquals("a", grouped.companyId)
    }

    @Test
    fun `auth notices open the matching settings`() {
        assertEquals(
            CareerCompanySettingsKey("a"),
            careerNoticeDestination(route(listOf(notice()), "settings"), "phone"),
        )
        assertEquals(CareerMailSettingsKey, careerNoticeDestination(route(listOf(notice()), "mail_settings"), "phone"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `route cannot cross a connection profile scope`() {
        careerNoticeDestination(route(listOf(notice())), "desktop")
    }
}
