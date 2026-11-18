package com.m57.hermescontrol.ui.settings

import com.m57.hermescontrol.ModelScreen
import com.m57.hermescontrol.PluginsScreen
import com.m57.hermescontrol.ScreenRegistry
import com.m57.hermescontrol.SettingsAppearance
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.SettingsExternalServices
import com.m57.hermescontrol.SettingsLimits
import com.m57.hermescontrol.SettingsMemory
import com.m57.hermescontrol.SettingsNotifications
import com.m57.hermescontrol.SettingsPasswords
import com.m57.hermescontrol.SettingsPermissions
import com.m57.hermescontrol.SkillsScreen
import com.m57.hermescontrol.navigation.AppManagement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDirectoryTest {
    @Test
    fun dailyDirectoryKeepsExactlyTheAgreedDestinationsOnce() {
        val entries = SettingsDirectory.sections.flatMap { it.entries }
        assertEquals(11, entries.size)
        assertEquals(
            setOf(
                ModelScreen,
                SkillsScreen,
                PluginsScreen,
                SettingsMemory,
                SettingsLimits,
                SettingsExternalServices,
                SettingsPasswords,
                SettingsPermissions,
                SettingsConnection,
                SettingsNotifications,
                SettingsAppearance,
            ),
            entries.map { it.key }.toSet(),
        )
    }

    @Test
    fun directlyOpenedDailyPagesAlsoKeepManagementNavigationScope() {
        SettingsDirectory.destinations.forEach { key ->
            assertTrue("$key", AppManagement.contains(listOf(key)))
            assertTrue("$key must have a stable route", ScreenRegistry.contributions.forKey(key::class) != null)
        }
        listOf(
            com.m57.hermescontrol.SettingsLanguage,
            com.m57.hermescontrol.SettingsChat,
            com.m57.hermescontrol.SettingsBehavior,
            com.m57.hermescontrol.ConfigAdvancedScreen,
        ).forEach { key -> assertTrue("$key", AppManagement.contains(listOf(key))) }
    }
}
