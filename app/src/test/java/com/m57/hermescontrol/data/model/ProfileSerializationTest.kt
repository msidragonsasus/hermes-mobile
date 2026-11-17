package com.m57.hermescontrol.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testBotRosterMetaSerialization() {
        val meta =
            BotRosterMeta(
                title = "Literature Scout",
                description = "Scrapes arXiv and summarizes papers",
                avatar =
                    BotAvatarMeta(
                        shape = "circle",
                        color = "#4A90E2",
                        icon = "science",
                    ),
                hidden = false,
                groups = listOf("research", "ai-lab"),
                group = "research",
                created = 1718000000.0,
            )

        val encoded = json.encodeToString(meta)
        val decoded = json.decodeFromString<BotRosterMeta>(encoded)

        assertEquals("Literature Scout", decoded.title)
        assertEquals("Scrapes arXiv and summarizes papers", decoded.description)
        assertEquals("circle", decoded.avatar?.shape)
        assertEquals("#4A90E2", decoded.avatar?.color)
        assertEquals("science", decoded.avatar?.icon)
        assertEquals(false, decoded.hidden)
        assertEquals(listOf("research", "ai-lab"), decoded.groups)
        assertEquals("research", decoded.group)
        assertEquals(1718000000.0, decoded.created)
    }

    @Test
    fun testProfileInfoWithBotMetaExtraction() {
        val botMeta =
            BotRosterMeta(
                title = "Custom Bot Title",
                description = "Custom Bot Description",
                avatar = BotAvatarMeta(shape = "hexagon", color = "#E91E63", icon = "robot"),
                hidden = false,
            )

        val profile =
            ProfileInfo(
                name = "custom_bot",
                display_name = "Original Name",
                description = "Original Description",
                ui_meta = mapOf("hermes-bots" to json.encodeToJsonElement(botMeta)),
                canonical_session =
                    CanonicalSessionInfo(
                        id = "sess-123",
                        last_active = 1720000000.0,
                    ),
                worker_session =
                    ProfileWorkerSummary(
                        id = "worker-456",
                        source = "kanban",
                        title = "Task #42",
                        last_active = 1720000100.0,
                    ),
            )

        val extractedMeta = profile.botMeta(json)
        assertNotNull(extractedMeta)
        assertEquals("Custom Bot Title", extractedMeta?.title)
        assertEquals("Custom Bot Title", profile.effectiveTitle)
        assertEquals("Custom Bot Description", profile.effectiveDescription)
        assertFalse(profile.isHidden)
        assertEquals("sess-123", profile.canonical_session?.id)
        assertEquals("worker-456", profile.worker_session?.id)
    }

    @Test
    fun testProfileInfoWithoutBotMeta_fallsBackToProfileFields() {
        val profile =
            ProfileInfo(
                name = "regular_profile",
                display_name = "My Profile",
                description = "Just a profile",
            )

        assertNull(profile.botMeta(json))
        assertEquals("My Profile", profile.effectiveTitle)
        assertEquals("Just a profile", profile.effectiveDescription)
        assertFalse(profile.isHidden)
    }

    @Test
    fun testProfileInfoHidden_derivedFromBotMeta() {
        val hiddenBot =
            ProfileInfo(
                name = "hidden_bot",
                ui_meta =
                    mapOf(
                        "hermes-bots" to
                            json.encodeToJsonElement(
                                BotRosterMeta(title = "Hidden", hidden = true),
                            ),
                    ),
            )

        val visibleBot =
            ProfileInfo(
                name = "visible_bot",
                ui_meta =
                    mapOf(
                        "hermes-bots" to
                            json.encodeToJsonElement(
                                BotRosterMeta(title = "Visible", hidden = false),
                            ),
                    ),
            )

        assertTrue(hiddenBot.isHidden)
        assertFalse(visibleBot.isHidden)
    }

    @Test
    fun testProfileInfoDefault_fallbackNames() {
        val profile = ProfileInfo(name = "default")

        assertNull(profile.botMeta(json))
        assertEquals("default", profile.effectiveTitle)
        assertEquals("", profile.effectiveDescription)
        assertFalse(profile.isHidden)
    }
}
