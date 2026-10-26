package com.m57.hermescontrol.ui.config

import com.m57.hermescontrol.data.model.ConfigSchemaResponse
import com.m57.hermescontrol.data.model.SchemaField
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSettingsCatalogTest {
    private fun setting(id: String) = requireNotNull(AgentSettingsCatalog.byId(id))

    private fun schema(vararg keys: String) =
        ConfigSchemaResponse(fields = keys.associateWith { SchemaField(type = "string") }, category_order = emptyList())

    @Test
    fun catalogIdsAndOptionLabelsAreUnique() {
        val ids = AgentSettingsCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        AgentSettingsCatalog.all.forEach { s ->
            assertEquals(
                s.id,
                s.options.size,
                s.options
                    .map { it.label }
                    .toSet()
                    .size,
            )
        }
    }

    @Test
    fun choiceMapsToTheConfigValueTheServerUses() {
        val approvals = setting("approvals")
        val values = mapOf("approvals.mode" to JsonPrimitive("smart"))
        assertEquals("AIが判断して必要なときだけ", approvals.currentOption(null, values, emptyMap())?.label)
        assertEquals(
            mapOf("approvals.mode" to JsonPrimitive("off")),
            approvals.options.single { it.label == "確認しない" }.values,
        )
        assertTrue(approvals.options.single { it.label == "確認しない" }.confirmMessage != null)
    }

    @Test
    fun numbersMatchRegardlessOfIntegerOrDecimalForm() {
        val timeout = setting("terminal_timeout")
        val values = mapOf("terminal.timeout" to JsonPrimitive(180.0))
        assertEquals("3分", timeout.currentOption(null, values, emptyMap())?.label)
    }

    @Test
    fun nullMaxTurnsReadsAsNoLimit() {
        val maxTurns = setting("max_turns")
        assertEquals("制限なし", maxTurns.currentOption(null, mapOf("agent.max_turns" to JsonNull), emptyMap())?.label)
        assertNull(maxTurns.currentOption(null, mapOf("agent.max_turns" to JsonPrimitive(42)), emptyMap()))
        assertEquals(
            "カスタム（詳細設定で変更されています）",
            maxTurns.summary(null, mapOf("agent.max_turns" to JsonPrimitive(42)), emptyMap()),
        )
    }

    @Test
    fun compositeSessionResetRoundTripsThroughSeveralKeys() {
        val reset = setting("session_reset")
        // Absent from config and DEFAULT_CONFIG: the server treats it as "none".
        assertEquals("しない", reset.currentOption(null, emptyMap(), emptyMap())?.label)
        val idleDay = reset.options.single { it.label == "1日放置したら" }
        val saved =
            mapOf(
                "session_reset.mode" to JsonPrimitive("idle"),
                "session_reset.idle_minutes" to JsonPrimitive(1440),
            )
        assertEquals(saved, idleDay.values)
        assertEquals(idleDay, reset.currentOption(null, saved, emptyMap()))
        assertEquals(
            mapOf(
                "session_reset" to
                    JsonObject(mapOf("mode" to JsonPrimitive("idle"), "idle_minutes" to JsonPrimitive(1440))),
            ),
            buildChangeset(idleDay.values),
        )
        // "both" has no friendly equivalent: shown as custom, never silently rewritten.
        assertNull(reset.currentOption(null, mapOf("session_reset.mode" to JsonPrimitive("both")), emptyMap()))
    }

    @Test
    fun settingsTheServerDoesNotKnowAreHidden() {
        val approvals = setting("approvals")
        assertFalse(approvals.isAvailable(schema("display.show_reasoning"), emptyMap()))
        assertTrue(approvals.isAvailable(schema("approvals.mode"), emptyMap()))
        assertTrue(approvals.isAvailable(null, mapOf("approvals.mode" to JsonPrimitive("manual"))))
        assertTrue(setting("session_reset").isAvailable(schema(), emptyMap()))
    }

    @Test
    fun timezoneOffersEverySchemaOptionWithoutDuplicates() {
        val tz = setting("timezone")
        val schema =
            ConfigSchemaResponse(
                fields =
                    mapOf(
                        "timezone" to SchemaField(type = "select", options = listOf("", "Asia/Tokyo", "Europe/Paris")),
                    ),
                category_order = emptyList(),
            )
        val labels = tz.resolvedOptions(schema).map { it.label }
        assertEquals(listOf("PCの設定に合わせる", "日本（Asia/Tokyo）", "Europe/Paris"), labels)
        assertEquals(
            "Europe/Paris",
            tz.currentOption(schema, mapOf("timezone" to JsonPrimitive("Europe/Paris")), emptyMap())?.label,
        )
    }

    @Test
    fun restoreDefaultWritesOnlyKeysThatDiffer() {
        val memory = setting("memory")
        val defaults = mapOf("memory.memory_enabled" to JsonPrimitive(true))
        assertNull(memory.defaultWrites(mapOf("memory.memory_enabled" to JsonPrimitive(true)), defaults))
        assertEquals(defaults, memory.defaultWrites(mapOf("memory.memory_enabled" to JsonPrimitive(false)), defaults))
    }

    @Test
    fun searchMatchesPlainWordsAndKeywords() {
        assertTrue(setting("approvals").matches("確認"))
        assertTrue(setting("approvals").matches("YOLO"))
        assertTrue(setting("compression").matches("要約"))
        assertFalse(setting("compression").matches("タイムゾーン"))
    }
}
