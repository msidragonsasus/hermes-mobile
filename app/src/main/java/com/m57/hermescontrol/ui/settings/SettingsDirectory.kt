package com.m57.hermescontrol.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ModelScreen
import com.m57.hermescontrol.PluginsScreen
import com.m57.hermescontrol.R
import com.m57.hermescontrol.SettingsAbout
import com.m57.hermescontrol.SettingsAppearance
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.SettingsExternalServices
import com.m57.hermescontrol.SettingsLimits
import com.m57.hermescontrol.SettingsMemory
import com.m57.hermescontrol.SettingsNotifications
import com.m57.hermescontrol.SettingsPasswords
import com.m57.hermescontrol.SettingsPermissions
import com.m57.hermescontrol.SkillsScreen
import com.m57.hermescontrol.navigation.AdvancedScreen
import com.m57.hermescontrol.navigation.ManagementEntry
import com.m57.hermescontrol.navigation.ManagementSection
import com.m57.hermescontrol.ui.common.NeurologyIcon

/** The daily settings contract, also used by management search and navigation scoping. */
object SettingsDirectory {
    val sections =
        listOf(
            ManagementSection(
                "ai",
                R.string.settings_group_ai,
                listOf(
                    ManagementEntry(
                        ModelScreen,
                        R.string.settings_models,
                        R.string.settings_models_desc,
                        Icons.Outlined.Psychology,
                    ),
                    ManagementEntry(
                        SkillsScreen,
                        R.string.screen_skills,
                        R.string.settings_skills_desc,
                        Icons.Outlined.Inventory2,
                    ),
                    ManagementEntry(
                        PluginsScreen,
                        R.string.screen_plugins,
                        R.string.settings_plugins_desc,
                        Icons.Outlined.Extension,
                    ),
                    ManagementEntry(
                        SettingsMemory,
                        R.string.settings_memory,
                        R.string.settings_memory_desc,
                        NeurologyIcon,
                    ),
                    ManagementEntry(
                        SettingsLimits,
                        R.string.settings_limits,
                        R.string.settings_limits_desc,
                        Icons.Outlined.Speed,
                    ),
                ),
            ),
            ManagementSection(
                "connections",
                R.string.settings_group_connections,
                listOf(
                    ManagementEntry(
                        SettingsExternalServices,
                        R.string.management_external_services,
                        R.string.settings_connectors_desc,
                        Icons.Outlined.Link,
                    ),
                    ManagementEntry(
                        SettingsPasswords,
                        R.string.vault_title,
                        R.string.settings_logins_desc,
                        Icons.Outlined.Key,
                    ),
                    ManagementEntry(
                        SettingsPermissions,
                        R.string.settings_permissions,
                        R.string.settings_permissions_desc,
                        Icons.Outlined.Security,
                    ),
                    ManagementEntry(
                        SettingsConnection,
                        R.string.settings_connection,
                        R.string.management_connection_desc,
                        Icons.Outlined.Dns,
                    ),
                ),
            ),
            ManagementSection(
                "app",
                R.string.settings_group_app,
                listOf(
                    ManagementEntry(
                        SettingsNotifications,
                        R.string.settings_notifications,
                        R.string.settings_notifications_desc,
                        Icons.Outlined.Notifications,
                    ),
                    ManagementEntry(
                        SettingsAppearance,
                        R.string.settings_sec_appearance,
                        R.string.settings_appearance_desc,
                        Icons.Outlined.Palette,
                    ),
                ),
            ),
        )

    val footer =
        listOf(
            ManagementEntry(
                AdvancedScreen,
                R.string.settings_advanced,
                R.string.settings_advanced_desc,
                Icons.Outlined.Tune,
            ),
            ManagementEntry(
                SettingsAbout,
                R.string.settings_sec_about,
                R.string.settings_about_desc,
                Icons.Outlined.Info,
            ),
        )

    val destinations: Set<NavKey> = sections.flatMap { it.entries }.map { it.key }.toSet() + footer.map { it.key }
}
