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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ConfigUpdateRequest
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.UpdateProfileModelRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.components.availableReasoningLevels
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.model.components.ModelPickerDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class BotDefaultsState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val model: String = "",
    val provider: String = "",
    val effort: String? = null,
    val efforts: List<String> = emptyList(),
    val providers: List<ModelProvider> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
)

class BotDefaultsViewModel(
    private val profile: String,
) : ViewModel() {
    private val connection = AuthManager.contextFlow.value
    private val api = ApiClient.hermesApi
    private val state = MutableStateFlow(BotDefaultsState())
    val uiState = state.asStateFlow()

    init {
        load()
    }

    private fun checkConnection() {
        check(connection == AuthManager.contextFlow.value) { "接続先が変わりました。画面を開き直してください。" }
    }

    fun load(
        refreshModels: Boolean = false,
        notice: String? = null,
    ) {
        viewModelScope.launch {
            state.update { it.copy(loading = true, error = null) }
            try {
                val next =
                    withContext(Dispatchers.IO) {
                        checkConnection()
                        val profiles = api.getProfiles().body()?.profiles ?: error("bot一覧を取得できません")
                        val bot = profiles.firstOrNull { it.name == profile } ?: error("botが見つかりません")
                        val config = api.getConfig(profile).body() ?: error("設定を取得できません")
                        val providers =
                            api
                                .getModelOptions(
                                    refresh = refreshModels,
                                    profile = profile,
                                ).body()
                                ?.providers
                                .orEmpty()
                        val schema = api.getConfigSchema(profile).body()
                        val agent = config["agent"] as? JsonObject
                        val effort = (agent?.get("reasoning_effort") as? JsonPrimitive)?.contentOrNull
                        val capabilities = providers.find { it.slug == bot.provider }?.capabilities?.get(bot.model)
                        BotDefaultsState(
                            loading = false,
                            model = bot.model.orEmpty(),
                            provider = bot.provider.orEmpty(),
                            effort = effort,
                            // Use the same gateway values/capability filtering as the existing chat control.
                            efforts =
                                if (capabilities?.reasoning == false) {
                                    emptyList()
                                } else {
                                    schema?.fields?.get("agent.reasoning_effort")?.options
                                        ?: availableReasoningLevels(
                                            capabilities?.can_disable_reasoning,
                                        ).map { it.first }
                                },
                            providers = providers,
                            notice = notice,
                        )
                    }
                checkConnection()
                state.value = next
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.update { it.copy(loading = false, error = "botの設定を読み込めません。接続を確認して再試行してください。") }
            }
        }
    }

    fun setModel(
        provider: String,
        model: String,
    ) = save {
        check(state.value.providers.any { it.slug == provider && model in it.models.orEmpty() })
        check(api.updateProfileModel(profile, UpdateProfileModelRequest(provider, model)).isSuccessful)
    }

    fun setEffort(effort: String) =
        save {
            check(effort in state.value.efforts)
            check(
                api
                    .updateConfig(
                        ConfigUpdateRequest(
                            config = mapOf("agent" to JsonObject(mapOf("reasoning_effort" to JsonPrimitive(effort)))),
                            profile = profile,
                        ),
                    ).isSuccessful,
            )
        }

    private fun save(write: suspend () -> Unit) {
        if (state.value.saving || state.value.loading) return
        viewModelScope.launch {
            state.update { it.copy(saving = true, error = null, notice = null) }
            try {
                withContext(Dispatchers.IO) {
                    checkConnection()
                    write()
                }
                checkConnection()
                load(notice = "保存しました。新しい個別会話・グループ会話から使用します。")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.update { it.copy(error = "保存を確認できませんでした。再読込して設定を確認してください。") }
            } finally {
                state.update { it.copy(saving = false) }
            }
        }
    }
}

@Composable
fun BotDefaultsScreen(profile: String) {
    val vm: BotDefaultsViewModel =
        viewModel(key = "bot-defaults:${AuthManager.getBaseUrl()}:$profile") {
            BotDefaultsViewModel(profile)
        }
    val state by vm.uiState.collectAsStateWithLifecycle()
    var modelPicker by remember { mutableStateOf(false) }
    var effortPicker by remember { mutableStateOf(false) }
    val spacing = LocalSpacing.current
    LaunchedEffect(Unit) { vm.load() }
    HermesScaffold(
        title = { Text("${BotConversations.label(profile)}の既定値") },
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
                            headlineContent = { Text("モデル（model）") },
                            supportingContent = { Text("${state.provider}/${state.model}") },
                            modifier =
                                Modifier
                                    .clickable(
                                        enabled = !state.saving,
                                    ) { modelPicker = true }
                                    .testTag("bot_default_model"),
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("推論強度（effort）") },
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
            title = "${BotConversations.label(profile)}の既定モデル",
            isLoading = state.loading,
            errorMessage = state.error,
            onRefresh = { vm.load(refreshModels = true) },
            onSelect = { provider, model ->
                modelPicker = false
                vm.setModel(provider, model)
            },
            onDismiss = { modelPicker = false },
        )
    }
    if (effortPicker) {
        AlertDialog(
            onDismissRequest = { effortPicker = false },
            title = { Text("既定の推論強度（effort）") },
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
