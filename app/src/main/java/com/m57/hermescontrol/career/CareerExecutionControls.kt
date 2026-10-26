package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.model.components.ModelPickerDialog

@Composable
internal fun CareerExecutionControls(
    selected: CareerExecution?,
    capabilities: CareerCapabilities?,
    enabled: Boolean,
    onSelect: (CareerExecution) -> Unit,
    onRefresh: () -> Unit,
    label: String = "会話",
) {
    var modelsOpen by remember { mutableStateOf(false) }
    var effortModel by remember { mutableStateOf<CareerModelOption?>(null) }
    val available = enabled && capabilities?.state == "available" && "execution_config" in capabilities.features
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
        TextButton(
            onClick = { modelsOpen = true },
            enabled = available,
            modifier = Modifier.testTag("career_${label}_model"),
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(selected?.let { "${it.model} · ${it.provider}" } ?: "モデルを選択")
        }
        TextButton(
            onClick = {
                effortModel =
                    capabilities?.models?.firstOrNull { it.id == selected?.model && it.provider == selected.provider }
            },
            enabled = available && selected != null,
            modifier = Modifier.testTag("career_${label}_reasoning"),
            shapes = ButtonDefaults.shapes(),
        ) { Text("推論: ${careerEffortLabel(selected?.reasoning_effort)}") }
        TextButton(onClick = onRefresh, shapes = ButtonDefaults.shapes()) { Text("候補を再確認") }
        capabilities?.provider_errors.orEmpty().forEach { failure ->
            Text("${failure.provider}: ${careerPreparationReason(failure.reason) ?: "候補を取得できませんでした。"}")
        }
    }
    if (modelsOpen) {
        ModelPickerDialog(
            providers =
                capabilities?.models.orEmpty().groupBy { it.provider }.map { (provider, models) ->
                    ModelProvider(slug = provider, name = provider, models = models.map { it.id })
                },
            title = "$label のモデル",
            onSelect = { provider, model ->
                modelsOpen = false
                effortModel = capabilities?.models?.firstOrNull { it.provider == provider && it.id == model }
            },
            onDismiss = { modelsOpen = false },
        )
    }
    effortModel?.let { model ->
        AlertDialog(
            onDismissRequest = { effortModel = null },
            title = { Text("${model.label} の推論強度") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    model.reasoning_efforts.forEach { effort ->
                        TextButton(onClick = {
                            onSelect(CareerExecution(model.provider, model.id, effort))
                            effortModel = null
                        }, modifier = Modifier.fillMaxWidth().testTag("career_effort_$effort")) {
                            Text(careerEffortLabel(effort))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { effortModel = null }) { Text("閉じる") } },
        )
    }
}

private fun careerEffortLabel(effort: String?): String =
    when (effort) {
        "default" -> "Hermes標準"
        null -> "未選択"
        else -> effort
    }
