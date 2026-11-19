package com.m57.hermescontrol.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ConfigUpdateRequest
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.SpecialistRequestScope
import com.m57.hermescontrol.data.remote.saveProfileModel
import com.m57.hermescontrol.data.remote.settingsWriteError
import com.m57.hermescontrol.ui.chat.components.availableReasoningLevels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val bot: ProfileInfo? = null,
    val model: String = "",
    val provider: String = "",
    val effort: String? = null,
    val efforts: List<String> = emptyList(),
    val providers: List<ModelProvider> = emptyList(),
    val modelOptionsError: String? = null,
    val error: String? = null,
    val notice: String? = null,
)

class BotDefaultsViewModel(
    private val profile: String,
    private val connectionEpoch: StateFlow<Long> = AuthManager.connectionEpoch,
    private val api: HermesApiService = ApiClient.hermesApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope = SpecialistRequestScope(connectionEpoch.value)
    private val state = MutableStateFlow(BotDefaultsState())
    val uiState = state.asStateFlow()
    private var loading = false

    private fun isCurrentConnection(): Boolean = scope.epoch % 2L == 0L && scope.epoch == connectionEpoch.value

    fun load(refreshModels: Boolean = false) {
        if (loading || state.value.saving) return
        loading = true
        state.update { it.copy(loading = true, error = null, modelOptionsError = null) }
        viewModelScope.launch {
            try {
                reload(refreshModels)
            } finally {
                loading = false
            }
        }
    }

    private suspend fun reload(refreshModels: Boolean = false) {
        try {
            val next =
                withContext(ioDispatcher) {
                    check(isCurrentConnection())
                    val profiles = api.getProfiles().body()?.profiles ?: error("Missing profiles")
                    val bot = profiles.firstOrNull { it.name == profile } ?: error("Missing bot profile")
                    val config = api.getConfig(profile).body() ?: error("Missing config")
                    val providers =
                        try {
                            api.getModelOptions(refresh = refreshModels, profile = profile).body()?.providers
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    val schema = api.getConfigSchema(profile).body()
                    val agent = config["agent"] as? JsonObject
                    val effort = (agent?.get("reasoning_effort") as? JsonPrimitive)?.contentOrNull
                    val capabilities = providers?.find { it.slug == bot.provider }?.capabilities?.get(bot.model)
                    BotDefaultsState(
                        loading = false,
                        saving = state.value.saving,
                        bot = bot,
                        model = bot.model.orEmpty(),
                        provider = bot.provider.orEmpty(),
                        effort = effort,
                        efforts =
                            if (capabilities?.reasoning == false) {
                                emptyList()
                            } else {
                                schema?.fields?.get("agent.reasoning_effort")?.options
                                    ?: availableReasoningLevels(capabilities?.can_disable_reasoning).map { it.first }
                            },
                        providers =
                            providers ?: state.value.providers,
                        modelOptionsError =
                            if (providers == null) "モデル一覧を読み込めません。再読み込みしてください。" else null,
                        notice = state.value.notice,
                    )
                }
            if (isCurrentConnection()) state.value = next else showConnectionChanged()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (isCurrentConnection()) {
                state.update { it.copy(loading = false, error = "botの設定を読み込めません。接続を確認して再試行してください。") }
            } else {
                showConnectionChanged()
            }
        }
    }

    fun setModel(
        provider: String,
        model: String,
    ) = save(
        write = { api.saveProfileModel(profile, provider, model, scope) },
        onSaved = { it.copy(provider = provider, model = model) },
    )

    fun setEffort(effort: String) {
        if (effort !in state.value.efforts) return
        save(
            write = {
                settingsWriteError {
                    api.updateConfig(
                        ConfigUpdateRequest(
                            config = mapOf("agent" to JsonObject(mapOf("reasoning_effort" to JsonPrimitive(effort)))),
                            profile = profile,
                        ),
                        scope,
                    )
                }
            },
            onSaved = { it.copy(effort = effort) },
        )
    }

    private fun save(
        write: suspend () -> String?,
        onSaved: (BotDefaultsState) -> BotDefaultsState,
    ): Boolean {
        if (state.value.saving || state.value.loading) return false
        state.update { it.copy(saving = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                val error =
                    withContext(ioDispatcher) {
                        if (isCurrentConnection()) write() else null
                    }
                when {
                    !isCurrentConnection() -> {
                        showConnectionChanged()
                    }

                    error != null -> {
                        state.update { it.copy(error = error) }
                    }

                    else -> {
                        state.update {
                            onSaved(it).copy(notice = "保存しました。新しい個別会話・グループ会話から使用します。")
                        }
                        reload()
                    }
                }
            } finally {
                state.update { it.copy(saving = false) }
            }
        }
        return true
    }

    private fun showConnectionChanged() {
        state.update { it.copy(loading = false, error = "接続状態が変わりました。最新の設定を読み込んでください。") }
    }
}
