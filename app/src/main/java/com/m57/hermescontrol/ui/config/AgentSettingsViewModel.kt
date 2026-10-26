package com.m57.hermescontrol.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.ConfigSchemaResponse
import com.m57.hermescontrol.data.model.ConfigUpdateRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Result of one save, shown once as a snackbar. [undo] restores the previous config values. */
data class AgentSettingsNotice(
    val id: Long,
    val message: String,
    val undo: Map<String, JsonElement>? = null,
)

data class AgentSettingsUiState(
    val isLoading: Boolean = false,
    val values: Map<String, JsonElement>? = null,
    val defaults: Map<String, JsonElement> = emptyMap(),
    val schema: ConfigSchemaResponse? = null,
    val errorMessage: String? = null,
    /** Settings with a save in flight; their controls are disabled meanwhile. */
    val savingIds: Set<String> = emptySet(),
    val notice: AgentSettingsNotice? = null,
) {
    val visibleGroups: List<AgentSettingGroup>
        get() {
            val current = values ?: return emptyList()
            return AgentSettingsCatalog.groups
                .map { group -> group.copy(settings = group.settings.filter { it.isAvailable(schema, current) }) }
                .filter { it.settings.isNotEmpty() }
        }

    /** Number of raw fields behind "詳細設定", for the entry row. */
    val advancedFieldCount: Int get() = schema?.fields?.size ?: 0
}

/** Saves each change immediately; the server deep-merges the small changeset over `config.yaml`. */
class AgentSettingsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AgentSettingsUiState())
    val uiState: StateFlow<AgentSettingsUiState> = _uiState.asStateFlow()
    private var noticeSeq = 0L

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            coroutineScope {
                val config = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getConfig() } }
                val schema = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getConfigSchema() } }
                val defaults = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getConfigDefaults() } }
                when (val result = config.await()) {
                    is NetworkResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                values = flattenConfig(result.data),
                                schema = (schema.await() as? NetworkResult.Success)?.data,
                                defaults =
                                    (defaults.await() as? NetworkResult.Success)?.data?.let(::flattenConfig).orEmpty(),
                            )
                        }
                    }

                    is NetworkResult.Failure -> {
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = "設定を読み込めませんでした: ${result.error.message}")
                        }
                    }
                }
            }
        }
    }

    fun select(
        setting: AgentSetting,
        option: AgentSettingOption,
    ) = save(setting.id, option.values, "「${setting.title}」を「${option.label}」にしました")

    fun restoreDefault(setting: AgentSetting) {
        val state = _uiState.value
        val writes = setting.defaultWrites(state.values.orEmpty(), state.defaults) ?: return
        save(setting.id, writes, "「${setting.title}」を既定に戻しました")
    }

    fun undo(notice: AgentSettingsNotice) {
        val writes = notice.undo ?: return
        save("undo", writes, "元に戻しました", offerUndo = false)
    }

    fun consumeNotice(id: Long) {
        _uiState.update { if (it.notice?.id == id) it.copy(notice = null) else it }
    }

    private fun save(
        settingId: String,
        writes: Map<String, JsonElement>,
        successMessage: String,
        offerUndo: Boolean = true,
    ) {
        val before = _uiState.value
        val previousValues = before.values ?: return
        val setting = AgentSettingsCatalog.byId(settingId)
        val undo =
            writes.keys.associateWith { key ->
                setting?.effectiveValue(key, previousValues, before.defaults)
                    ?: previousValues[key]
                    ?: before.defaults[key]
                    ?: JsonNull
            }
        _uiState.update {
            it.copy(
                values = previousValues + writes,
                savingIds = it.savingIds + settingId,
            )
        }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.updateConfig(
                            ConfigUpdateRequest(config = buildChangeset(writes)),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val refreshed =
                        withContext(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getConfig() } }
                    _uiState.update {
                        it.copy(
                            values = (refreshed as? NetworkResult.Success)?.data?.let(::flattenConfig) ?: it.values,
                            savingIds = it.savingIds - settingId,
                            notice = AgentSettingsNotice(++noticeSeq, successMessage, undo.takeIf { offerUndo }),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            // Put back exactly what the written keys held before the optimistic update.
                            values =
                                it.values?.let { current ->
                                    current.filterKeys { key -> key !in writes } +
                                        previousValues.filterKeys { key -> key in writes }
                                },
                            savingIds = it.savingIds - settingId,
                            notice = AgentSettingsNotice(++noticeSeq, "保存できませんでした: ${result.error.message}"),
                        )
                    }
                }
            }
        }
    }
}
