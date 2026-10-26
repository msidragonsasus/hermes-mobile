package com.m57.hermescontrol.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeActionGroupsTest {
    private fun item(
        id: String,
        title: String,
        deadline: String? = null,
    ) = HomeItem(id, "1", "career", "action", title, HomeTarget("thread", thread_id = "t$id"), deadline = deadline)

    @Test
    fun sameTitlesWithoutDeadlineCollapseIntoOneGroup() {
        val groups =
            homeActionGroups(
                listOf(
                    item("1", "相談への回答が必要です"),
                    item("2", "SMBC日興 エントリー開始"),
                    item("3", "相談への回答が必要です"),
                    item("4", "相談への回答が必要です"),
                ),
            )
        assertEquals(listOf(listOf("1", "3", "4"), listOf("2")), groups.map { group -> group.map { it.id } })
    }

    @Test
    fun itemsWithDeadlinesStaySeparate() {
        val groups =
            homeActionGroups(
                listOf(
                    item("1", "書類を提出する", "2026-10-26T12:00:00+09:00"),
                    item("2", "書類を提出する", "2026-10-27T12:00:00+09:00"),
                ),
            )
        assertEquals(2, groups.size)
    }
}
