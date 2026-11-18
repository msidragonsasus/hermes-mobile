package com.m57.hermescontrol.ui.skills

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.HubSkill
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.data.model.SkillHubSource
import com.m57.hermescontrol.data.model.SkillScanFinding
import com.m57.hermescontrol.data.model.SkillScanResponse
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.common.ConnectedButtonGroup
import com.m57.hermescontrol.ui.common.DetailDialog
import com.m57.hermescontrol.ui.common.DetailRow
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.FilterChipRow
import com.m57.hermescontrol.ui.common.FlatListRow
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.HermesTextField
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SettingsFootnote
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.common.SettingsListItem
import com.m57.hermescontrol.ui.common.SettingsMenuItem
import com.m57.hermescontrol.ui.common.SettingsPanel
import com.m57.hermescontrol.ui.common.SettingsRow
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listItemSpacing
import com.m57.hermescontrol.ui.common.toDetailRows

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {},
    viewModel: SkillsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf(SkillFilter.ALL_STATUSES) }

    LaunchedEffect(Unit) {
        viewModel.loadSkills()
    }

    HermesScaffold(
        modifier = modifier,
        title = { Text(stringResource(R.string.skills_screen_title)) },
        navigationIcon = NavIcon.Menu(onOpen = onOpenDrawer),
        isRefreshing = state.isLoading,
        onRefresh = {
            if (state.viewMode == SkillsViewMode.INSTALLED) {
                viewModel.loadSkills()
            } else {
                viewModel.loadHubSources()
            }
        },
        actions = {
            if (state.viewMode == SkillsViewMode.INSTALLED) {
                IconButton(onClick = { viewModel.updateSkillsFromHub() }) {
                    Icon(
                        imageVector = Icons.Filled.CloudDownload,
                        contentDescription = stringResource(R.string.content_desc_update_skills_hub),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // ── View mode tabs ──────────────────────────────────────
            ConnectedButtonGroup(
                options = SkillsViewMode.entries,
                selected = state.viewMode,
                onSelect = viewModel::setViewMode,
                label = { mode ->
                    when (mode) {
                        SkillsViewMode.INSTALLED -> stringResource(R.string.skills_mode_installed)
                        SkillsViewMode.HUB -> stringResource(R.string.skills_mode_hub)
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            when (state.viewMode) {
                SkillsViewMode.INSTALLED -> {
                    InstalledSkillsView(
                        state = state,
                        query = query,
                        onQueryChange = { query = it },
                        selectedStatus = selectedStatus,
                        onStatusChange = { selectedStatus = it },
                        onToggle = viewModel::toggleSkill,
                        onEdit = viewModel::loadSkillContent,
                        onUninstall = viewModel::uninstallSkill,
                        isUninstalling = state.isUninstalling,
                        uninstallingSkillName = state.uninstallingSkillName,
                        onRefresh = viewModel::loadSkills,
                        onBrowseHub = { viewModel.setViewMode(SkillsViewMode.HUB) },
                    )
                }

                SkillsViewMode.HUB -> {
                    HubBrowseView(
                        state = state,
                        hubQuery = state.hubQuery,
                        onHubQueryChange = viewModel::setHubQuery,
                        onSearch = viewModel::searchHub,
                        onClearSearch = viewModel::clearHubSearch,
                        onInstall = viewModel::installSkill,
                        onPreviewHubSkill = viewModel::previewHubSkill,
                        onScanHubSkill = viewModel::scanHubSkill,
                        onClearHubScan = viewModel::clearHubScan,
                        onLoadHubSources = viewModel::loadHubSources,
                        isInstalling = state.isInstalling,
                        installingSkillName = state.installingSkillName,
                    )
                }
            }
        }
    }

    // ── Dialogs ────────────────────────────────────────────────────

    state.editingSkillName?.let { skillName ->
        SkillEditorDialog(
            skillName = skillName,
            isLoading = state.isLoadingContent,
            initialContent = state.skillContent,
            isSaving = state.isSavingContent,
            saveSuccess = state.saveContentSuccess,
            onSave = { content -> viewModel.saveSkillContent(skillName, content) },
            onDismiss = { viewModel.clearEditor() },
            onClearSaveSuccess = { viewModel.clearSaveSuccess() },
        )
    }

    state.previewSkillName?.let { skillName ->
        SkillPreviewDialog(
            skillName = skillName,
            isLoading = state.isLoadingPreview,
            content = state.previewSkillContent,
            onDismiss = { viewModel.clearPreview() },
        )
    }

    ToastEffect(
        toastMessage = state.toastMessage,
        onClearToast = viewModel::clearToast,
    )
}

// ── Installed Skills View ───────────────────────────────────────────────

@Composable
private fun InstalledSkillsView(
    state: SkillsUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedStatus: SkillFilter,
    onStatusChange: (SkillFilter) -> Unit,
    onToggle: (Skill) -> Unit,
    onEdit: (String) -> Unit,
    onUninstall: (String) -> Unit,
    isUninstalling: Boolean,
    uninstallingSkillName: String?,
    onRefresh: () -> Unit,
    onBrowseHub: () -> Unit,
) {
    var showDetail by remember { mutableStateOf<Skill?>(null) }

    val filteredSkills =
        // One filter row (DESIGN.md "Settings destinations"): search plus enabled state. Category and
        // source no longer filter, so a stale selection kept by the activity-scoped ViewModel cannot hide skills.
        remember(state.skills, query, selectedStatus) {
            state.skills.filter { skill ->
                (
                    query.isBlank() || skill.name.contains(query, ignoreCase = true) ||
                        skill.description?.contains(query, ignoreCase = true) == true
                ) &&
                    (
                        selectedStatus == SkillFilter.ALL_STATUSES ||
                            (selectedStatus == SkillFilter.ENABLED && skill.enabled) ||
                            (selectedStatus == SkillFilter.DISABLED && !skill.enabled)
                    )
            }
        }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.skills_search_placeholder),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        FilterChipRow(
            chips = SkillFilter.entries.toList(),
            selectedChip = selectedStatus,
            onChipSelected = onStatusChange,
            horizontalPadding = 16.dp,
            chipLabel = { chip ->
                Text(
                    text =
                        if (chip == SkillFilter.ALL_STATUSES) {
                            stringResource(R.string.skills_status_all)
                        } else {
                            stringResource(chip.labelRes)
                        },
                )
            },
        )

        when {
            state.isLoading -> {
                SkeletonListState()
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage,
                    onRetry = onRefresh,
                )
            }

            filteredSkills.isEmpty() -> {
                EmptyState(
                    icon = Icons.Filled.Extension,
                    title = stringResource(R.string.skills_empty_message),
                    actionLabel = stringResource(R.string.empty_action_browse_hub),
                    onAction = onBrowseHub,
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                ) {
                    item(key = "installed_label") {
                        SettingsGroupLabel(stringResource(R.string.skills_installed_count, filteredSkills.size))
                    }
                    itemsIndexed(
                        filteredSkills,
                        key = { index, skill -> "${skill.name}:${skill.source ?: "unknown"}:$index" },
                    ) { index, skill ->
                        SkillCard(
                            first = index == 0,
                            last = index == filteredSkills.lastIndex,
                            skill = skill,
                            onToggle = { onToggle(skill) },
                            onAction = { onEdit(skill.name) },
                            onUninstall =
                                if (skill.source == "hub") {
                                    { onUninstall(skill.name) }
                                } else {
                                    null
                                },
                            isUninstalling = isUninstalling && uninstallingSkillName == skill.name,
                            onClick = { showDetail = skill },
                        )
                    }
                }
            }
        }
    }

    showDetail?.let { skill ->
        DetailDialog(
            title = skill.name,
            rows = skill.toDetailRows(),
            onDismiss = { showDetail = null },
        )
    }
}

// ── Skill Card ─────────────────────────────────────────────────────────

@Composable
internal fun SkillCard(
    skill: Skill,
    onToggle: () -> Unit,
    onAction: () -> Unit,
    onUninstall: (() -> Unit)?,
    isUninstalling: Boolean,
    onClick: () -> Unit,
    first: Boolean = true,
    last: Boolean = true,
) {
    val editLabel = stringResource(R.string.settings_action_edit)
    val uninstallLabel = stringResource(R.string.skills_action_uninstall)
    SettingsListItem(first = first, last = last) {
        SettingsRow(
            title = skill.name,
            subtitle = skill.description?.takeIf { it.isNotBlank() } ?: skill.category,
            checked = skill.enabled,
            onCheckedChange = { onToggle() },
            actions =
                listOfNotNull(
                    SettingsMenuItem(editLabel, onClick = onAction),
                    onUninstall?.let { SettingsMenuItem(uninstallLabel, enabled = !isUninstalling, onClick = it) },
                ),
            onClick = onClick,
            trailing =
                if (isUninstalling) {
                    { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                } else {
                    null
                },
        )
    }
}

// ── Hub Browse View ─────────────────────────────────────────────────────

@Composable
private fun HubBrowseView(
    state: SkillsUiState,
    hubQuery: String,
    onHubQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onInstall: (String) -> Unit,
    onPreviewHubSkill: (String) -> Unit,
    onScanHubSkill: (String) -> Unit,
    onClearHubScan: () -> Unit,
    onLoadHubSources: () -> Unit,
    isInstalling: Boolean,
    installingSkillName: String?,
) {
    var hubShowDetail by remember { mutableStateOf<HubSkill?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = hubQuery,
            onQueryChange = { value ->
                onHubQueryChange(value)
                if (value.isEmpty()) onClearSearch()
            },
            placeholder = stringResource(R.string.skills_hub_search_placeholder),
            onSearch = { if (hubQuery.isNotBlank()) onSearch(hubQuery) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        when {
            state.isHubSearching -> {
                SkeletonListState()
            }

            state.hubSearchError != null -> {
                ErrorState(
                    message = state.hubSearchError,
                    onRetry = { onSearch(hubQuery) },
                )
            }

            state.hubResults.isEmpty() && hubQuery.isNotBlank() && !state.isHubSearching -> {
                EmptyState(
                    icon = Icons.Filled.Extension,
                    title = stringResource(R.string.skills_hub_empty_results),
                )
            }

            state.hubResults.isNotEmpty() -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                ) {
                    item(key = "hub_results_label") {
                        SettingsGroupLabel(stringResource(R.string.skills_hub_results_count, state.hubResults.size))
                    }
                    itemsIndexed(
                        state.hubResults,
                        key = { index, hubSkill -> "${hubSkill.name}:${hubSkill.source ?: "unknown"}:$index" },
                    ) { index, hubSkill ->
                        HubSkillCard(
                            first = index == 0,
                            last = index == state.hubResults.lastIndex,
                            hubSkill = hubSkill,
                            onInstall = { onInstall(hubSkill.name) },
                            isInstalling = isInstalling && installingSkillName == hubSkill.name,
                            onClick = {
                                hubShowDetail = hubSkill
                                hubSkill.identifier?.let { onPreviewHubSkill(it) }
                            },
                        )
                    }
                }
            }

            else -> {
                HubLandingView(
                    state = state,
                    onLoadHubSources = onLoadHubSources,
                    onInstall = onInstall,
                    isInstalling = isInstalling,
                    installingSkillName = installingSkillName,
                    onOpenDetail = { skill ->
                        hubShowDetail = skill
                        skill.identifier?.let { onPreviewHubSkill(it) }
                    },
                )
            }
        }
    }

    hubShowDetail?.let { hubSkill ->
        val previewReady = state.hubPreviewIdentifier == hubSkill.identifier
        val fullContent =
            if (previewReady) {
                state.hubPreviewContent ?: hubSkill.description
            } else {
                hubSkill.description
            }
        DetailDialog(
            title = hubSkill.name,
            rows =
                listOf(
                    DetailRow(stringResource(R.string.detail_dialog_category), hubSkill.category),
                    DetailRow(stringResource(R.string.detail_dialog_source), hubSkill.source),
                    DetailRow(stringResource(R.string.detail_dialog_description), fullContent),
                    DetailRow(stringResource(R.string.detail_dialog_tags), hubSkill.tags.orEmpty().joinToString(", ")),
                    DetailRow(stringResource(R.string.detail_dialog_trust_level), hubSkill.trustLevel),
                ),
            actions = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (previewReady && state.isHubPreviewing) {
                        CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                    }
                    hubSkill.identifier?.let { identifier ->
                        SkillScanSection(
                            scanResult =
                                state.hubScanResult.takeIf {
                                    state.hubScanIdentifier == identifier
                                },
                            isScanning =
                                state.isHubScanning &&
                                    state.hubScanIdentifier == identifier,
                            scanError =
                                state.hubScanError.takeIf {
                                    state.hubScanIdentifier == identifier
                                },
                            onScan = { onScanHubSkill(identifier) },
                        )
                    }
                }
            },
            onDismiss = {
                hubShowDetail = null
                onClearHubScan()
            },
        )
    }
}

// ── Hub Landing (sources + featured) ───────────────────────────────────

@Composable
private fun HubLandingView(
    state: SkillsUiState,
    onLoadHubSources: () -> Unit,
    onInstall: (String) -> Unit,
    isInstalling: Boolean,
    installingSkillName: String?,
    onOpenDetail: (HubSkill) -> Unit,
) {
    when {
        state.isHubSourcesLoading -> {
            SkeletonListState()
        }

        state.hubSourcesError != null -> {
            ErrorState(
                message = state.hubSourcesError,
                onRetry = onLoadHubSources,
            )
        }

        state.hubSources.isEmpty() && state.hubFeatured.isEmpty() -> {
            EmptyState(
                icon = Icons.Filled.Extension,
                title = stringResource(R.string.skills_hub_landing_hint),
            )
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                if (state.hubSources.isNotEmpty()) {
                    item(key = "hub_sources_header") {
                        Column {
                            SettingsGroupLabel(stringResource(R.string.skills_hub_sources_title))
                            Row(
                                modifier =
                                    Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                state.hubSources.forEach { source ->
                                    SourceChip(source = source)
                                }
                            }
                        }
                    }
                }

                if (state.hubFeatured.isNotEmpty()) {
                    item(key = "hub_featured_header") {
                        SettingsGroupLabel(stringResource(R.string.skills_hub_featured_title))
                    }
                    itemsIndexed(
                        state.hubFeatured,
                        key = { index, hubSkill ->
                            "featured:${hubSkill.name}:${hubSkill.source ?: "unknown"}:$index"
                        },
                    ) { index, hubSkill ->
                        HubSkillCard(
                            first = index == 0,
                            last = index == state.hubFeatured.lastIndex,
                            hubSkill = hubSkill,
                            onInstall = { onInstall(hubSkill.name) },
                            isInstalling =
                                isInstalling && installingSkillName == hubSkill.name,
                            onClick = { onOpenDetail(hubSkill) },
                        )
                    }
                    item(key = "hub_featured_footnote") {
                        SettingsFootnote(stringResource(R.string.skills_hub_featured_subtitle))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceChip(source: SkillHubSource) {
    val statusColors = LocalHermesStatusColors.current
    val label =
        buildString {
            append(source.label ?: source.id)
            when {
                source.rateLimited == true -> {
                    append(" · ")
                    append(stringResource(R.string.skills_hub_source_rate_limited))
                }

                source.available == false -> {
                    append(" · ")
                    append(stringResource(R.string.skills_hub_source_offline))
                }

                source.searchable == false -> {
                    append(" · ")
                    append(stringResource(R.string.skills_hub_source_via_index))
                }
            }
        }
    val color =
        when {
            source.rateLimited == true -> statusColors.warning
            source.available == false -> statusColors.error
            else -> MaterialTheme.colorScheme.primary
        }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

// ── Hub security scan ──────────────────────────────────────────────────

@Composable
private fun SkillScanSection(
    scanResult: SkillScanResponse?,
    isScanning: Boolean,
    scanError: String?,
    onScan: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onScan,
            enabled = !isScanning,
            shapes = ButtonDefaults.shapes(),
        ) {
            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.skills_hub_scan))
            }
        }

        when {
            isScanning -> {
                Unit
            }

            scanError != null -> {
                Surface(
                    color = LocalHermesStatusColors.current.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = scanError,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            scanResult != null -> {
                ScanResultCard(result = scanResult)
            }
        }
    }
}

@Composable
private fun ScanResultCard(result: SkillScanResponse) {
    val statusColors = LocalHermesStatusColors.current
    val verdictColor =
        when (result.verdict) {
            "safe" -> statusColors.success
            "dangerous" -> statusColors.error
            else -> statusColors.warning
        }
    val policyLabel =
        when (result.policy) {
            "allow" -> stringResource(R.string.skills_hub_scan_policy_allow)
            "ask" -> stringResource(R.string.skills_hub_scan_policy_ask)
            "block" -> stringResource(R.string.skills_hub_scan_policy_block)
            else -> result.policy.orEmpty()
        }
    val policyColor =
        when (result.policy) {
            "allow" -> statusColors.success
            "ask" -> statusColors.warning
            "block" -> statusColors.error
            else -> statusColors.neutral
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Verdict header ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    tint = verdictColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.skills_hub_scan_verdict_label, result.verdict.orEmpty()),
                    style = MaterialTheme.typography.titleSmall,
                    color = verdictColor,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = policyColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = policyLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = policyColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            // ── Trust + finding count ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!result.trustLevel.isNullOrBlank()) {
                    Text(
                        text = result.trustLevel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.skills_hub_scan_findings_count,
                            result.findings.size,
                            result.findings.size,
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Severity tally ──
            val severityColors =
                mapOf(
                    "critical" to statusColors.error,
                    "high" to statusColors.error,
                    "medium" to statusColors.warning,
                    "low" to statusColors.neutral,
                )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("critical", "high", "medium", "low").forEach { sev ->
                    val count = result.severityCounts[sev] ?: 0
                    if (count > 0) {
                        val sevColor = severityColors[sev] ?: statusColors.neutral
                        Surface(
                            color = sevColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                text = "$count $sev",
                                style = MaterialTheme.typography.labelSmall,
                                color = sevColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                if (result.findings.isEmpty()) {
                    Text(
                        text = stringResource(R.string.skills_hub_scan_no_findings),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColors.success,
                    )
                }
            }

            // ── Policy reason ──
            if (!result.policyReason.isNullOrBlank()) {
                Text(
                    text = result.policyReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Findings ──
            if (result.findings.isNotEmpty()) {
                HorizontalDivider()
                result.findings.forEach { finding ->
                    ScanFindingRow(finding = finding)
                }
            }
        }
    }
}

@Composable
private fun ScanFindingRow(finding: SkillScanFinding) {
    val statusColors = LocalHermesStatusColors.current
    val severityColor =
        when (finding.severity) {
            "critical", "high" -> statusColors.error
            "medium" -> statusColors.warning
            else -> statusColors.neutral
        }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                color = severityColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = finding.severity,
                    style = MaterialTheme.typography.labelSmall,
                    color = severityColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(
                text = finding.category,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val location =
                buildString {
                    finding.file?.let { append(it) }
                    finding.line?.let { append(":").append(it) }
                }
            if (location.isNotEmpty()) {
                Text(
                    text = location,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        finding.description?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Hub Skill Card ─────────────────────────────────────────────────────

@Composable
internal fun HubSkillCard(
    hubSkill: HubSkill,
    onInstall: () -> Unit,
    isInstalling: Boolean,
    onClick: () -> Unit,
    first: Boolean = true,
    last: Boolean = true,
) {
    SettingsListItem(first = first, last = last) {
        SettingsRow(
            title = hubSkill.name,
            subtitle = hubSkill.description?.takeIf { it.isNotBlank() } ?: hubSkill.category,
            onClick = onClick,
            trailing = {
                if (isInstalling) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = onInstall) { Text(stringResource(R.string.skills_hub_install)) }
                }
            },
        )
    }
}

// ── Skill Preview Dialog ───────────────────────────────────────────────

@Composable
fun SkillPreviewDialog(
    skillName: String,
    isLoading: Boolean,
    content: String?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
        ) {
            HermesScaffold(
                title = { Text(stringResource(R.string.skills_preview_title, skillName)) },
                navigationIcon = NavIcon.Back(onBack = onDismiss),
            ) { padding ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize(),
                ) {
                    when {
                        isLoading -> {
                            LoadingState()
                        }

                        content != null -> {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(16.dp)
                                        .verticalScroll(rememberScrollState()),
                            ) {
                                Text(
                                    text = content,
                                    style =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                )
                            }
                        }

                        else -> {
                            EmptyState(
                                icon = Icons.Filled.Extension,
                                title = stringResource(R.string.skills_preview_empty),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Skill Editor Dialog (existing, preserved unchanged) ────────────────

@Composable
fun SkillEditorDialog(
    skillName: String,
    isLoading: Boolean,
    initialContent: String?,
    isSaving: Boolean,
    saveSuccess: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    onClearSaveSuccess: () -> Unit,
) {
    var contentText by remember(initialContent) { mutableStateOf(initialContent.orEmpty()) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    val hasChanges =
        remember(initialContent, contentText) {
            val original = initialContent.orEmpty()
            original != contentText
        }

    LaunchedEffect(saveSuccess) {
        if (saveSuccess) {
            onClearSaveSuccess()
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = {
            if (hasChanges) {
                showDiscardConfirm = true
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
        ) {
            HermesScaffold(
                title = { Text(stringResource(R.string.skills_edit_title, skillName)) },
                navigationIcon =
                    NavIcon.Back(
                        onBack = {
                            if (hasChanges) {
                                showDiscardConfirm = true
                            } else {
                                onDismiss()
                            }
                        },
                    ),
                actions = {
                    if (!isLoading) {
                        IconButton(
                            onClick = { onSave(contentText) },
                            enabled = !isSaving,
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.fillMaxSize(0.6f),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Save,
                                    contentDescription = stringResource(R.string.content_desc_save_changes),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize(),
                ) {
                    when {
                        isLoading -> {
                            LoadingState()
                        }

                        else -> {
                            HermesTextField(
                                value = contentText,
                                onValueChange = { contentText = it },
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                textStyle =
                                    TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                placeholder = {
                                    Text(stringResource(R.string.skills_content_placeholder))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.skills_discard_title)) },
            text = { Text(stringResource(R.string.skills_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        onDismiss()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.action_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
