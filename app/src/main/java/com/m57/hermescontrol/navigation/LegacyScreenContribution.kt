package com.m57.hermescontrol.navigation

import com.m57.hermescontrol.AchievementsScreen
import com.m57.hermescontrol.AnalyticsScreen
import com.m57.hermescontrol.AuthLoginScreen
import com.m57.hermescontrol.BillingScreen
import com.m57.hermescontrol.BotsScreen
import com.m57.hermescontrol.ChannelsScreen
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.ConfigAdvancedScreen
import com.m57.hermescontrol.ConfigScreen
import com.m57.hermescontrol.CronJobsScreen
import com.m57.hermescontrol.FilesScreen
import com.m57.hermescontrol.GatewayScreen
import com.m57.hermescontrol.HistoryScreen
import com.m57.hermescontrol.KanbanScreen
import com.m57.hermescontrol.KeysScreen
import com.m57.hermescontrol.LandingScreen
import com.m57.hermescontrol.LogsScreen
import com.m57.hermescontrol.McpServersScreen
import com.m57.hermescontrol.MemoryProviderDetailKey
import com.m57.hermescontrol.MemoryScreen
import com.m57.hermescontrol.ModelScreen
import com.m57.hermescontrol.PairingScreen
import com.m57.hermescontrol.PluginsScreen
import com.m57.hermescontrol.ProcessesScreen
import com.m57.hermescontrol.ProfilesScreen
import com.m57.hermescontrol.ProvidersScreen
import com.m57.hermescontrol.ScreenDefinition
import com.m57.hermescontrol.SettingsAbout
import com.m57.hermescontrol.SettingsAppearance
import com.m57.hermescontrol.SettingsBehavior
import com.m57.hermescontrol.SettingsChat
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.SettingsGoogleConnection
import com.m57.hermescontrol.SettingsLanguage
import com.m57.hermescontrol.SettingsPasswords
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.SkillsScreen
import com.m57.hermescontrol.SystemScreen
import com.m57.hermescontrol.ToolsetDetailKey
import com.m57.hermescontrol.ToolsetsScreen
import com.m57.hermescontrol.WebhooksScreen

/** Wraps the existing definitions; stable routes do not depend on class names or localized labels. */
class LegacyScreenContribution(
    screens: List<ScreenDefinition>,
) : AppContribution {
    override val id = "hermes.legacy"

    override val destinations =
        screens.mapIndexed { index, screen ->
            val route =
                when (screen.key) {
                    ChatScreen -> "chat"
                    HistoryScreen -> "history"
                    ProfilesScreen -> "profiles"
                    BotsScreen -> "bots"
                    CronJobsScreen -> "cron-jobs"
                    WebhooksScreen -> "webhooks"
                    KanbanScreen -> "kanban"
                    SkillsScreen -> "skills"
                    ToolsetsScreen -> "toolsets"
                    PluginsScreen -> "plugins"
                    ConfigScreen -> "config"
                    McpServersScreen -> "mcp-servers"
                    MemoryScreen -> "memory"
                    ModelScreen -> "model"
                    PairingScreen -> "pairing"
                    KeysScreen -> "keys"
                    ChannelsScreen -> "channels"
                    ProvidersScreen -> "providers"
                    GatewayScreen -> "gateway"
                    SystemScreen -> "system"
                    LogsScreen -> "logs"
                    ProcessesScreen -> "processes"
                    AnalyticsScreen -> "analytics"
                    BillingScreen -> "billing"
                    FilesScreen -> "files"
                    AchievementsScreen -> "achievements"
                    SettingsScreen -> "settings"
                    else -> error("Legacy screen has no stable route")
                }
            DestinationMetadata(
                "legacy.$route",
                "hermes/$route",
                screen.key::class,
                index,
                screen,
                role = if (screen.key == ChatScreen) NavigationRole.ROOT else NavigationRole.ADVANCED,
            )
        } +
            listOf(
                DestinationMetadata(
                    "legacy.landing",
                    "hermes/landing",
                    LandingScreen::class,
                    screens.size + 0,
                    role = NavigationRole.AUTH,
                ),
                DestinationMetadata(
                    "legacy.auth-login",
                    "hermes/auth-login",
                    AuthLoginScreen::class,
                    screens.size + 1,
                    role = NavigationRole.AUTH,
                ),
                DestinationMetadata(
                    "legacy.settings-connection",
                    "hermes/settings-connection",
                    SettingsConnection::class,
                    screens.size + 2,
                ),
                DestinationMetadata(
                    "legacy.settings-appearance",
                    "hermes/settings-appearance",
                    SettingsAppearance::class,
                    screens.size + 3,
                ),
                DestinationMetadata(
                    "legacy.settings-language",
                    "hermes/settings-language",
                    SettingsLanguage::class,
                    screens.size + 4,
                ),
                DestinationMetadata(
                    "legacy.settings-chat",
                    "hermes/settings-chat",
                    SettingsChat::class,
                    screens.size + 5,
                ),
                DestinationMetadata(
                    "legacy.settings-behavior",
                    "hermes/settings-behavior",
                    SettingsBehavior::class,
                    screens.size + 6,
                ),
                DestinationMetadata(
                    "legacy.settings-about",
                    "hermes/settings-about",
                    SettingsAbout::class,
                    screens.size + 7,
                ),
                DestinationMetadata(
                    "legacy.toolset-detail",
                    "hermes/toolset-detail",
                    ToolsetDetailKey::class,
                    screens.size + 8,
                ),
                DestinationMetadata(
                    "legacy.memory-provider-detail",
                    "hermes/memory-provider-detail",
                    MemoryProviderDetailKey::class,
                    screens.size + 9,
                ),
                DestinationMetadata(
                    "legacy.config-advanced",
                    "hermes/config-advanced",
                    ConfigAdvancedScreen::class,
                    screens.size + 11,
                ),
                DestinationMetadata(
                    "legacy.settings-google-connection",
                    "hermes/settings-google-connection",
                    SettingsGoogleConnection::class,
                    screens.size + 12,
                ),
                DestinationMetadata(
                    "legacy.settings-passwords",
                    "hermes/settings-passwords",
                    SettingsPasswords::class,
                    screens.size + 13,
                ),
                DestinationMetadata(
                    "legacy.external-services",
                    "hermes/external-services",
                    com.m57.hermescontrol.SettingsExternalServices::class,
                    screens.size + 14,
                ),
                DestinationMetadata(
                    "legacy.external-service-detail",
                    "hermes/external-service-detail",
                    com.m57.hermescontrol.ExternalServiceDetailKey::class,
                    screens.size + 15,
                ),
                DestinationMetadata(
                    "legacy.mcp-connection-settings",
                    "hermes/mcp-connection-settings",
                    com.m57.hermescontrol.McpConnectionSettingsKey::class,
                    screens.size + 16,
                ),
                DestinationMetadata(
                    "legacy.settings-permissions",
                    "hermes/settings-permissions",
                    com.m57.hermescontrol.SettingsPermissions::class,
                    screens.size + 17,
                ),
                DestinationMetadata(
                    "legacy.settings-notifications",
                    "hermes/settings-notifications",
                    com.m57.hermescontrol.SettingsNotifications::class,
                    screens.size + 18,
                ),
                DestinationMetadata(
                    "legacy.settings-memory",
                    "hermes/settings-memory",
                    com.m57.hermescontrol.SettingsMemory::class,
                    screens.size + 19,
                ),
                DestinationMetadata(
                    "legacy.settings-limits",
                    "hermes/settings-limits",
                    com.m57.hermescontrol.SettingsLimits::class,
                    screens.size + 20,
                ),
                DestinationMetadata(
                    "legacy.plugin-catalog",
                    "hermes/plugin-catalog",
                    com.m57.hermescontrol.PluginCatalogKey::class,
                    screens.size + 21,
                ),
                DestinationMetadata(
                    "legacy.plugin-advanced",
                    "hermes/plugin-advanced",
                    com.m57.hermescontrol.PluginAdvancedKey::class,
                    screens.size + 22,
                ),
            )
}
