package com.m57.hermescontrol.ui.model

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.data.model.MainModelAssignment
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SettingsFootnote
import com.m57.hermescontrol.ui.common.SettingsGroup
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.common.SettingsRow
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.rememberHasLoaded
import com.m57.hermescontrol.ui.model.components.MoaConfigDialog
import com.m57.hermescontrol.ui.model.components.ModelPickerDialog
import com.m57.hermescontrol.ui.model.components.ModelVisibilityEditor
import com.m57.hermescontrol.ui.model.components.rememberModelVisibility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ModelViewModel = viewModel { ModelViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var expandedProviderSlug by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val visibility = rememberModelVisibility()
    var showVisibilityEditor by remember { mutableStateOf(false) }
    val visibleProviders =
        remember(state.providers, visibility.value) { visibility.value.filterProviders(state.providers) }
    val visiblePinnedModels =
        remember(state.pinnedModels, visibility.value) { visibility.value.filterPinned(state.pinnedModels) }

    val filteredProviders =
        remember(query, visibleProviders) {
            visibleProviders.filter { provider ->
                provider.name.contains(query, ignoreCase = true) ||
                    provider.slug.contains(query, ignoreCase = true) ||
                    provider.models.orEmpty().any { model ->
                        model.contains(query, ignoreCase = true)
                    }
            }
        }

    LaunchedEffect(Unit) {
        viewModel.loadAll()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)
    val hasLoaded = rememberHasLoaded(state.isLoading)

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_models)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) } ?: NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = onOpenDrawer != null,
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadAll(refresh = true) },
    ) {
        when {
            state.isLoading && state.providers.isEmpty() && !hasLoaded -> {
                SkeletonListState()
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadAll() },
                )
            }

            state.providers.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.model_empty_title),
                    subtitle = stringResource(R.string.model_empty_desc),
                    actionLabel = stringResource(R.string.empty_action_configure_model),
                    onAction = { NavigationController.navigateTo(SettingsConnection) },
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp),
                ) {
                    // ── Model settings ──
                    item("model-settings") {
                        Column {
                            ModelSettingsSection(
                                mainProvider = state.mainModelProvider,
                                mainModel = state.mainModelModel,
                                auxTasks = state.auxTasks,
                                moaEnabled = state.moaConfig != null,
                                moaSummary =
                                    if (state.moaConfig != null) {
                                        val mc = state.moaConfig!!
                                        "${mc.reference_models.size} ref · ${mc.aggregator.provider}/${shortModelName(
                                            mc.aggregator.model,
                                        )}"
                                    } else {
                                        ""
                                    },
                                onSetMainModel = { viewModel.openMainModelPicker() },
                                onConfigureAux = { viewModel.openAuxDialog() },
                                onConfigureMoa = { viewModel.openMoaDialog() },
                                onConfigureVisibility = { showVisibilityEditor = true },
                            )
                            if (visibleProviders.isEmpty() && visiblePinnedModels.isEmpty()) {
                                SettingsFootnote(stringResource(R.string.model_visibility_empty))
                            }
                        }
                    }

                    // ── Pinned models ──
                    if (visiblePinnedModels.isNotEmpty()) {
                        item("pinned") {
                            Column {
                                SettingsGroupLabel(stringResource(R.string.model_section_pinned))
                                PinnedSectionCard(
                                    pinnedModels = visiblePinnedModels,
                                    activeProfile = state.activeProfile,
                                    onModelClick = { slug, model -> viewModel.selectModel(slug, model) },
                                    onUnpin = { slug, model -> viewModel.unpinModel(slug, model) },
                                )
                            }
                        }
                    }

                    // ── All models: one search row, then providers as one continuous group ──
                    if (visibleProviders.isNotEmpty()) {
                        item("models-label") { SettingsGroupLabel(stringResource(R.string.model_group_models)) }
                        item("search") {
                            SearchBar(
                                query = query,
                                onQueryChange = { query = it },
                                placeholder = stringResource(R.string.model_search_placeholder),
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    }
                    itemsIndexed(filteredProviders, key = { _, provider -> provider.slug }) { index, provider ->
                        val isExpanded = expandedProviderSlug == provider.slug
                        val isCurrent = provider.is_current == true

                        ProviderCard(
                            provider = provider,
                            isExpanded = isExpanded,
                            isCurrent = isCurrent,
                            query = query,
                            activeProfile = state.activeProfile,
                            pinnedModels = state.pinnedModels,
                            onToggleExpand = {
                                expandedProviderSlug = if (isExpanded) null else provider.slug
                            },
                            onModelClick = { slug, model -> viewModel.selectModel(slug, model) },
                            onPin = { slug, model -> viewModel.pinModel(slug, model) },
                            onUnpin = { slug, model -> viewModel.unpinModel(slug, model) },
                            first = index == 0,
                            last = index == filteredProviders.lastIndex,
                        )
                    }
                }
            }
        }
    }

    // ── Dialogs ──

    if (showVisibilityEditor) {
        ModelVisibilityEditor(state.providers, visibility, onDismiss = { showVisibilityEditor = false })
    }

    // Expensive model confirmation
    if (state.modelPickerConfirmMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissModelPickerConfirm() },
            title = { Text(stringResource(R.string.model_expensive_title)) },
            text = { Text(state.modelPickerConfirmMessage!!) },
            confirmButton = {
                Button(onClick = { viewModel.confirmModelPickerExpensive() }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.model_confirm_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissModelPickerConfirm() }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Main model picker dialog
    if (state.showMainModelPicker) {
        ModelPickerDialog(
            providers = state.providers,
            title = stringResource(R.string.model_set_main),
            currentModel = MainModelAssignment(state.mainModelProvider, state.mainModelModel),
            isLoading = state.isLoading,
            errorMessage = state.modelOptionsError,
            onRefresh = viewModel::refreshModelOptions,
            pinnedModels = state.pinnedModels,
            onPinToggle = { provider, model -> viewModel.togglePinModel(provider, model) },
            onSelect = { provider, model ->
                viewModel.setMainModel(provider, model)
            },
            onDismiss = { viewModel.closeMainModelPicker() },
        )
    }

    // Auxiliary tasks dialog
    if (state.showAuxDialog) {
        AuxTasksDialog(
            auxTasks = state.auxTasks,
            busy = state.modelPickerBusy,
            onSetTask = { task -> viewModel.openAuxModelPicker(task) },
            onResetAll = { viewModel.resetAllAuxTasks() },
            onDismiss = { viewModel.closeAuxDialog() },
        )
    }

    // Auxiliary model picker dialog
    if (state.showAuxModelPicker) {
        ModelPickerDialog(
            providers = state.providers,
            title = stringResource(R.string.model_set_aux, state.auxPickerTask),
            currentModel =
                state.auxTasks.find { it.task == state.auxPickerTask && it.provider != "auto" }?.let {
                    MainModelAssignment(it.provider, it.model)
                },
            isLoading = state.isLoading,
            errorMessage = state.modelOptionsError,
            onRefresh = viewModel::refreshModelOptions,
            pinnedModels = state.pinnedModels,
            onPinToggle = { provider, model -> viewModel.togglePinModel(provider, model) },
            onSelect = { provider, model ->
                viewModel.setAuxTask(state.auxPickerTask, provider, model)
            },
            onDismiss = { viewModel.closeAuxModelPicker() },
        )
    }

    // MOA config dialog
    if (state.showMoaDialog && state.moaConfig != null) {
        MoaConfigDialog(
            config = state.moaConfig!!,
            providers = state.providers,
            busy = state.modelPickerBusy,
            onSave = { viewModel.saveMoaConfig(it) },
            onDismiss = { viewModel.closeMoaDialog() },
        )
    }
}

// ────────────────────────────────────────────────────────────────────
// Model Settings Panel
// ────────────────────────────────────────────────────────────────────

@Composable
internal fun ModelSettingsSection(
    mainProvider: String,
    mainModel: String,
    auxTasks: List<*>?,
    moaEnabled: Boolean,
    moaSummary: String,
    onSetMainModel: () -> Unit,
    onConfigureAux: () -> Unit,
    onConfigureMoa: () -> Unit,
    onConfigureVisibility: (() -> Unit)? = null,
) {
    val auxKeys = AUX_TASKS.map { it.key }.toSet()
    val overrides =
        auxTasks
            .orEmpty()
            .filterIsInstance<com.m57.hermescontrol.data.model.AuxiliaryTaskAssignment>()
            .count { it.task in auxKeys && it.provider.isNotBlank() && it.provider != "auto" }
    SettingsGroupLabel(stringResource(R.string.model_settings))
    SettingsGroup {
        row {
            SettingsRow(
                title = stringResource(R.string.model_main_label),
                subtitle =
                    if (mainProvider.isNotBlank()) {
                        "$mainProvider · $mainModel"
                    } else {
                        stringResource(
                            R.string.model_value_unset,
                        )
                    },
                chevron = true,
                onClick = onSetMainModel,
            )
        }
        row {
            SettingsRow(
                title = stringResource(R.string.model_aux_tasks_label),
                subtitle =
                    if (overrides > 0) {
                        stringResource(R.string.model_aux_summary_overrides, overrides, AUX_TASKS.size - overrides)
                    } else {
                        stringResource(R.string.model_aux_summary_all_auto, AUX_TASKS.size)
                    },
                chevron = true,
                onClick = onConfigureAux,
            )
        }
        // An unavailable advanced feature is omitted rather than shown as a disabled row.
        if (moaEnabled) {
            row {
                SettingsRow(
                    title = stringResource(R.string.model_moa_label),
                    subtitle = moaSummary,
                    chevron = true,
                    onClick = onConfigureMoa,
                )
            }
        }
        onConfigureVisibility?.let { open ->
            row {
                SettingsRow(
                    title = stringResource(R.string.model_visibility_row),
                    chevron = true,
                    onClick = open,
                )
            }
        }
    }
    SettingsFootnote(stringResource(R.string.model_new_sessions))
}

// ────────────────────────────────────────────────────────────────────
// Auxiliary Tasks Dialog
// ────────────────────────────────────────────────────────────────────

@Composable
private fun AuxTasksDialog(
    auxTasks: List<com.m57.hermescontrol.data.model.AuxiliaryTaskAssignment>,
    busy: Boolean,
    onSetTask: (String) -> Unit,
    onResetAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_aux_tasks_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.model_aux_tasks_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))

                AUX_TASKS.forEach { info ->
                    val cur = auxTasks.find { it.task == info.key }
                    val isAuto = cur == null || cur.provider.isBlank() || cur.provider == "auto"

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(info.labelRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = stringResource(info.hintRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text =
                                    if (isAuto) {
                                        stringResource(R.string.model_aux_auto)
                                    } else {
                                        val model =
                                            cur.model.ifBlank {
                                                stringResource(
                                                    R.string.model_aux_provider_default,
                                                )
                                            }
                                        "${cur.provider} · $model"
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { onSetTask(info.key) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(R.string.action_change), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onResetAll,
                    enabled = !busy,
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.model_reset_all_auto))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(R.string.action_close)) }
        },
    )
}

// ────────────────────────────────────────────────────────────────────
// MOA Config Dialog
// ────────────────────────────────────────────────────────────────────

// ────────────────────────────────────────────────────────────────────
// Model Picker Dialog
// ────────────────────────────────────────────────────────────────────

// ────────────────────────────────────────────────────────────────────
// Pinned Section Card — extracted from existing screen
// ────────────────────────────────────────────────────────────────────

@Composable
internal fun PinnedSectionCard(
    pinnedModels: List<com.m57.hermescontrol.data.model.PinnedModel>,
    activeProfile: com.m57.hermescontrol.data.model.ProfileInfo?,
    onModelClick: (String, String) -> Unit,
    onUnpin: (String, String) -> Unit,
) {
    SettingsGroup(Modifier.animateContentSize()) {
        pinnedModels.forEach { pinned ->
            row {
                val active =
                    activeProfile?.provider == pinned.providerSlug && activeProfile.model == pinned.modelName
                SettingsRow(
                    title = pinned.modelName,
                    subtitle = pinned.providerSlug,
                    leading = { RadioButton(selected = active, onClick = null) },
                    onClick = { onModelClick(pinned.providerSlug, pinned.modelName) },
                    trailing = {
                        IconButton(onClick = { onUnpin(pinned.providerSlug, pinned.modelName) }) {
                            Icon(Icons.Filled.PushPin, stringResource(R.string.content_desc_unpin_model))
                        }
                    },
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// Provider Card — extracted from existing screen
// ────────────────────────────────────────────────────────────────────

@Composable
internal fun ProviderCard(
    provider: com.m57.hermescontrol.data.model.ModelProvider,
    isExpanded: Boolean,
    isCurrent: Boolean,
    query: String,
    activeProfile: com.m57.hermescontrol.data.model.ProfileInfo?,
    pinnedModels: List<com.m57.hermescontrol.data.model.PinnedModel>,
    onToggleExpand: () -> Unit,
    onModelClick: (String, String) -> Unit,
    onPin: (String, String) -> Unit,
    onUnpin: (String, String) -> Unit,
    first: Boolean = true,
    last: Boolean = true,
) {
    SettingsGroup(Modifier.animateContentSize(), first = first, last = last) {
        row {
            SettingsRow(
                title = provider.name,
                subtitle =
                    listOfNotNull(
                        provider.slug,
                        if (isCurrent) stringResource(R.string.model_status_current) else null,
                    ).joinToString(" · "),
                onClick = onToggleExpand,
                status = {
                    Icon(
                        if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        provider.warning?.takeIf { it.isNotBlank() }?.let { warning ->
            row {
                SettingsFootnote(warning, Modifier.padding(bottom = 12.dp))
            }
        }
        if (isExpanded) {
            val models = provider.models.orEmpty().filter { query.isBlank() || it.contains(query, ignoreCase = true) }
            if (models.isEmpty()) {
                row {
                    SettingsRow(title = stringResource(R.string.model_no_models), enabled = false)
                }
            }
            models.forEach { model ->
                row {
                    val active = activeProfile?.provider == provider.slug && activeProfile.model == model
                    val caps = provider.capabilities?.get(model)
                    val hint =
                        when {
                            caps?.reasoning == false -> stringResource(R.string.model_hint_no_reasoning)
                            caps?.can_disable_reasoning == false -> stringResource(R.string.model_hint_reasoning_always)
                            else -> null
                        }
                    val pinned = pinnedModels.any { it.providerSlug == provider.slug && it.modelName == model }
                    SettingsRow(
                        title = model,
                        subtitle = hint,
                        maxLines = 3,
                        leading = { RadioButton(selected = active, onClick = null) },
                        onClick = { onModelClick(provider.slug, model) },
                        trailing = {
                            IconButton(
                                onClick = {
                                    if (pinned) {
                                        onUnpin(
                                            provider.slug,
                                            model,
                                        )
                                    } else {
                                        onPin(provider.slug, model)
                                    }
                                },
                            ) {
                                Icon(
                                    if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                    stringResource(
                                        if (pinned) {
                                            R.string.content_desc_unpin_model
                                        } else {
                                            R.string.content_desc_pin_model
                                        },
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Shorten a model name for display. */
private fun shortModelName(model: String): String {
    val parts = model.split("/")
    return parts.lastOrNull() ?: model
}
