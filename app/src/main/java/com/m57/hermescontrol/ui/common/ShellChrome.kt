package com.m57.hermescontrol.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.navigation.ShellTab

/** Only authenticated navigation supplies this menu to the shared top bar. */
val LocalShellMenu = compositionLocalOf<(@Composable () -> Unit)?> { null }

/** Management is a full-screen flow that returns to the originating tab. */
val LocalManagementScope = compositionLocalOf { false }

@Composable
fun ShellMenu() {
    IconButton(
        onClick = { NavigationController.navigateTo(SettingsScreen) },
        modifier = Modifier.testTag("shell_menu"),
    ) {
        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_home_title))
    }
}

@Composable
fun ShellBottomBar(selected: ShellTab) {
    ShortNavigationBar {
        ShellTab.primary.forEach { tab ->
            val label =
                when (tab) {
                    ShellTab.TODAY, ShellTab.ACTIVITY -> R.string.shell_today
                    ShellTab.CHAT -> R.string.shell_chat
                    ShellTab.APPS -> R.string.shell_apps
                    ShellTab.AGENT -> R.string.shell_agent
                }
            val icon =
                when (tab) {
                    ShellTab.TODAY, ShellTab.ACTIVITY -> Icons.Default.Home
                    ShellTab.CHAT -> Icons.AutoMirrored.Filled.Chat
                    ShellTab.APPS -> Icons.Default.Apps
                    ShellTab.AGENT -> Icons.Default.SmartToy
                }
            ShortNavigationBarItem(
                selected = selected == tab,
                onClick = { NavigationController.selectTabFromBar(tab) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(if (tab == ShellTab.TODAY) "ホーム" else stringResource(label)) },
                modifier = Modifier.testTag("shell_tab_${tab.name.lowercase()}"),
            )
        }
    }
}
