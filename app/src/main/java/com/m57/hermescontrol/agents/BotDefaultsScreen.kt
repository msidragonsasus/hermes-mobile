package com.m57.hermescontrol.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.MainModelAssignment
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.bots.BotsViewModel
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.model.components.ModelPickerDialog

@Composable
fun BotDefaultsScreen(profile: String) {
    val epoch by AuthManager.connectionEpoch.collectAsStateWithLifecycle()
    val vm: BotDefaultsViewModel =
        viewModel(key = "bot-defaults:$epoch:$profile") {
            BotDefaultsViewModel(profile)
        }
    val state by vm.uiState.collectAsStateWithLifecycle()
    var modelPicker by remember { mutableStateOf(false) }
    var effortPicker by remember { mutableStateOf(false) }
    val roster: BotsViewModel = viewModel { BotsViewModel() }
    val bots by roster.uiState.collectAsStateWithLifecycle()
    val rosterScope = BotConversations.serverScope()
    val bot = bots.profiles.firstOrNull { bots.rosterScope == rosterScope && it.name == profile }
    val label = BotConversations.label(profile, bot, state.bot)
    val spacing = LocalSpacing.current
    LaunchedEffect(vm) { vm.load() }
    LaunchedEffect(rosterScope) {
        if (bots.rosterScope != rosterScope) roster.loadBots(isRefresh = true)
    }
    HermesScaffold(
        title = { Text("${label}の既定値") },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        showShellMenu = false,
        actions = {
            TextButton(onClick = { vm.load() }, enabled = !state.saving && !state.loading) { Text("再読込") }
        },
    ) {
        when {
            state.loading -> {
                LoadingState()
            }

            state.error != null && state.model.isEmpty() -> {
                ErrorState(state.error!!, { vm.load() })
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize().testTag("bot_defaults")) {
                    item {
                        Text(
                            "このbotが新しい会話・グループで使うモデルと推論強度です。既存の会話に個別設定がある場合は、その設定を優先します。",
                            Modifier.padding(spacing.md),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (state.saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    item {
                        ListItem(
                            headlineContent = { Text("モデル") },
                            supportingContent = { Text("${state.provider}/${state.model}") },
                            modifier =
                                Modifier
                                    .clickable(
                                        enabled = !state.saving && !state.loading,
                                    ) { modelPicker = true }
                                    .testTag("bot_default_model"),
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("推論強度") },
                            supportingContent = { Text(state.effort?.takeIf { it.isNotBlank() } ?: "Hermesの既定値") },
                            modifier =
                                Modifier
                                    .clickable(enabled = !state.saving && state.efforts.isNotEmpty()) {
                                        effortPicker = true
                                    }.testTag("bot_default_effort"),
                        )
                    }
                    if (state.efforts.isEmpty()) item { Text("このモデルでは推論強度を変更できません。", Modifier.padding(spacing.md)) }
                    state.error?.let {
                        item {
                            Text(
                                it,
                                Modifier.padding(spacing.md),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    state.notice?.let { item { Text(it, Modifier.padding(spacing.md)) } }
                }
            }
        }
    }
    if (modelPicker) {
        ModelPickerDialog(
            providers = state.providers,
            title = "${label}の既定モデル",
            isLoading = state.loading,
            selectionEnabled = !state.loading && !state.saving,
            errorMessage = state.modelOptionsError ?: state.error,
            currentModel = MainModelAssignment(state.provider, state.model),
            onRefresh = { vm.load(refreshModels = true) },
            onSelect = { provider, model ->
                if (vm.setModel(provider, model)) modelPicker = false
            },
            onDismiss = { modelPicker = false },
        )
    }
    if (effortPicker) {
        AlertDialog(
            onDismissRequest = { effortPicker = false },
            title = { Text("既定の推論強度") },
            text = {
                Column {
                    state.efforts.forEach { effort ->
                        TextButton(onClick = {
                            effortPicker = false
                            vm.setEffort(effort)
                        }) { Text(effort) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { effortPicker = false }) { Text("閉じる") } },
        )
    }
}
