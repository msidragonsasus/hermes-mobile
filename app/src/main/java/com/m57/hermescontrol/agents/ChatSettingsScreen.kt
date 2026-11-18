package com.m57.hermescontrol.agents

import androidx.compose.runtime.Composable
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.ui.settings.SettingsScreen

/** Compatibility entry: the conversation gear opens the same settings directory as the drawer. */
@Composable
fun ChatSettingsScreen() {
    SettingsScreen(onBack = { NavigationController.goBack() })
}
