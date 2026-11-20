package com.m57.hermescontrol.agents

import com.m57.hermescontrol.data.model.ProfileInfo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class BotDetailDisplayNameTest {
    private val id = "bot-31b01926a7"
    private val fallback = "専門ボット"
    private val profile = ProfileInfo(name = id)

    @Test
    fun rosterTitleMatchesChatAndWinsOverProfileDisplayName() {
        val roster =
            profile.copy(
                display_name = "初期設定名",
                ui_meta = mapOf("hermes-bots" to buildJsonObject { put("title", "調査アシスタント") }),
            )
        assertEquals(BotConversations.label(id, roster), botDetailDisplayName(id, fallback, roster, profile))
        assertEquals("調査アシスタント", botDetailDisplayName(id, fallback, roster, profile))
    }

    @Test
    fun missingOrIdOnlyMetadataUsesNeutralLabel() {
        assertEquals(fallback, botDetailDisplayName(id, fallback, null))
        assertEquals(fallback, botDetailDisplayName(id, fallback, profile.copy(display_name = " ${id.uppercase()} ")))
        assertEquals(fallback, botDetailDisplayName(id, fallback, profile.copy(display_name = "@$id")))
        assertEquals(fallback, botDetailDisplayName(id, fallback, ProfileInfo(name = "other", display_name = "Other")))
    }

    @Test
    fun technicalRosterTitleCanFallBackToSetupDisplayName() {
        val bot =
            profile.copy(
                display_name = "研究アシスタント",
                ui_meta = mapOf("hermes-bots" to buildJsonObject { put("title", id) }),
            )
        assertEquals("研究アシスタント", botDetailDisplayName(id, fallback, bot))
    }
}
