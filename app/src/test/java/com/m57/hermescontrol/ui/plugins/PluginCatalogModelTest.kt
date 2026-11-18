package com.m57.hermescontrol.ui.plugins

import com.m57.hermescontrol.data.model.PluginCatalogCapabilities
import com.m57.hermescontrol.data.model.PluginCatalogEntry
import com.m57.hermescontrol.data.model.PluginCatalogResponse
import com.m57.hermescontrol.data.model.PluginInfo
import com.m57.hermescontrol.data.remote.OkHttpProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCatalogModelTest {
    private fun entry(
        name: String,
        tier: String = "official",
        category: String = "tools",
        platforms: List<String> = emptyList(),
        installed: Boolean = false,
        description: String = "",
    ) = PluginCatalogEntry(
        name = name,
        tier = tier,
        category = category,
        platforms = platforms,
        installed = installed,
        description = description,
    )

    @Test
    fun desktopAndOtherOsEntriesDoNotRunOnTheServer() {
        assertFalse(entry("accent", category = "desktop").runsOnServer())
        assertFalse(entry("switcher", platforms = listOf("windows")).runsOnServer())
        assertTrue(entry("cross", platforms = listOf("macos", "Linux")).runsOnServer())
        assertTrue(entry("any").runsOnServer())
    }

    @Test
    fun recommendedFilterKeepsOfficialAndVerifiedOnly() {
        val catalog =
            listOf(
                entry("a", tier = "official"),
                entry("b", tier = "verified"),
                entry("c", tier = "community"),
                entry("d", tier = "official", category = "desktop"),
            )
        assertEquals(
            listOf("a", "b"),
            visibleCatalogEntries(catalog, includeCommunity = false, query = "").map { it.name },
        )
        assertEquals(
            listOf("a", "b", "c"),
            visibleCatalogEntries(catalog, includeCommunity = true, query = "").map { it.name },
        )
    }

    @Test
    fun searchMatchesNameAndDescription() {
        val catalog = listOf(entry("spotify", description = "Control music playback."), entry("snyk"))
        assertEquals(listOf("spotify"), visibleCatalogEntries(catalog, false, "music").map { it.name })
        assertEquals(listOf("snyk"), visibleCatalogEntries(catalog, false, "SNY").map { it.name })
    }

    @Test
    fun unknownCategoriesLandOnTheGeneralShelf() {
        assertEquals("general", entry("x", category = "experimental").shelf())
        assertEquals("memory", entry("y", category = "memory").shelf())
    }

    @Test
    fun hooksAreGroupedByWhatAPersonNotices() {
        val facts =
            PluginCatalogCapabilities(
                providesTools = listOf("a", "b", "a"),
                providesHooks = listOf("post_tool_call", "pre_tool_call", "on_session_end", "pre_llm_call", "custom"),
                requiresEnv = listOf("API_KEY"),
            ).facts()
        assertEquals(2, facts.toolCount)
        assertEquals(
            listOf(HookKind.TOOL_CALLS, HookKind.AI_REPLIES, HookKind.SESSIONS, HookKind.OTHER),
            facts.hookKinds,
        )
        assertTrue(facts.automatic)
        assertEquals(listOf("API_KEY"), facts.requiredEnv)
        assertTrue(PluginCatalogCapabilities().facts().declaresNothing)
    }

    @Test
    fun firstSentenceShortensLongDescriptions() {
        assertEquals("Process hygiene.", firstSentence("Process hygiene. It finds leftovers."))
        assertEquals("v1.2 works", firstSentence("v1.2 works"))
        assertEquals(10, firstSentence("x".repeat(50), maxLength = 10).length)
    }

    @Test
    fun installedPluginsMatchTheirCatalogEntryWithoutGuessing() {
        val catalog =
            listOf(
                entry("hermes-plugin-spotify", installed = true),
                entry("snyk", installed = false),
                entry("memory_wiki", installed = true),
                entry("hermes-memory-wiki", installed = true),
            )
        assertEquals("hermes-plugin-spotify", catalogEntryFor(PluginInfo("spotify"), catalog)?.name)
        assertNull(catalogEntryFor(PluginInfo("snyk"), catalog))
        assertEquals("memory_wiki", catalogEntryFor(PluginInfo("memory_wiki"), catalog)?.name)
        assertNull(catalogEntryFor(PluginInfo("memory-wiki"), catalog))
    }

    @Test
    fun serverCatalogJsonDecodesWithTheAppJsonSettings() {
        val json =
            """
            {"entries":[{"name":"rea","repo":"https://github.com/x/rea","sha":"0123456789abcdef0123456789abcdef01234567",
            "description":"Reverse engineering.","maintainer":"x","tier":"official","category":"tools",
            "requires_hermes":">=0.21","subdir":"","docs_url":"","version":"1.0","image":"","screenshots":[],
            "readme":false,"platforms":[],"title":"REA","onboarding":false,
            "capabilities":{"provides_tools":["open_binary"],"provides_hooks":[],"provides_middleware":[],
            "requires_env":[]},"known_issues":[],"sha_short":"0123456","capability_summary":"...",
            "installed":true,"installed_sha":"0123456789abcdef0123456789abcdef01234567",
            "update_available":false,"runtime_status":"enabled"}],"removed":[],"generated_at":"2026-11-17T00:00:00Z"}
            """.trimIndent()
        val decoded = OkHttpProvider.json.decodeFromString(PluginCatalogResponse.serializer(), json)
        val rea = decoded.entries.single()
        assertEquals("REA", rea.displayName())
        assertEquals(listOf("open_binary"), rea.capabilities.providesTools)
        assertTrue(rea.installed)
        assertEquals("enabled", rea.runtimeStatus)
    }
}
