package com.m57.hermescontrol

import com.m57.hermescontrol.data.model.BotRosterMeta
import com.m57.hermescontrol.data.model.CanonicalSessionInfo
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.ProfileWorkerSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerBotsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun specialistDrawerBotsExcludesDefaultAndHiddenAndPrioritizesWorkingAndRecent() {
        val hiddenMeta = BotRosterMeta(hidden = true)
        val profiles =
            listOf(
                ProfileInfo(name = "default", is_default = true),
                ProfileInfo(name = "custom_default", is_default = true),
                ProfileInfo(
                    name = "hidden_meta",
                    ui_meta = mapOf("hermes-bots" to json.encodeToJsonElement(hiddenMeta)),
                ),
                ProfileInfo(name = "hidden_local"),
                ProfileInfo(
                    name = "zebra",
                    canonical_session = CanonicalSessionInfo(id = "c1", last_active = 100.0),
                ),
                ProfileInfo(
                    name = "active_bot",
                    canonical_session = CanonicalSessionInfo(id = "c2", last_active = 50.0),
                ),
                ProfileInfo(
                    name = "worker_bot",
                    worker_session = ProfileWorkerSummary(id = "w1", source = "kanban", last_active = 10.0),
                    canonical_session = CanonicalSessionInfo(id = "c3", last_active = 10.0),
                ),
            )

        val result =
            specialistDrawerBots(
                profiles = profiles,
                hiddenProfiles = setOf("hidden_local"),
                activeProfileName = "active_bot",
            )

        assertEquals(
            listOf("worker_bot", "active_bot", "zebra"),
            result.map { it.name },
        )
    }
}
