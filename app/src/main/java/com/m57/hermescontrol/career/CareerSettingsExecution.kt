package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.data.model.MainModelAssignment
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.components.reasoningLevels
import com.m57.hermescontrol.ui.common.SettingsChoiceRow
import com.m57.hermescontrol.ui.common.SettingsDivider
import com.m57.hermescontrol.ui.common.SettingsPickerDialog
import com.m57.hermescontrol.ui.common.SettingsRow
import com.m57.hermescontrol.ui.model.components.ModelPickerDialog
import com.m57.hermescontrol.ui.model.components.modelDisplayName

internal data class CareerSettingsModel(
    val provider: String,
    val id: String,
    val label: String,
    val efforts: List<String>,
    val billing: String? = null,
    val selectable: Boolean = true,
    val unavailableReason: String? = null,
) {
    val key: Pair<String, String> get() = provider to id
    val displayName: String get() = if (label.isBlank() || label == id) modelDisplayName(id) else label
}

@Composable
internal fun CareerSettingsExecutionRows(
    modelTitle: String,
    tagPrefix: String,
    selected: CareerExecution?,
    models: List<CareerSettingsModel>,
    enabled: Boolean,
    onSelect: (CareerExecution) -> Unit,
    reasoningTitle: String = "推論",
    emptyLabel: String = "利用できるモデルがありません。",
    catalogSheet: Boolean = false,
    // Shown instead of "未設定" while the saved choice is not known yet.
    unknownLabel: String? = null,
) {
    var pickingModel by remember { mutableStateOf(false) }
    var effortModelKey by remember { mutableStateOf<Pair<String, String>?>(null) }
    val selectedKey = selected?.let { it.provider to it.model }
    val selectedModel = models.firstOrNull { it.key == selectedKey }
    SettingsRow(
        title = modelTitle,
        modifier = Modifier.testTag("${tagPrefix}_model"),
        subtitle = selectedModel?.unavailableReason ?: selectedModel?.billing,
        subtitleColor =
            if (selectedModel?.unavailableReason != null) {
                LocalHermesStatusColors.current.warning
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        subtitleMaxLines = Int.MAX_VALUE,
        maxLines = Int.MAX_VALUE,
        value = selectedModel?.displayName ?: selected?.model?.let(::modelDisplayName) ?: unknownLabel ?: "未設定",
        chevron = true,
        enabled = enabled,
        wrapValue = true,
        onClick = { pickingModel = true },
    )
    SettingsDivider()
    SettingsRow(
        title = reasoningTitle,
        modifier = Modifier.testTag("${tagPrefix}_reasoning"),
        value =
            if (selected == null && unknownLabel != null) {
                unknownLabel
            } else {
                settingsEffortLabel(selected?.reasoning_effort)
            },
        maxLines = Int.MAX_VALUE,
        chevron = true,
        enabled = enabled && selectedModel?.selectable == true && selectedModel.efforts.isNotEmpty(),
        wrapValue = true,
        onClick = { effortModelKey = selectedModel?.key },
    )
    if (pickingModel && catalogSheet) {
        ModelPickerDialog(
            providers =
                models.groupBy { it.provider }.map { (provider, choices) ->
                    ModelProvider(slug = provider, name = careerProviderName(provider), models = choices.map { it.id })
                },
            title = "モデルを選択",
            currentModel = selected?.let { MainModelAssignment(provider = it.provider, model = it.model) },
            selectionEnabled = enabled,
            showModelIdentifiers = false,
            onSelect = { provider, model ->
                pickingModel = false
                effortModelKey = provider to model
            },
            onDismiss = { pickingModel = false },
        )
    } else if (pickingModel) {
        SettingsPickerDialog(title = "モデルを選択", onDismiss = { pickingModel = false }) {
            if (models.isEmpty()) {
                Text(emptyLabel, style = MaterialTheme.typography.bodyMedium)
            }
            Column(Modifier.selectableGroup()) {
                models.forEach { model ->
                    SettingsChoiceRow(
                        title = model.displayName,
                        subtitle =
                            listOfNotNull(careerProviderName(model.provider), model.billing, model.unavailableReason)
                                .joinToString(" · "),
                        selected = model.key == selectedModel?.key,
                        enabled = enabled && model.selectable && model.efforts.isNotEmpty(),
                        onClick = {
                            pickingModel = false
                            effortModelKey = model.key
                        },
                    )
                }
            }
        }
    }
    val effortModel = models.firstOrNull { it.key == effortModelKey }
    if (effortModel != null) {
        SettingsPickerDialog(title = "${effortModel.displayName} の推論強度", onDismiss = { effortModelKey = null }) {
            Column(Modifier.selectableGroup()) {
                effortModel.efforts.forEach { effort ->
                    SettingsChoiceRow(
                        title = settingsEffortLabel(effort),
                        selected = effortModel.key == selectedModel?.key && effort == selected?.reasoning_effort,
                        enabled = enabled && effortModel.selectable,
                        modifier = Modifier.testTag("career_effort_$effort"),
                        onClick = {
                            effortModelKey = null
                            onSelect(CareerExecution(effortModel.provider, effortModel.id, effort))
                        },
                    )
                }
            }
        }
    }
}

private fun careerProviderName(provider: String): String =
    when (provider) {
        "openai" -> "OpenAI"
        "openai-codex" -> "OpenAI Codex"
        "openrouter" -> "OpenRouter"
        "anthropic" -> "Anthropic"
        else -> modelDisplayName(provider)
    }

@Composable
private fun settingsEffortLabel(effort: String?): String =
    when (effort) {
        null -> {
            "未設定"
        }

        "default" -> {
            "Hermes標準"
        }

        else -> {
            reasoningLevels.firstOrNull { it.first == effort }?.second?.let { stringResource(it) }
                ?: "提供元の設定（${modelDisplayName(effort)}）"
        }
    }
