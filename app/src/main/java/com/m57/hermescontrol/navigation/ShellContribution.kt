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
                role = NavigationRole.ROOT,
            ),
            DestinationMetadata("shell.apps", "shell/apps", AppsScreen::class, 1002, role = NavigationRole.ROOT),
            DestinationMetadata(
                "shell.advanced",
                "shell/advanced",
                AdvancedScreen::class,
                1003,
                role = NavigationRole.ADVANCED,
            ),
            DestinationMetadata("shell.attention", "shell/attention", AttentionDetailKey::class, 1004),
        )
}
