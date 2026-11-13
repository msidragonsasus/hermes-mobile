package com.m57.hermescontrol.ui.model.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.SearchBar

/**
 * Reusable model picker used by both the global model screen and the
 * in-session `/model` hot-swap (issue #589). Selecting a provider/model pair
 * invokes [onSelect]; the consumer decides what to do with it (global
 * `config.yaml` assignment vs. a session-scoped `/model` slash command).
 *
 * [pinnedModels] renders a pinned section at the top (mirrors the global model
 * screen's pin section) so frequently-used models are one tap away.
 * [onPinToggle] allows pinning and unpinning models directly inside the dialog.
 * Per-model reasoning capabilities (issue #946) are surfaced as a small hint
 * when `can_disable_reasoning == false` ("reasoning always on") or
 * `reasoning == false` ("no reasoning").
 */
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
    onSelect: (provider: String, model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pickerQuery by remember { mutableStateOf("") }
    val visibility = rememberModelVisibility()
    var showVisibilityEditor by remember { mutableStateOf(false) }
    val visibleProviders = remember(providers, visibility.value) { visibility.value.filterProviders(providers) }
    val visiblePinnedModels = remember(pinnedModels, visibility.value) { visibility.value.filterPinned(pinnedModels) }
    if (showVisibilityEditor) {
        ModelVisibilityEditor(providers, visibility, onDismiss = { showVisibilityEditor = false })
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // The root wraps the Surface exactly so outside taps still dismiss. Size
        // follows the window constraints after reported safe-drawing insets:
        // phone width minus an edge margin, capped on tablets. Actual IME inset
        // delivery and extreme small-window layouts require device verification.
        BoxWithConstraints(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Surface(
                modifier =
                    Modifier
                        .width((maxWidth - 32.dp).coerceIn(0.dp, 560.dp))
                        .heightIn(max = (maxHeight - 48.dp).coerceIn(0.dp, 840.dp)),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column {
                    ModelPickerHeader(title = title, subtitle = subtitle, onDismiss = onDismiss)
                    // The body uses the height left by header and footer rather
                    // than reserving a fixed minimum that could overflow the window.
                    Column(modifier = Modifier.weight(1f, fill = false).padding(horizontal = 24.dp)) {
                        if (onRefresh != null) {
                            TextButton(
                                onClick = onRefresh,
                                enabled = !isLoading,
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Text(
                                    text = stringResource(R.string.model_picker_refresh),
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                        if (errorMessage != null) {
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        if (isLoading && (visibleProviders.isNotEmpty() || visiblePinnedModels.isNotEmpty())) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        if (isLoading && visibleProviders.isEmpty() && visiblePinnedModels.isEmpty()) {
                            LoadingState(
                                subtitle = stringResource(R.string.model_picker_loading),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else if (visibleProviders.isEmpty() && visiblePinnedModels.isEmpty()) {
                            Text(
                                text =
                                    stringResource(
                                        if (providers.isNotEmpty() || pinnedModels.isNotEmpty()) {
                                            R.string.model_visibility_empty
                                        } else {
                                            R.string.model_picker_no_models
                                        },
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            val filteredPinned =
                                remember(pickerQuery, visiblePinnedModels) {
                                    if (pickerQuery.isBlank()) {
                                        visiblePinnedModels
                                    } else {
                                        visiblePinnedModels.filter {
                                            it.modelName.contains(pickerQuery, ignoreCase = true) ||
                                                it.providerSlug.contains(pickerQuery, ignoreCase = true)
                                        }
                                    }
                                }

                            val pinnedSet =
                                remember(visiblePinnedModels) {
                                    visiblePinnedModels.map { "${it.providerSlug}:${it.modelName}" }.toSet()
                                }

                            val providerBySlug =
                                remember(visibleProviders) {
                                    visibleProviders.associateBy { it.slug }
                                }

                            val filteredProvidersWithModels =
                                remember(pickerQuery, visibleProviders) {
                                    visibleProviders.mapNotNull { provider ->
                                        val matchingModels =
                                            provider.models.orEmpty().filter { model ->
                                                pickerQuery.isBlank() ||
                                                    provider.name.contains(pickerQuery, ignoreCase = true) ||
                                                    provider.slug.contains(pickerQuery, ignoreCase = true) ||
                                                    model.contains(pickerQuery, ignoreCase = true)
                                            }
                                        if (matchingModels.isNotEmpty()) {
                                            provider to matchingModels
                                        } else {
                                            null
                                        }
                                    }
                                }

                            SearchBar(
                                query = pickerQuery,
                                onQueryChange = { pickerQuery = it },
                                placeholder = stringResource(R.string.model_picker_search),
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            LazyColumn(
                                modifier = Modifier.weight(1f, fill = false),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                // ── Pinned section ──
                                if (filteredPinned.isNotEmpty()) {
                                    item(key = "pinned-header") {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.PushPin,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Text(
                                                text = stringResource(R.string.sessions_pinned_indicator),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    items(
                                        filteredPinned,
                                        key = { "pinned:${it.providerSlug}:${it.modelName}" },
                                    ) { pinned ->
                                        val caps =
                                            providerBySlug[pinned.providerSlug]?.capabilities?.get(
                                                pinned.modelName,
                                            )
                                        ModelItemCard(
                                            modelName = pinned.modelName,
                                            isPinned = true,
                                            onPinToggle =
                                                if (onPinToggle != null) {
                                                    { onPinToggle(pinned.providerSlug, pinned.modelName) }
                                                } else {
                                                    null
                                                },
                                            onClick = { onSelect(pinned.providerSlug, pinned.modelName) },
                                            canDisableReasoning = caps?.can_disable_reasoning,
                                            supportsReasoning = caps?.reasoning,
                                        )
                                    }
                                }

                                // ── Visible providers / models (lazy item per model) ──
                                filteredProvidersWithModels.forEach { (provider, models) ->
                                    item(key = "header:${provider.slug}") {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                                        ) {
                                            Text(
                                                text = provider.name,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Spacer(modifier = Modifier.weight(1f))
                                            Text(
                                                text = "${models.size} models",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }

                                    items(
                                        items = models,
                                        key = { model -> "${provider.slug}:$model" },
                                    ) { model ->
                                        val isPinned = "${provider.slug}:$model" in pinnedSet
                                        val caps = provider.capabilities?.get(model)
                                        ModelItemCard(
                                            modelName = model,
                                            isPinned = isPinned,
                                            onPinToggle =
                                                if (onPinToggle != null) {
                                                    { onPinToggle(provider.slug, model) }
                                                } else {
                                                    null
                                                },
                                            onClick = { onSelect(provider.slug, model) },
                                            canDisableReasoning = caps?.can_disable_reasoning,
                                            supportsReasoning = caps?.reasoning,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showVisibilityEditor = true }, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.model_visibility_title))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Title with an optional scope line beneath it, so a qualifier such as "this
 * chat only" never wraps as an orphaned fragment of the title. The close
 * button keeps the default 48dp touch target.
 */
@Composable
private fun ModelPickerHeader(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 16.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(lineBreak = LineBreak.Heading),
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.action_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelItemCard(
    modelName: String,
    isPinned: Boolean,
    onPinToggle: (() -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    canDisableReasoning: Boolean? = null,
    supportsReasoning: Boolean? = null,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    // No maxLines/ellipsis: a long model name wraps so the full
                    // name stays readable while the pin button keeps its spot.
                )
                val hint =
                    when {
                        supportsReasoning == false -> "no reasoning"
                        canDisableReasoning == false -> "reasoning always on"
                        else -> null
                    }
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onPinToggle != null) {
                IconButton(
                    onClick = onPinToggle,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (isPinned) "Unpin model" else "Pin model",
                        tint =
                            if (isPinned) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
