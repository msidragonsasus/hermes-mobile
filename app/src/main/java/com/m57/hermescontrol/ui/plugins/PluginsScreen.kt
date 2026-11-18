package com.m57.hermescontrol.ui.plugins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.InstallDesktop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.MemoryProviderDetailKey
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.PluginAdvancedKey
import com.m57.hermescontrol.PluginCatalogKey
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.PluginCatalogEntry
import com.m57.hermescontrol.data.model.PluginInfo
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.common.DetailDialog
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.ExposedDropdownField
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.HermesTextField
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SettingsFootnote
import com.m57.hermescontrol.ui.common.SettingsFormSubmit
import com.m57.hermescontrol.ui.common.SettingsGroup
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.common.SettingsListItem
import com.m57.hermescontrol.ui.common.SettingsMenuItem
import com.m57.hermescontrol.ui.common.SettingsPanel
import com.m57.hermescontrol.ui.common.SettingsRow
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing
import com.m57.hermescontrol.ui.common.rememberHasLoaded
import com.m57.hermescontrol.ui.common.toDetailRows

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: PluginsViewModel = viewModel { PluginsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var showDetail by remember { mutableStateOf<PluginInfo?>(null) }

    val filteredPlugins =
        remember(query, state.plugins) {
            state.plugins.filter { plugin ->
                plugin.name.contains(query, ignoreCase = true) ||
                    plugin.description?.contains(query, ignoreCase = true) == true
            }
        }

    LaunchedEffect(Unit) {
        viewModel.loadPlugins()
        // Optional: it only adds the plain "what it adds" line to rows installed from the catalog.
        viewModel.loadCatalog()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)
    val hasLoaded = rememberHasLoaded(state.isLoading)

    // Remove confirmation dialog
    if (state.removeConfirmPlugin != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelRemovePlugin() },
            title = { Text(stringResource(R.string.plugins_remove_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.plugins_remove_confirm_text,
                        state.removeConfirmPlugin ?: "",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmRemovePlugin() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.plugins_action_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelRemovePlugin() }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_plugins)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) } ?: NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = onOpenDrawer != null,
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadPlugins() },
    ) {
        when {
            state.isLoading && state.plugins.isEmpty() && !hasLoaded -> {
                SkeletonListState()
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadPlugins() },
                )
            }

            state.plugins.isEmpty() && state.orphanPlugins.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.plugins_empty_title),
                    subtitle = stringResource(R.string.plugins_empty_desc),
                    onAction = { NavigationController.navigateTo(PluginCatalogKey) },
                    actionLabel = stringResource(R.string.plugins_catalog_title),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                ) {
                    item(key = "add") {
                        Column {
                            SettingsGroupLabel(stringResource(R.string.plugins_group_add))
                            SettingsGroup {
                                row {
                                    SettingsRow(
                                        title = stringResource(R.string.plugins_catalog_title),
                                        subtitle = stringResource(R.string.plugins_catalog_entry_subtitle),
                                        leading = { Icon(Icons.Default.Search, null, Modifier.size(24.dp)) },
                                        chevron = true,
                                        onClick = { NavigationController.navigateTo(PluginCatalogKey) },
                                        modifier = Modifier.testTag("plugins_open_catalog"),
                                    )
                                }
                            }
                        }
                    }
                    item(key = "installed-label") {
                        SettingsGroupLabel(stringResource(R.string.plugins_installed_count, filteredPlugins.size))
                    }
                    // A search row only earns its place once the list is longer than a screen.
                    if (state.plugins.size > SEARCH_THRESHOLD) {
                        item(key = "search") {
                            SearchBar(
                                query = query,
                                onQueryChange = { query = it },
                                placeholder = stringResource(R.string.plugins_search_placeholder),
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    }

                    // Plugin list
                    itemsIndexed(filteredPlugins, key = { _, plugin -> plugin.name }) { index, plugin ->
                        PluginCard(
                            first = index == 0,
                            last = index == filteredPlugins.lastIndex,
                            plugin = plugin,
                            state = state,
                            viewModel = viewModel,
                            onClick = { showDetail = plugin },
                            catalogEntry = state.catalog?.let { catalogEntryFor(plugin, it) },
                        )
                    }
                    item(key = "installed-footnote") {
                        SettingsFootnote(stringResource(R.string.plugins_footnote_what))
                    }

                    // Orphan dashboard plugins section
                    if (state.orphanPlugins.isNotEmpty()) {
                        item(key = "orphan-header") {
                            SettingsGroupLabel(stringResource(R.string.plugins_orphan_heading))
                        }
                        itemsIndexed(state.orphanPlugins, key = {
                            _,
                            plugin,
                            ->
                            "orphan-${plugin.name}"
                        }) { index, plugin ->
                            OrphanPluginCard(
                                plugin = plugin,
                                first = index == 0,
                                last =
                                    index == state.orphanPlugins.lastIndex,
                                onClick = { showDetail = plugin },
                            )
                        }
                    }

                    // Connectors are their own settings destination; this is a final shortcut, not a second home.
                    item(key = "external-services") {
                        SettingsGroup(Modifier.padding(top = 24.dp)) {
                            row {
                                SettingsRow(
                                    title = stringResource(R.string.plugins_advanced),
                                    subtitle = stringResource(R.string.plugins_advanced_subtitle),
                                    chevron = true,
                                    onClick = { NavigationController.navigateTo(PluginAdvancedKey) },
                                )
                            }
                            row {
                                SettingsRow(
                                    title = stringResource(R.string.management_external_services),
                                    subtitle = "Gmail・カレンダー・Figma・Slackなどの接続とアカウント設定を開く",
                                    chevron = true,
                                    onClick = {
                                        NavigationController.navigateTo(com.m57.hermescontrol.SettingsExternalServices)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    showDetail?.let { plugin ->
        DetailDialog(
            title = plugin.name,
            rows = plugin.toDetailRows(),
            onDismiss = { showDetail = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderSelectionSection(
    state: PluginsUiState,
    viewModel: PluginsViewModel,
) {
    val providerDefaultsLabel = stringResource(R.string.plugins_provider_defaults)

    SettingsGroupLabel(stringResource(R.string.plugins_providers_heading))
    SettingsGroup {
        row {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                ExposedDropdownField(
                    label = stringResource(R.string.plugins_memory_provider_label),
                    options =
                        listOf(
                            providerDefaultsLabel,
                        ) + state.memoryOptions.map { it.name },
                    selectedValue =
                        if (state.isMemoryBuiltin) {
                            providerDefaultsLabel
                        } else {
                            state.memoryProvider
                        },
                    onOptionSelected = { selected ->
                        if (selected == providerDefaultsLabel) {
                            viewModel.updateMemoryProvider(PluginsUiState.MEMORY_PROVIDER_BUILTIN)
                        } else {
                            viewModel.updateMemoryProvider(selected)
                        }
                    },
                )
            }
        }
        // Manage the selected provider's config/setup (issue #783).
        if (!state.isMemoryBuiltin && state.memoryProvider.isNotBlank()) {
            row {
                SettingsRow(
                    title = stringResource(R.string.plugins_memory_manage),
                    subtitle = state.memoryProvider,
                    chevron = true,
                    onClick = {
                        NavigationController.navigateTo(
                            MemoryProviderDetailKey(
                                name = state.memoryProvider,
                                label = state.memoryProvider,
                            ),
                        )
                    },
                )
            }
        }
        row {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                ExposedDropdownField(
                    label = stringResource(R.string.plugins_context_engine_label),
                    options = listOf("compressor") + state.contextOptions.map { it.name },
                    selectedValue = state.contextEngine,
                    onOptionSelected = { viewModel.updateContextEngine(it) },
                )
            }
        }
        row {
            SettingsFormSubmit(
                label = stringResource(R.string.common_save),
                busy = state.providerBusy,
                enabled = !state.providerBusy,
                onClick = { viewModel.savePluginProviders() },
            )
        }
    }
    SettingsFootnote(stringResource(R.string.plugins_providers_hint))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InstallSection(
    state: PluginsUiState,
    viewModel: PluginsViewModel,
) {
    SettingsGroupLabel(stringResource(R.string.plugins_install_heading))
    SettingsGroup {
        row {
            HermesTextField(
                value = state.installUrl,
                onValueChange = { viewModel.updateInstallUrl(it) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text(stringResource(R.string.plugins_repo_placeholder)) },
                singleLine = true,
                enabled = !state.installBusy,
            )
        }
        row {
            SettingsRow(
                title = stringResource(R.string.plugins_enable_after_install),
                checked = state.installEnable,
                onCheckedChange = { viewModel.updateInstallEnable(it) },
                enabled = !state.installBusy,
            )
        }
        row {
            SettingsRow(
                title = stringResource(R.string.plugins_force_reinstall),
                checked = state.installForce,
                onCheckedChange = { viewModel.updateInstallForce(it) },
                enabled = !state.installBusy,
            )
        }
        row {
            SettingsFormSubmit(
                label = stringResource(R.string.plugins_action_install),
                busy = state.installBusy,
                enabled = !state.installBusy && state.installUrl.isNotBlank(),
                onClick = { viewModel.installPluginFromUrl() },
            )
        }
    }
    SettingsFootnote(stringResource(R.string.plugins_install_hint))
}

private const val SEARCH_THRESHOLD = 6

@Composable
internal fun PluginCard(
    plugin: PluginInfo,
    state: PluginsUiState,
    viewModel: PluginsViewModel,
    onClick: () -> Unit,
    first: Boolean = true,
    last: Boolean = true,
    catalogEntry: PluginCatalogEntry? = null,
) {
    val busy = state.rowBusy == plugin.name
    val statusColors = LocalHermesStatusColors.current
    val actions =
        if (plugin.installed) {
            listOfNotNull(
                if (plugin.hasDashboardManifest) {
                    SettingsMenuItem(
                        stringResource(
                            if (plugin.userHidden) R.string.plugins_action_show else R.string.plugins_action_hide,
                        ),
                    ) { viewModel.togglePluginVisibility(plugin) }
                } else {
                    null
                },
                if (plugin.canUpdateGit) {
                    SettingsMenuItem(
                        stringResource(R.string.plugins_action_update),
                    ) { viewModel.updatePlugin(plugin.name) }
                } else {
                    null
                },
                if (plugin.canRemove) {
                    SettingsMenuItem(stringResource(R.string.plugins_action_uninstall)) {
                        viewModel.requestRemovePlugin(plugin.name)
                    }
                } else {
                    null
                },
            )
        } else {
            emptyList()
        }
    // An authentication need replaces the description: it is the one thing the person must act on.
    // It stays in the meta line (text + warning color) so large text never squeezes the title.
    val authRequiredLabel = stringResource(R.string.plugins_auth_required)
    val authHint =
        if (plugin.authRequired) {
            listOfNotNull(
                authRequiredLabel,
                plugin.authCommand?.let { stringResource(R.string.plugins_auth_command_hint, it) },
            ).joinToString(" · ")
        } else {
            null
        }
    // Catalog plugins say what they add in plain words first; the server's own text follows.
    val summary =
        catalogEntry?.let { entry ->
            // The shelf name is dropped when the capabilities already say it ("ツール · ツール1個").
            listOf(
                stringResource(catalogCategoryLabel(entry.shelf())).takeIf { entry.shelf() != "tools" }.orEmpty(),
                capabilityShortSummary(entry),
            ).filter { it.isNotBlank() }
                .joinToString(" · ")
        }
    val description = plugin.description?.takeIf { it.isNotBlank() }?.let(::firstSentence)
    SettingsListItem(first = first, last = last) {
        SettingsRow(
            title = catalogEntry?.displayName() ?: plugin.name,
            subtitle =
                authHint
                    ?: listOfNotNull(summary, description).joinToString("\n").ifBlank {
                        stringResource(R.string.plugins_no_desc)
                    },
            subtitleColor = if (authHint != null) statusColors.warning else Color.Unspecified,
            subtitleMaxLines = if (authHint != null) Int.MAX_VALUE else 2,
            checked = if (plugin.installed && !busy) plugin.enabled else null,
            onCheckedChange = { viewModel.togglePlugin(plugin) },
            actions = actions,
            actionsEnabled = !busy,
            onClick = onClick,
            trailing =
                when {
                    busy -> {
                        { CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp) }
                    }

                    !plugin.installed -> {
                        {
                            TextButton(onClick = { viewModel.activatePlugin(plugin) }) {
                                Text(stringResource(R.string.plugins_action_install))
                            }
                        }
                    }

                    else -> {
                        null
                    }
                },
        )
    }
}

@Composable
private fun OrphanPluginCard(
    plugin: PluginInfo,
    onClick: () -> Unit,
    first: Boolean = true,
    last: Boolean = true,
) {
    SettingsListItem(first = first, last = last) {
        SettingsRow(title = plugin.name, subtitle = plugin.description, onClick = onClick)
    }
}
