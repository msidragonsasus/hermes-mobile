package com.m57.hermescontrol.navigation

/** Chat retains its original contribution and content identity. */
class ShellContribution : AppContribution {
    override val id = "personal-ai.shell"
    override val destinations =
        listOf(
            DestinationMetadata("shell.today", "shell/today", TodayScreen::class, 1000, role = NavigationRole.ROOT),
            DestinationMetadata(
                "shell.activity",
                "shell/activity",
                ActivityScreen::class,
                1001,
                role = NavigationRole.ADVANCED,
            ),
            DestinationMetadata("shell.apps", "shell/apps", AppsScreen::class, 1002, role = NavigationRole.ROOT),
            DestinationMetadata(
                "shell.advanced",
                "shell/advanced",
                AdvancedScreen::class,
                1003,
                role = NavigationRole.ADVANCED,
            ),
            DestinationMetadata(
                "shell.home.detail",
                "shell/home/detail",
                com.m57.hermescontrol.home.HomeDetailKey::class,
                1005,
            ),
            DestinationMetadata("shell.attention", "shell/attention", AttentionDetailKey::class, 1004),
            DestinationMetadata("shell.agent", "shell/agent", AgentScreen::class, 1006, role = NavigationRole.ROOT),
            DestinationMetadata(
                "shell.agent.detail",
                "shell/agent/detail",
                com.m57.hermescontrol.agents.AgentDetailKey::class,
                1007,
            ),
            DestinationMetadata(
                "shell.agent.chat-settings",
                "shell/agent/chat-settings",
                com.m57.hermescontrol.agents.ChatSettingsKey::class,
                1008,
            ),
            DestinationMetadata(
                "shell.agent.bot-settings",
                "shell/agent/bot-settings",
                com.m57.hermescontrol.agents.BotSettingsKey::class,
                1009,
            ),
            DestinationMetadata(
                "shell.agent.bot-defaults",
                "shell/agent/bot-defaults",
                com.m57.hermescontrol.agents.BotDefaultsKey::class,
                1011,
            ),
            DestinationMetadata(
                "shell.openclaw.chat",
                "shell/openclaw/chat",
                com.m57.hermescontrol.openclaw.OpenClawChatKey::class,
                1012,
            ),
            DestinationMetadata(
                "shell.openclaw.settings",
                "shell/openclaw/settings",
                com.m57.hermescontrol.openclaw.OpenClawSettingsKey::class,
                1013,
            ),
        )
}
