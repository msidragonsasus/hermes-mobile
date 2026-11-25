package com.m57.hermescontrol.ui.model.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.InListSectionHeaderPadding
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.model.ModelVisibility
import com.m57.hermescontrol.ui.model.ModelVisibilityStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelVisibilityController(
    val value: ModelVisibility,
    val busy: Boolean,
    val error: String?,
    val update: ((ModelVisibility) -> ModelVisibility) -> Unit,
)

@Composable
fun rememberModelVisibility(): ModelVisibilityController {
    val baseUrl by AuthManager.baseUrlFlow.collectAsStateWithLifecycle()
    val selectedProfile by AuthManager.selectedProfileFlow.collectAsStateWithLifecycle()
    // Picker visibility belongs to the connection, so switching bots keeps the same list.
    val preferenceScope = remember(baseUrl, selectedProfile) { AppScope.connectionKey() }
    val store = ModelVisibilityStore.shared
    val result = remember(preferenceScope) { runCatching { store.observe(preferenceScope) } }
    val value = result.getOrNull()?.collectAsStateWithLifecycle()?.value ?: ModelVisibility()
    var busy by remember(preferenceScope) { mutableStateOf(false) }
    var writeFailed by remember(preferenceScope) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    return ModelVisibilityController(
        value = value,
        busy = busy || result.isFailure,
        error =
            when {
                result.isFailure -> stringResource(R.string.model_visibility_load_error)
                writeFailed -> stringResource(R.string.model_visibility_save_error)
                else -> null
            },
        update = { transform ->
            if (!busy && result.isSuccess) {
                busy = true
                coroutineScope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.update(preferenceScope, transform) }
                        writeFailed = false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        writeFailed = true
                    } finally {
                        busy = false
                    }
                }
            }
        },
    )
}

@Composable
fun ModelVisibilityEditor(
    providers: List<ModelProvider>,
    controller: ModelVisibilityController,
    onDismiss: () -> Unit,
) {
    ModelCatalogSheet(
        title = stringResource(R.string.model_visibility_title),
        onDismiss = onDismiss,
        onBack = onDismiss,
    ) {
        ModelVisibilityContent(providers, controller, onClose = onDismiss)
    }
}

@Composable
internal fun ColumnScope.ModelVisibilityContent(
    providers: List<ModelProvider>,
    controller: ModelVisibilityController,
    onClose: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(providers, query) { matchingModelGroups(providers, query) }
    Text(
        stringResource(R.string.model_visibility_description),
        Modifier.padding(bottom = spacing.sm),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    controller.error?.let { InlineNotice(it, tone = NoticeTone.Error) }
    SearchBar(query, { query = it }, placeholder = stringResource(R.string.model_picker_search))
    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("model_visibility_list")) {
        groups.forEach { (provider, models) ->
            item("provider:${provider.slug}") {
                val allModels = provider.models.orEmpty().distinct()
                val visibleCount = allModels.count { controller.value.isVisible(provider.slug, it) }
                SectionHeader(provider.name, contentPadding = InListSectionHeaderPadding)
                Text(
                    stringResource(R.string.model_visibility_count, visibleCount, allModels.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow {
                    TextButton(
                        onClick = { controller.update { it.setProviderVisible(provider.slug, true) } },
                        enabled = !controller.busy && visibleCount < allModels.size,
                    ) { Text(stringResource(R.string.model_visibility_provider_show_all)) }
                    TextButton(
                        onClick = { controller.update { it.setProviderVisible(provider.slug, false) } },
                        enabled = !controller.busy && visibleCount > 0,
                    ) { Text(stringResource(R.string.model_visibility_provider_hide_all)) }
                }
            }
            items(models, key = { "${provider.slug}::$it" }) { model ->
                val visible = controller.value.isVisible(provider.slug, model)
                ModelCatalogRow(
                    model = model,
                    modifier =
                        Modifier
                            .testTag("model_visibility:${provider.slug}::$model")
                            .toggleable(visible, enabled = !controller.busy, role = Role.Checkbox) { next ->
                                controller.update { it.setModelVisible(provider, model, next) }
                            },
                    trailing = { Checkbox(visible, onCheckedChange = null, enabled = !controller.busy) },
                )
            }
        }
        if (groups.isEmpty()) {
            item {
                Text(
                    stringResource(
                        if (query.isBlank()) R.string.model_picker_no_models else R.string.model_picker_no_matches,
                    ),
                    Modifier.padding(vertical = spacing.md),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    FlowRow {
        TextButton(onClick = { controller.update { ModelVisibility() } }, enabled = !controller.busy) {
            Text(stringResource(R.string.model_visibility_show_all))
        }
        TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
    }
}
