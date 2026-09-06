package com.m57.hermescontrol.navigation

import com.m57.hermescontrol.AuthLoginScreen
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.GroupChatKey
import com.m57.hermescontrol.HistoryScreen
import com.m57.hermescontrol.LandingScreen
import com.m57.hermescontrol.MemoryProviderDetailKey
import com.m57.hermescontrol.ScreenRegistry
import com.m57.hermescontrol.SettingsAbout
import com.m57.hermescontrol.SettingsAppearance
import com.m57.hermescontrol.SettingsBehavior
import com.m57.hermescontrol.SettingsChat
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.SettingsLanguage
import com.m57.hermescontrol.ToolsetDetailKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ContributionRegistryTest {
    private fun contribution(
        id: String,
        vararg destinations: DestinationMetadata,
    ) = object : AppContribution {
        override val id = id
        override val destinations = destinations.toList()
    }

    private val chat = DestinationMetadata("chat", "app/chat", ChatScreen::class, 0)
    private val history = DestinationMetadata("history", "app/history", HistoryScreen::class, 1)

    @Test
    fun preservesAllLegacyDefinitionsByIdentityAndOrder() {
        val original = ScreenRegistry.LEGACY_SCREENS
        val wrapped = ScreenRegistry.ALL_SCREENS
        assertEquals(27, wrapped.size)
        original.zip(wrapped).forEach { (before, after) -> assertSame(before, after) }
        assertEquals(
            listOf(
                "chat",
                "history",
                "profiles",
                "bots",
                "cron-jobs",
                "webhooks",
                "kanban",
                "skills",
                "toolsets",
                "plugins",
                "config",
                "mcp-servers",
                "memory",
                "model",
                "pairing",
                "keys",
                "channels",
                "providers",
                "gateway",
                "system",
                "logs",
                "processes",
                "analytics",
                "billing",
                "files",
                "achievements",
                "settings",
            ).map { "hermes/$it" },
            ScreenRegistry.contributions.destinations
                .filter { it.screen != null }
                .map { it.route },
        )
    }

    @Test
    fun reservesEverySpecializedNavigationEntryWithoutReplacingItsContent() {
        val legacy = ScreenRegistry.contributions.destinations.filter { it.route.startsWith("hermes/") }
        val specialized = legacy.filter { it.screen == null }
        assertEquals(
            listOf(
                LandingScreen::class,
                AuthLoginScreen::class,
                SettingsConnection::class,
                SettingsAppearance::class,
                SettingsLanguage::class,
                SettingsChat::class,
                SettingsBehavior::class,
                SettingsAbout::class,
                ToolsetDetailKey::class,
                MemoryProviderDetailKey::class,
                GroupChatKey::class,
            ),
            specialized.map { it.keyType },
        )
        assertEquals(38, legacy.size)
        assertEquals(42, ScreenRegistry.contributions.destinations.size)
        assertSame(specialized.last(), ScreenRegistry.contributions.forRoute("hermes/group-chat"))
        assertSame(specialized.last(), ScreenRegistry.contributions.forKey(GroupChatKey::class))
    }

    @Test
    fun shellAddsThreeRootsAndAdvancedWhileChatKeepsItsLegacyIdentity() {
        val registry = ScreenRegistry.contributions
        listOf(TodayScreen, ActivityScreen, AppsScreen).forEach {
            assertEquals(NavigationRole.ROOT, registry.forKey(it::class)?.role)
        }
        assertEquals(NavigationRole.ADVANCED, registry.forKey(AdvancedScreen::class)?.role)
        assertEquals("hermes/chat", registry.forKey(ChatScreen::class)?.route)
    }

    @Test
    fun rejectsDuplicateContributionDestinationRouteAndKeyType() {
        for (registryInput in listOf(
            listOf(contribution("same", chat), contribution("same", history)),
            listOf(contribution("a", chat), contribution("b", history.copy(id = chat.id))),
            listOf(contribution("a", chat), contribution("b", history.copy(route = chat.route))),
            listOf(contribution("a", chat), contribution("b", history.copy(keyType = chat.keyType))),
            listOf(contribution("a", chat, chat)),
        )) {
            assertThrows(IllegalArgumentException::class.java) { ContributionRegistry(registryInput) }
        }
    }

    @Test
    fun rejectsBlankIdentitiesAndMismatchedScreenType() {
        for (registryInput in listOf(
            listOf(contribution(" ", chat)),
            listOf(contribution("a", chat.copy(id = ""))),
            listOf(contribution("a", chat.copy(route = " "))),
            listOf(contribution("a", history.copy(screen = ScreenRegistry.ALL_SCREENS.first()))),
        )) {
            assertThrows(IllegalArgumentException::class.java) { ContributionRegistry(registryInput) }
        }
    }

    @Test
    fun stableOrderingAcrossContributionsAndUnknownRouteDoNotFallback() {
        val registry = ContributionRegistry(listOf(contribution("b", history.copy(order = 0)), contribution("a", chat)))
        assertEquals(listOf("history", "chat"), registry.destinations.map { it.id })
        val ordered = ContributionRegistry(listOf(contribution("a", history, chat)))
        assertEquals(listOf("chat", "history"), ordered.destinations.map { it.id })
        assertNull(ordered.forRoute("missing"))
        assertNull(ordered.forKey(LandingScreen::class))
        assertFalse(ordered.destinations.isEmpty())
        assertEquals(emptyList<DestinationMetadata>(), ContributionRegistry(emptyList()).destinations)
    }
}
