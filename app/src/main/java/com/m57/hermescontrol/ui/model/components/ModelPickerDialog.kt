package com.m57.hermescontrol.ui.model.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.MainModelAssignment
import com.m57.hermescontrol.data.model.ModelCapabilities
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.CompactListRow
import com.m57.hermescontrol.ui.common.InListSectionHeaderPadding
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SectionHeader

/** Shared picker for default, auxiliary, profile, and conversation model changes. */
@Composable
fun ModelPickerDialog(
    providers: List<ModelProvider>,
    title: String,
    subtitle: String? = null,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onRefresh: (() -> Unit)? = null,
    pinnedModels: List<PinnedModel> = emptyList(),
    onPinToggle: ((provider: String, model: String) -> Unit)? = null,
    currentModel: MainModelAssignment? = null,
    selectionEnabled: Boolean = true,
    onSelect: (provider: String, model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val visibility = rememberModelVisibility()
    var editing by rememberSaveable { mutableStateOf(false) }
    var pickerQuery by rememberSaveable { mutableStateOf("") }
    val spacing = LocalSpacing.current
    ModelCatalogSheet(
        title = if (editing) stringResource(R.string.model_visibility_title) else title,
        subtitle = if (editing) null else subtitle,
        onBack =
            if (editing) {
                { editing = false }
            } else {
                null
            },
        onDismiss = onDismiss,
    ) {
        if (editing) {
            ModelVisibilityContent(providers, visibility, onClose = { editing = false })
        } else {
            val visibleProviders = remember(providers, visibility.value) { visibility.value.filterProviders(providers) }
            val visiblePinned = remember(pinnedModels, visibility.value) { visibility.value.filterPinned(pinnedModels) }
            val providerBySlug = remember(providers) { providers.associateBy { it.slug } }
            val groups = remember(visibleProviders, pickerQuery) { matchingModelGroups(visibleProviders, pickerQuery) }
            val pinned =
                remember(visiblePinned, pickerQuery, providerBySlug) {
                    visiblePinned.filter {
                        matchesModel(
                            it.providerSlug,
                            providerBySlug[it.providerSlug]?.name.orEmpty(),
                            it.modelName,
                            pickerQuery,
                        )
                    }
                }
            val current =
                currentModel?.takeIf { it.provider.isNotBlank() && it.model.isNotBlank() }
            val currentInResults =
                current != null && (
                    groups.any { (provider, models) -> current.provider == provider.slug && current.model in models } ||
                        pinned.any { it.providerSlug == current.provider && it.modelName == current.model }
                )

            SearchBar(
                query = pickerQuery,
                onQueryChange = { pickerQuery = it },
                placeholder = stringResource(R.string.model_picker_search),
            )
            if (onRefresh != null) {
                TextButton(onClick = onRefresh, enabled = !isLoading) {
                    Text(stringResource(R.string.model_picker_refresh))
                }
            }
            errorMessage?.let { InlineNotice(it, tone = NoticeTone.Error) }
            val hasChoices = visibleProviders.isNotEmpty() || visiblePinned.isNotEmpty()
            if (isLoading && hasChoices) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            LazyColumn(Modifier.weight(1f)) {
                if (current != null && !currentInResults) {
                    item("current") {
                        SectionHeader(
                            stringResource(R.string.model_picker_current),
                            contentPadding = InListSectionHeaderPadding,
                        )
                        ModelCatalogRow(
                            model = current.model,
                            providerLabel = providerBySlug[current.provider]?.name ?: current.provider,
                            status =
                                stringResource(
                                    if (providers.any {
                                            it.slug == current.provider && current.model in it.models.orEmpty()
                                        }
                                    ) {
                                        R.string.model_picker_current_outside_results
                                    } else {
                                        R.string.model_picker_current_unavailable
                                    },
                                ),
                        )
                    }
                }
                if (isLoading && !hasChoices) {
                    item("loading") {
                        LoadingState(
                            subtitle = stringResource(R.string.model_picker_loading),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (groups.isEmpty() && pinned.isEmpty()) {
                    item("empty") {
                        val message =
                            when {
                                hasChoices -> R.string.model_picker_no_matches

                                providers.any {
                                    !it.models.isNullOrEmpty()
                                } || pinnedModels.isNotEmpty() -> R.string.model_visibility_empty

                                else -> R.string.model_picker_no_models
                            }
                        Text(
                            stringResource(message),
                            Modifier.padding(vertical = spacing.md),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (pinned.isNotEmpty()) {
                    item("pinned-header") {
                        SectionHeader(
                            stringResource(R.string.sessions_pinned_indicator),
                            contentPadding = InListSectionHeaderPadding,
                        )
                    }
                    items(pinned, key = { "pinned:${it.providerSlug}::${it.modelName}" }) {
                        val provider = providerBySlug[it.providerSlug]
                        ModelChoiceRow(
                            provider = it.providerSlug,
                            model = it.modelName,
                            providerLabel = provider?.name ?: it.providerSlug,
                            capabilities = provider?.capabilities?.get(it.modelName),
                            current = current,
                            pinned = true,
                            enabled = selectionEnabled,
                            onPinToggle = onPinToggle,
                            onSelect = onSelect,
                        )
                    }
                }
                groups.forEach { (provider, models) ->
                    item("header:${provider.slug}") {
                        SectionHeader(provider.name, contentPadding = InListSectionHeaderPadding)
                    }
                    items(models, key = { model -> "${provider.slug}::$model" }) { model ->
                        ModelChoiceRow(
                            provider = provider.slug,
                            model = model,
                            capabilities = provider.capabilities?.get(model),
                            current = current,
                            pinned = PinnedModel(provider.slug, model) in visiblePinned,
                            enabled = selectionEnabled,
                            onPinToggle = onPinToggle,
                            onSelect = onSelect,
                        )
                    }
                }
            }
            TextButton(
                onClick = { editing = true },
                enabled = providers.any { !it.models.isNullOrEmpty() },
            ) {
                Text(stringResource(R.string.model_visibility_title))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelCatalogSheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val spacing = LocalSpacing.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = onBack == null),
    ) {
        BackHandler(enabled = onBack != null) { onBack?.invoke() }
        Column(
            Modifier
                .fillMaxHeight(0.93f)
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = spacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onBack == null) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun ModelChoiceRow(
    provider: String,
    model: String,
    capabilities: ModelCapabilities?,
    current: MainModelAssignment?,
    pinned: Boolean,
    enabled: Boolean,
    onPinToggle: ((String, String) -> Unit)?,
    onSelect: (String, String) -> Unit,
    providerLabel: String? = null,
) {
    val selected = current?.provider == provider && current.model == model
    val pinTint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val reasoning =
        when {
            capabilities?.reasoning == false -> stringResource(R.string.model_picker_reasoning_unsupported)
            capabilities?.can_disable_reasoning == false -> stringResource(R.string.model_picker_reasoning_required)
            else -> null
        }
    val status =
        listOfNotNull(
            if (selected) stringResource(R.string.model_picker_current) else null,
            reasoning,
        ).joinToString(" · ")
    ModelCatalogRow(
        model = model,
        modifier =
            Modifier.selectable(
                selected,
                enabled = enabled,
                role = Role.RadioButton,
            ) { onSelect(provider, model) },
        providerLabel = providerLabel,
        status = status.ifEmpty { null },
        trailing = {
            if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            if (onPinToggle != null) {
                IconButton(onClick = { onPinToggle(provider, model) }) {
                    Icon(
                        imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription =
                            stringResource(
                                if (pinned) R.string.model_picker_unpin else R.string.model_picker_pin,
                            ),
                        tint = pinTint,
                    )
                }
            }
        },
    )
}

@Composable
internal fun ModelCatalogRow(
    model: String,
    modifier: Modifier = Modifier,
    providerLabel: String? = null,
    status: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    CompactListRow(
        modifier = modifier,
        trailing = { trailing?.invoke() },
    ) {
        Text(modelDisplayName(model), style = MaterialTheme.typography.bodyLarge)
        Text(
            if (providerLabel == null) model else "$providerLabel · $model",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        status?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun matchingModelGroups(
    providers: List<ModelProvider>,
    query: String,
): List<Pair<ModelProvider, List<String>>> =
    providers.mapNotNull { provider ->
        val models =
            provider.models.orEmpty().distinct().filter {
                matchesModel(
                    provider.slug,
                    provider.name,
                    it,
                    query,
                )
            }
        models.takeIf { it.isNotEmpty() }?.let { provider to it }
    }

private fun matchesModel(
    slug: String,
    providerName: String,
    model: String,
    query: String,
): Boolean {
    val filter = query.trim()
    return slug.contains(filter, ignoreCase = true) ||
        providerName.contains(filter, ignoreCase = true) ||
        model.contains(filter, ignoreCase = true) ||
        modelDisplayName(model).contains(filter, ignoreCase = true)
}

private val modelWordSeparator = Regex("[-_]+")

private fun modelDisplayName(model: String): String =
    model
        .substringAfterLast('/')
        .split(modelWordSeparator)
        .joinToString(" ") { word ->
            when (word.lowercase()) {
                "gpt", "glm", "llm", "moe", "ai" -> word.uppercase()
                else -> word.replaceFirstChar { it.titlecase() }
            }
        }.ifBlank { model }
