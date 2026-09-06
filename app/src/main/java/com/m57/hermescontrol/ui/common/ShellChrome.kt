package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.ProfilesScreen
import com.m57.hermescontrol.R
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.navigation.AdvancedScreen
import com.m57.hermescontrol.navigation.ShellTab

/** Only authenticated navigation supplies this menu to the shared top bar. */
val LocalShellMenu = compositionLocalOf<(@Composable () -> Unit)?> { null }

@Composable
fun ShellMenu() {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("shell_menu")) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.shell_menu))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                R.string.screen_settings to SettingsScreen,
                R.string.shell_connection to SettingsConnection,
                R.string.screen_profiles to ProfilesScreen,
                R.string.shell_advanced to AdvancedScreen,
            ).forEach { (label, destination) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        expanded = false
                        NavigationController.navigateTo(destination)
                    },
                )
            }
        }
    }
}

@Composable
fun ShellBottomBar(selected: ShellTab) {
    NavigationBar {
        ShellTab.entries.forEach { tab ->
            val label =
                when (tab) {
                    ShellTab.TODAY -> R.string.shell_today
                    ShellTab.CHAT -> R.string.shell_chat
                    ShellTab.ACTIVITY -> R.string.shell_activity
                    ShellTab.APPS -> R.string.shell_apps
                }
            val icon =
                when (tab) {
                    ShellTab.TODAY -> Icons.Default.Home
                    ShellTab.CHAT -> Icons.AutoMirrored.Filled.Chat
                    ShellTab.ACTIVITY -> Icons.Default.Timeline
                    ShellTab.APPS -> Icons.Default.Apps
                }
            NavigationBarItem(
                selected = selected == tab,
                onClick = { NavigationController.selectTab(tab) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(stringResource(label)) },
                modifier = Modifier.testTag("shell_tab_${tab.name.lowercase()}"),
            )
        }
    }
}
