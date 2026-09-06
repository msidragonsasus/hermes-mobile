package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.HistoryScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.PluginsScreen
import com.m57.hermescontrol.R
import com.m57.hermescontrol.navigation.ShellTab
import com.m57.hermescontrol.theme.LocalSpacing

/** Navigation migration surface; PR09/10 supply the observed activity and mini-app content. */
@Composable
fun ShellRootScreen(tab: ShellTab) {
    val title =
        when (tab) {
            ShellTab.TODAY -> R.string.shell_today
            ShellTab.ACTIVITY -> R.string.shell_activity
            ShellTab.APPS -> R.string.shell_apps
            ShellTab.CHAT -> error("Chat uses its existing screen")
        }
    val action =
        when (tab) {
            ShellTab.TODAY -> R.string.shell_open_chat to ChatScreen
            ShellTab.ACTIVITY -> R.string.screen_history to HistoryScreen
            ShellTab.APPS -> R.string.screen_plugins to PluginsScreen
            ShellTab.CHAT -> error("Chat uses its existing screen")
        }
    val spacing = LocalSpacing.current
    HermesScaffold(title = { Text(stringResource(title)) }, drawerGesturesEnabled = false) {
        Column(
            modifier = Modifier.fillMaxSize().padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Button(onClick = { NavigationController.navigateTo(action.second) }) {
                Text(stringResource(action.first))
            }
        }
    }
}
