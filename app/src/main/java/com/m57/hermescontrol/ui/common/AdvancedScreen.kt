package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.DrawerSection
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ScreenRegistry

/** Keeps every existing management destination reachable without changing its implementation. */
@Composable
fun AdvancedScreenContent() {
    HermesScaffold(
        title = { Text(stringResource(R.string.shell_advanced)) },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            DrawerSection.entries.forEach { section ->
                item("section_${section.name}") {
                    ListItem(headlineContent = { Text(stringResource(section.titleRes)) })
                }
                items(ScreenRegistry.ALL_SCREENS.filter { it.drawerSection == section }) { screen ->
                    ListItem(
                        headlineContent = { Text(stringResource(screen.labelRes)) },
                        leadingContent = { Icon(screen.icon, contentDescription = null) },
                        modifier = Modifier.clickable { NavigationController.navigateTo(screen.key) },
                    )
                }
            }
        }
    }
}
