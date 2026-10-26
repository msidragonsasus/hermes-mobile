package com.m57.hermescontrol.ui.model.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.SearchBar
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
    val activeProfile by AuthManager.activeProfileId.collectAsStateWithLifecycle()
    val preferenceScope = remember(baseUrl, selectedProfile, activeProfile) { AuthManager.uiPreferenceScope() }
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
    val spacing = LocalSpacing.current
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_visibility_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(stringResource(R.string.model_visibility_description), style = MaterialTheme.typography.bodyMedium)
                controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                SearchBar(query, { query = it }, placeholder = stringResource(R.string.model_picker_search))
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .weight(
                            1f,
                            fill = false,
                        ).heightIn(max = 420.dp)
                        .testTag("model_visibility_list"),
                ) {
                    providers.forEach { provider ->
                        val allModels = provider.models.orEmpty().distinct()
                        val models =
                            allModels.filter {
                                query.isBlank() || it.contains(query, true) ||
                                    provider.name.contains(query, true) || provider.slug.contains(query, true)
                            }
                        if (models.isNotEmpty()) {
                            item("provider:${provider.slug}") {
                                val visibleCount = allModels.count { controller.value.isVisible(provider.slug, it) }
                                val allVisible = visibleCount == allModels.size
                                ListItem(
                                    headlineContent = { Text(provider.name) },
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                R.string.model_visibility_count,
                                                visibleCount,
                                                allModels.size,
                                            ),
                                        )
                                    },
                                    trailingContent = {
                                        Switch(
                                            allVisible,
                                            onCheckedChange = null,
                                            enabled = !controller.busy,
                                        )
                                    },
                                    modifier =
                                        Modifier.toggleable(
                                            allVisible,
                                            enabled = !controller.busy,
                                            role = Role.Switch,
                                        ) {
                                            controller.update { state -> state.setProviderVisible(provider.slug, it) }
                                        },
                                )
                            }
                            items(models, key = { "${provider.slug}::$it" }) { model ->
                                val visible = controller.value.isVisible(provider.slug, model)
                                ListItem(
                                    headlineContent = { Text(model) },
                                    trailingContent = {
                                        Switch(
                                            visible,
                                            onCheckedChange = null,
                                            enabled = !controller.busy,
                                        )
                                    },
                                    modifier =
                                        Modifier
                                            .testTag("model_visibility:${provider.slug}::$model")
                                            .toggleable(
                                                visible,
                                                enabled = !controller.busy,
                                                role = Role.Switch,
                                            ) { next ->
                                                controller.update { it.setModelVisible(provider, model, next) }
                                            },
                                )
                            }
                        }
                    }
                    if (providers.all { it.models.isNullOrEmpty() }) {
                        item { Text(stringResource(R.string.model_picker_no_models), Modifier.padding(spacing.md)) }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { controller.update { ModelVisibility() } },
                enabled = !controller.busy,
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(R.string.model_visibility_show_all)) }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}
