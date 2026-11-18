package com.m57.hermescontrol.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.PluginCatalogEntry
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.FilterChipRow
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.PrimaryPillButton
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SettingsFootnote
import com.m57.hermescontrol.ui.common.SettingsGroup
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.common.SettingsListItem
import com.m57.hermescontrol.ui.common.SettingsRow
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect

/** "プラグインを探す": the curated catalog, browsable by shelf, installable after a review sheet. */
@Composable
fun PluginCatalogScreen(viewModel: PluginsViewModel = viewModel { PluginsViewModel() }) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadCatalog() }
    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    HermesScaffold(
        title = { Text(stringResource(R.string.plugins_catalog_title)) },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        isRefreshing = state.catalogLoading,
        onRefresh = viewModel::loadCatalog,
    ) {
        val catalog = state.catalog
        when {
            catalog == null && state.catalogFailed -> {
                ErrorState(stringResource(R.string.plugins_catalog_failed), onRetry = viewModel::loadCatalog)
            }

            catalog == null -> {
                SkeletonListState()
            }

            else -> {
                PluginCatalogContent(catalog = catalog, onOpen = { selected = it.name })
            }
        }
    }

    state.catalog?.firstOrNull { it.name == selected }?.let { entry ->
        PluginCatalogSheet(
            entry = entry,
            installing = state.catalogInstalling == entry.name,
            onInstall = { viewModel.installFromCatalog(entry) },
            onDismiss = { selected = null },
        )
    }
}

private enum class CatalogFilter { RECOMMENDED, ALL }

@Composable
internal fun PluginCatalogContent(
    catalog: List<PluginCatalogEntry>,
    onOpen: (PluginCatalogEntry) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(CatalogFilter.RECOMMENDED) }
    val visible = visibleCatalogEntries(catalog, includeCommunity = filter == CatalogFilter.ALL, query = query)
    val shelves =
        PhoneCatalogCategories.mapNotNull { shelf ->
            visible.filter { it.shelf() == shelf }.takeIf { it.isNotEmpty() }?.let {
                shelf to
                    it
            }
        }

    LazyColumn(
        Modifier.fillMaxSize().testTag("plugin_catalog"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
    ) {
        item("controls") {
            Column {
                SearchBar(query, { query = it }, placeholder = stringResource(R.string.plugins_catalog_search))
                FilterChipRow(
                    chips = CatalogFilter.entries,
                    selectedChip = filter,
                    onChipSelected = { filter = it },
                    horizontalPadding = 0.dp,
                    modifier = Modifier.padding(top = 4.dp),
                    chipLabel = {
                        Text(
                            stringResource(
                                when (it) {
                                    CatalogFilter.RECOMMENDED -> R.string.plugins_catalog_filter_recommended
                                    CatalogFilter.ALL -> R.string.plugins_catalog_filter_all
                                },
                            ),
                        )
                    },
                )
            }
        }
        if (shelves.isEmpty()) {
            item("empty") { EmptyState(title = stringResource(R.string.plugins_catalog_empty)) }
        }
        shelves.forEach { (shelf, entries) ->
            item("label_$shelf") {
                SettingsGroupLabel("${stringResource(catalogCategoryLabel(shelf))}（${entries.size}）")
            }
            itemsIndexed(entries, key = { _, entry -> "entry_${entry.name}" }) { index, entry ->
                SettingsListItem(first = index == 0, last = index == entries.lastIndex) {
                    CatalogRow(entry, onClick = { onOpen(entry) })
                }
            }
        }
        item("footnotes") {
            Column {
                if (filter ==
                    CatalogFilter.ALL
                ) {
                    SettingsFootnote(stringResource(R.string.plugins_catalog_community_note))
                }
                SettingsFootnote(stringResource(R.string.plugins_catalog_hidden_note))
            }
        }
    }
}

@Composable
private fun CatalogRow(
    entry: PluginCatalogEntry,
    onClick: () -> Unit,
) {
    // Install state stays in the meta line (DESIGN.md "Settings destinations"): a badge would
    // squeeze the description at large text sizes.
    val status =
        when {
            entry.updateAvailable -> stringResource(R.string.plugins_catalog_update)
            entry.installed -> stringResource(R.string.plugins_catalog_installed)
            else -> null
        }
    val meta =
        listOfNotNull(status, stringResource(catalogTierLabel(entry.tier)), capabilityShortSummary(entry))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    SettingsRow(
        title = entry.displayName(),
        subtitle = listOf(meta, firstSentence(entry.description)).filter { it.isNotBlank() }.joinToString("\n"),
        subtitleMaxLines = 3,
        modifier = Modifier.testTag("catalog_${entry.name}"),
        chevron = true,
        onClick = onClick,
    )
}

/** "ツール3個 · 自動処理 · 要設定": the row-sized version of [capabilityLines]. */
@Composable
internal fun capabilityShortSummary(entry: PluginCatalogEntry): String {
    val facts = entry.capabilities.facts()
    return listOfNotNull(
        stringResource(R.string.plugins_cap_tools_short, facts.toolCount).takeIf { facts.toolCount > 0 },
        stringResource(R.string.plugins_cap_auto_short).takeIf { facts.automatic },
        stringResource(R.string.plugins_cap_env_short).takeIf { facts.requiredEnv.isNotEmpty() },
    ).joinToString(" · ")
}

/** One plain sentence per thing the plugin is allowed to do once installed. */
@Composable
internal fun capabilityLines(entry: PluginCatalogEntry): List<String> {
    val facts = entry.capabilities.facts()
    if (facts.declaresNothing) return listOf(stringResource(R.string.plugins_cap_none))
    return buildList {
        if (facts.toolCount > 0) add(stringResource(R.string.plugins_cap_tools, facts.toolCount))
        facts.hookKinds.forEach { add(stringResource(it.label)) }
        if (facts.middleware) add(stringResource(R.string.plugins_cap_middleware))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginCatalogSheet(
    entry: PluginCatalogEntry,
    installing: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirm by rememberSaveable(entry.name) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val facts = entry.capabilities.facts()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .testTag("plugin_catalog_sheet"),
        ) {
            Text(
                entry.displayName(),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                listOf(
                    stringResource(catalogTierLabel(entry.tier)),
                    stringResource(catalogCategoryLabel(entry.shelf())),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )

            val lines = capabilityLines(entry)
            SettingsGroupLabel(stringResource(R.string.plugins_detail_can))
            SettingsGroup {
                lines.forEach { line -> row { SettingsRow(title = line, maxLines = Int.MAX_VALUE) } }
            }
            if (facts.requiredEnv.isNotEmpty()) {
                SettingsGroupLabel(stringResource(R.string.plugins_detail_needs))
                SettingsGroup {
                    row {
                        SettingsRow(
                            title = stringResource(R.string.plugins_cap_env, facts.requiredEnv.joinToString("、")),
                            maxLines = Int.MAX_VALUE,
                        )
                    }
                }
            }
            if (entry.knownIssues.isNotEmpty()) {
                SettingsGroupLabel(stringResource(R.string.plugins_detail_notes))
                SettingsGroup {
                    entry.knownIssues.forEach { issue -> row { SettingsRow(title = issue, maxLines = Int.MAX_VALUE) } }
                }
            }
            SettingsGroupLabel(stringResource(R.string.plugins_detail_source))
            SettingsGroup {
                if (entry.maintainer.isNotBlank()) {
                    row { SettingsRow(title = stringResource(R.string.plugins_detail_maker), value = entry.maintainer) }
                }
                if (entry.version.isNotBlank()) {
                    row { SettingsRow(title = stringResource(R.string.plugins_detail_version), value = entry.version) }
                }
                val link = entry.docsUrl.ifBlank { entry.repo }
                if (link.startsWith("https://")) {
                    row {
                        SettingsRow(
                            title = stringResource(R.string.plugins_detail_repository),
                            subtitle = link.removePrefix("https://"),
                            subtitleMaxLines = 1,
                            chevron = true,
                            onClick = { uriHandler.openUri(link) },
                        )
                    }
                }
            }
            if (entry.description.isNotBlank()) {
                SettingsGroupLabel(stringResource(R.string.plugins_detail_about))
                SettingsFootnote(entry.description, Modifier.padding(top = 0.dp))
            }

            PrimaryPillButton(
                onClick = { confirm = true },
                enabled = !entry.installed && !installing,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            top = 24.dp,
                        ).heightIn(min = 52.dp)
                        .testTag("plugin_catalog_install"),
            ) {
                if (installing) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    val label =
                        if (entry.installed) R.string.plugins_catalog_installed else R.string.plugins_detail_install
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.plugins_install_confirm_title, entry.displayName())) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.plugins_install_confirm_text))
                    if (entry.tier !in RecommendedTiers) {
                        Text(
                            stringResource(
                                R.string.plugins_install_confirm_community,
                                entry.maintainer.ifBlank { "?" },
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        onInstall()
                    },
                    modifier = Modifier.testTag("plugin_install_confirm"),
                ) { Text(stringResource(R.string.plugins_install_confirm_action)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirm = false },
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Technical plugin settings kept one step away from the everyday list. */
@Composable
fun PluginAdvancedScreen(viewModel: PluginsViewModel = viewModel { PluginsViewModel() }) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (state.plugins.isEmpty()) viewModel.loadPlugins() }
    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)
    HermesScaffold(
        title = { Text(stringResource(R.string.plugins_advanced)) },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        ) {
            if (state.memoryOptions.isNotEmpty() || state.contextOptions.isNotEmpty()) {
                ProviderSelectionSection(state, viewModel)
            }
            InstallSection(state, viewModel)
        }
    }
}
