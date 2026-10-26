package com.m57.hermescontrol.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.AchievementsScreen
import com.m57.hermescontrol.AnalyticsScreen
import com.m57.hermescontrol.BillingScreen
import com.m57.hermescontrol.BotsScreen
import com.m57.hermescontrol.ChannelsScreen
import com.m57.hermescontrol.ConfigScreen
import com.m57.hermescontrol.CronJobsScreen
import com.m57.hermescontrol.FilesScreen
import com.m57.hermescontrol.GatewayScreen
import com.m57.hermescontrol.KanbanScreen
import com.m57.hermescontrol.KeysScreen
import com.m57.hermescontrol.LogsScreen
import com.m57.hermescontrol.McpServersScreen
import com.m57.hermescontrol.MemoryScreen
import com.m57.hermescontrol.ModelScreen
import com.m57.hermescontrol.PairingScreen
import com.m57.hermescontrol.PluginsScreen
import com.m57.hermescontrol.ProcessesScreen
import com.m57.hermescontrol.ProfilesScreen
import com.m57.hermescontrol.ProvidersScreen
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ScreenRegistry
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.SettingsExternalServices
import com.m57.hermescontrol.SettingsPasswords
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.SkillsScreen
import com.m57.hermescontrol.SystemScreen
import com.m57.hermescontrol.ToolsetsScreen
import com.m57.hermescontrol.WebhooksScreen

data class ManagementEntry(
    val key: NavKey,
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
    val icon: ImageVector,
)

data class ManagementSection(
    val id: String,
    @param:StringRes val titleRes: Int,
    val entries: List<ManagementEntry>,
)

/** One purpose-based directory shared by the management screen and its legacy drawer. */
object AppManagement {
    private fun legacy(
        key: NavKey,
        @StringRes description: Int,
    ): ManagementEntry {
        val screen = ScreenRegistry.ALL_SCREENS.single { it.key == key }
        return ManagementEntry(key, screen.labelRes, description, screen.icon)
    }

    val sections: List<ManagementSection> by lazy {
        listOf(
            // The two settings screens people look for most come first instead of hiding among
            // thirty management tools.
            ManagementSection(
                "settings",
                R.string.management_common_settings,
                listOf(
                    ManagementEntry(
                        SettingsExternalServices,
                        R.string.management_external_services,
                        R.string.management_external_services_desc,
                        Icons.Default.Link,
                    ),
                    legacy(SettingsScreen, R.string.management_settings_desc),
                    legacy(ConfigScreen, R.string.management_config_desc),
                    legacy(BotsScreen, R.string.management_bots_desc),
                ),
            ),
            ManagementSection(
                "connection",
                R.string.management_connection,
                listOf(
                    ManagementEntry(
                        SettingsPasswords,
                        R.string.vault_title,
                        R.string.vault_directory_description,
                        Icons.Default.Key,
                    ),
                    ManagementEntry(
                        SettingsConnection,
                        R.string.shell_connection,
                        R.string.management_connection_desc,
                        Icons.Default.Link,
                    ),
                    legacy(ProfilesScreen, R.string.management_profiles_desc),
                    legacy(ProvidersScreen, R.string.management_providers_desc),
                    legacy(KeysScreen, R.string.management_keys_desc),
                    legacy(ChannelsScreen, R.string.management_channels_desc),
                    legacy(PairingScreen, R.string.management_pairing_desc),
                    // Not a chat destination until its relay exists; its status lives here.
                    ManagementEntry(
                        com.m57.hermescontrol.agents
                            .AgentDetailKey(com.m57.hermescontrol.agents.AgentId.BRO.name),
                        R.string.management_bro,
                        R.string.management_bro_desc,
                        Icons.Default.Hub,
                    ),
                ),
            ),
            ManagementSection(
                "automation",
                R.string.management_automation,
                listOf(
                    legacy(CronJobsScreen, R.string.management_cron_desc),
                    legacy(WebhooksScreen, R.string.management_webhooks_desc),
                    legacy(KanbanScreen, R.string.management_kanban_desc),
                ),
            ),
            ManagementSection(
                "agent",
                R.string.management_agent,
                listOf(
                    legacy(ModelScreen, R.string.management_models_desc),
                    legacy(SkillsScreen, R.string.management_skills_desc),
                    legacy(ToolsetsScreen, R.string.management_toolsets_desc),
                    legacy(PluginsScreen, R.string.management_plugins_desc),
                    legacy(McpServersScreen, R.string.management_mcp_desc),
                ),
            ),
            ManagementSection(
                "data",
                R.string.management_data,
                listOf(
                    legacy(MemoryScreen, R.string.management_memory_desc),
                    legacy(FilesScreen, R.string.management_files_desc),
                    legacy(AchievementsScreen, R.string.management_achievements_desc),
                ),
            ),
            ManagementSection(
                "diagnostics",
                R.string.management_diagnostics,
                listOf(
                    ManagementEntry(
                        ActivityScreen,
                        R.string.management_activity,
                        R.string.management_activity_desc,
                        Icons.Default.HistoryEdu,
                    ),
                    legacy(GatewayScreen, R.string.management_gateway_desc),
                    legacy(SystemScreen, R.string.management_system_desc),
                    legacy(LogsScreen, R.string.management_logs_desc),
                    legacy(ProcessesScreen, R.string.management_processes_desc),
                    legacy(AnalyticsScreen, R.string.management_analytics_desc),
                    legacy(BillingScreen, R.string.management_billing_desc),
                ),
            ),
            ManagementSection(
                "developer",
                R.string.management_developer,
                listOf(
                    ManagementEntry(
                        MiniAppExamplesScreen,
                        R.string.management_examples,
                        R.string.management_examples_desc,
                        Icons.Default.Apps,
                    ),
                ),
            ),
        )
    }

    private val roots: Set<NavKey> by lazy { sections.flatMap { it.entries }.map { it.key }.toSet() + AdvancedScreen }

    /** Details inherit management scope from their parent without changing tab ownership. */
    fun contains(stack: List<NavKey>): Boolean =
        stack.any {
            it in roots || it is com.m57.hermescontrol.agents.BotDefaultsKey ||
                it is com.m57.hermescontrol.ExternalServiceDetailKey ||
                it is com.m57.hermescontrol.McpConnectionSettingsKey ||
                it == com.m57.hermescontrol.SettingsGoogleConnection ||
                it == com.m57.hermescontrol.openclaw.OpenClawSettingsKey
        }
}
