package com.m57.hermescontrol.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SettingsGroup
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.common.SettingsPanel

/** Daily settings have one entry point; technical editors remain under Details. */
@Composable
fun SettingsScreen(
    onOpenDrawer: (() -> Unit)? = null,
    onNavigateToLogin: () -> Unit = {},
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onNavigate: (NavKey) -> Unit = { NavigationController.navigateTo(it) },
) {
    HermesScaffold(
        title = { Text(stringResource(R.string.settings_home_title)) },
        navigationIcon = NavIcon.Back(onBack ?: { NavigationController.goBack() }),
        drawerGesturesEnabled = false,
    ) {
        SettingsDirectoryContent(modifier, onNavigate)
    }
}

@Composable
internal fun SettingsDirectoryContent(
    modifier: Modifier = Modifier,
    onNavigate: (NavKey) -> Unit,
) {
    LazyColumn(
        modifier.fillMaxSize().testTag("settings_directory"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        SettingsDirectory.sections.forEach { section ->
            item("section_${section.id}") {
                SettingsGroupLabel(stringResource(section.titleRes))
                SettingsGroup(dividerInset = 16.dp) {
                    section.entries.forEach { entry ->
                        row {
                            SettingsLinkRow(
                                label = stringResource(entry.labelRes),
                                icon = entry.icon,
                                modifier = Modifier.testTag("settings_${entry.key}"),
                                onClick = { onNavigate(entry.key) },
                            )
                        }
                    }
                }
            }
        }
        item("footer") {
            SettingsGroup(modifier = Modifier.padding(top = 24.dp), dividerInset = 16.dp) {
                SettingsDirectory.footer.forEach { entry ->
                    row {
                        SettingsLinkRow(
                            label = stringResource(entry.labelRes),
                            icon = entry.icon,
                            modifier = Modifier.testTag("settings_${entry.key}"),
                            onClick = { onNavigate(entry.key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun InfoRow(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        )
    }
}

/** Shared compound form group for daily settings and their legacy aliases. */
@Composable
internal fun SectionCard(content: @Composable () -> Unit) {
    SettingsPanel(Modifier.padding(vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}
