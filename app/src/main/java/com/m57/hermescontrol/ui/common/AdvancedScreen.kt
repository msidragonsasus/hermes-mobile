package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ConfigScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.SettingsAbout
import com.m57.hermescontrol.SettingsAppearance
import com.m57.hermescontrol.SettingsBehavior
import com.m57.hermescontrol.SettingsChat
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.SettingsLanguage
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.navigation.AppManagement
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.config.AgentSettingsCatalog
import com.m57.hermescontrol.ui.settings.SettingsDirectory

/** One searchable thing: a screen, or a single setting inside one. */
private data class SearchHit(
    val id: String,
    val title: String,
    val location: String,
    val terms: List<String>,
    val key: NavKey,
)

/** App settings items that live inline on [SettingsScreen] (or one level below it). */
private val appSettingItems: List<Pair<Int, NavKey>> =
    listOf(
        R.string.settings_item_theme to SettingsAppearance,
        R.string.settings_item_theme_preset to SettingsAppearance,
        R.string.settings_item_use_dynamic_colors to SettingsAppearance,
        R.string.settings_item_language to SettingsLanguage,
        R.string.settings_item_typing_effect to SettingsChat,
        R.string.settings_item_chat_font_size to SettingsAppearance,
        R.string.settings_item_auto_reconnect to SettingsBehavior,
        R.string.settings_sec_connection to SettingsConnection,
        R.string.settings_sec_about to SettingsAbout,
    )

/** Every existing management screen remains reachable through its purpose-based directory. */
@Composable
fun AdvancedScreenContent() {
    val spacing = LocalSpacing.current
    var query by rememberSaveable { mutableStateOf("") }
    val hits = searchIndex()
    val q = query.trim().lowercase()
    val matches = if (q.isEmpty()) emptyList() else hits.filter { hit -> hit.terms.any { it.lowercase().contains(q) } }

    HermesScaffold(
        title = { Text(stringResource(R.string.settings_advanced)) },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
    ) {
        LazyColumn(Modifier.fillMaxSize().testTag("app_management_list")) {
            item("search") {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.management_search_hint),
                    modifier =
                        Modifier
                            .padding(horizontal = spacing.lg, vertical = spacing.sm)
                            .testTag("management_search"),
                )
            }
            if (q.isNotEmpty()) {
                items(matches, key = { it.id }) { hit ->
                    DirectoryRow(
                        title = hit.title,
                        subtitle = hit.location,
                        icon = null,
                        onClick = { NavigationController.navigateTo(hit.key) },
                    )
                }
                if (matches.isEmpty()) {
                    item("no_hits") {
                        Text(
                            stringResource(R.string.management_search_empty, query.trim()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
                        )
                    }
                }
                return@LazyColumn
            }
            item("app_preferences") {
                DirectoryRow(
                    title = stringResource(R.string.settings_app_advanced),
                    subtitle = stringResource(R.string.settings_app_advanced_desc),
                    icon = null,
                    onClick = { NavigationController.navigateTo(SettingsBehavior) },
                )
            }
            item("purpose") {
                Text(
                    stringResource(R.string.app_management_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
                )
            }
            AppManagement.sections.forEach { section ->
                item("section_${section.id}") {
                    Text(
                        stringResource(section.titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
                    )
                }
                items(section.entries, key = { it.key.toString() }) { entry ->
                    DirectoryRow(
                        title = stringResource(entry.labelRes),
                        subtitle = stringResource(entry.descriptionRes),
                        icon = entry.icon,
                        modifier = Modifier.testTag("management_${entry.key}"),
                        onClick = { NavigationController.navigateTo(entry.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectoryRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = modifier.clickable(onClick = onClick),
    )
}

/** Screens, app settings and the friendly agent settings, searchable from one field. */
@Composable
private fun searchIndex(): List<SearchHit> {
    val screens =
        (AppManagement.sections.flatMap { it.entries } + SettingsDirectory.sections.flatMap { it.entries })
            .distinctBy { it.key }
            .map { entry ->
                val label = stringResource(entry.labelRes)
                val description = stringResource(entry.descriptionRes)
                SearchHit("screen:${entry.key}", label, description, listOf(label, description), entry.key)
            }
    val appLocation = stringResource(R.string.management_search_in_app_settings)
    val appItems =
        appSettingItems.map { (res, key) ->
            val title = stringResource(res)
            SearchHit("app:$res", title, appLocation, listOf(title), key)
        }
    val agentLocation = stringResource(R.string.management_search_in_agent_settings)
    val agentItems =
        AgentSettingsCatalog.all.map { setting ->
            SearchHit(
                "agent:${setting.id}",
                setting.title,
                agentLocation,
                listOf(setting.title, setting.description) + setting.keywords,
                ConfigScreen,
            )
        }
    return appItems + agentItems + screens
}
