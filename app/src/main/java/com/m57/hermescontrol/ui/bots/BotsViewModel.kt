package com.m57.hermescontrol.ui.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.agents.BotConversations
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.data.model.BotRosterMeta
import com.m57.hermescontrol.data.model.CreateProfileRequest
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.SpecialistCapabilitiesState
import com.m57.hermescontrol.data.remote.SpecialistsRepository
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.data.ws.toJsonElement
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

data class BotsUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val profiles: List<ProfileInfo> = emptyList(),
    val rosterScope: String? = null,
    val activeProfileName: String? = null,
    val searchQuery: String = "",
    val showHidden: Boolean = false,
    val hiddenProfiles: Set<String> = emptySet(),
    val capabilitiesState: SpecialistCapabilitiesState = SpecialistCapabilitiesState.Loading,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
) {
    val hasHiddenBots: Boolean
        get() = profiles.any { it.isHidden || it.name in hiddenProfiles }

    val activeNowBots: List<ProfileInfo>
        get() {
            val nowSeconds = System.currentTimeMillis() / 1000.0
            return profiles.filter { profile ->
                profile.worker_session != null ||
                    profile.name == activeProfileName ||
                    ((profile.canonical_session?.last_active ?: 0.0) > nowSeconds - 90) ||
                    ((profile.last_session?.last_active ?: 0.0) > nowSeconds - 90)
            }
        }

    val displayProfiles: List<ProfileInfo>
        get() {
            val query = searchQuery.trim().lowercase()
            return profiles
                .filter { profile ->
                    val isHidden = profile.isHidden || profile.name in hiddenProfiles
                    if (!showHidden && isHidden) return@filter false
                    if (query.isBlank()) return@filter true
                    profile.name.lowercase().contains(query) ||
                        profile.effectiveTitle.lowercase().contains(query) ||
                        profile.effectiveDescription.lowercase().contains(query)
                }.sortedWith(
                    compareByDescending<ProfileInfo> { it.name == activeProfileName }
                        .thenByDescending {
                            it.canonical_session?.last_active
                                ?: it.last_session?.last_active
                                ?: 0.0
                        }.thenBy { it.name },
                )
        }
}

class BotsViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    autoLoad: Boolean = true,
    private val specialistsRepository: SpecialistsRepository = SpecialistsRepository(),
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(BotsUiState())
    val uiState: StateFlow<BotsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(hiddenProfiles = AuthManager.getHiddenProfiles().toSet()) }
        if (autoLoad) {
            loadBots()
        }
    }

    fun loadBots(isRefresh: Boolean = false) {
        val rosterScope = BotConversations.serverScope()
        _uiState.update {
            if (isRefresh) {
                it.copy(isRefreshing = true, errorMessage = null)
            } else {
                it.copy(isLoading = true, errorMessage = null)
            }
        }
        viewModelScope.launch(ioDispatcher) {
            // First try fetching profiles via WebSocket RPC (profiles.list) which includes ui_meta (groups, custom avatars).
            var profilesWithMeta: List<ProfileInfo>? = null
            try {
                val rpcResult = HermesWsClient.request(WsMethods.PROFILES_LIST).await()
                val jsonElement =
                    when (rpcResult) {
                        is JsonElement -> rpcResult
                        null -> null
                        else -> rpcResult.toJsonElement()
                    }
                if (jsonElement != null) {
                    val resp = OkHttpProvider.json.decodeFromJsonElement<ProfilesResponse>(jsonElement)
                    if (!resp.profiles.isNullOrEmpty()) {
                        profilesWithMeta = resp.profiles
                    }
                }
            } catch (_: Exception) {
                // Fallback to REST API below
            }

            val profilesResult =
                if (profilesWithMeta != null) {
                    null
                } else {
                    safeApiCall { ApiClient.hermesApi.getProfiles() }
                }
            val activeResult = safeApiCall { ApiClient.hermesApi.getActiveProfile() }
            val capabilities = specialistsRepository.fetchCapabilities()
            if (BotConversations.serverScope() != rosterScope) return@launch

            if (profilesWithMeta != null) {
                val activeName = (activeResult as? NetworkResult.Success)?.data?.active
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        profiles = profilesWithMeta,
                        rosterScope = rosterScope,
                        activeProfileName = activeName ?: it.activeProfileName,
                        hiddenProfiles = AuthManager.getHiddenProfiles().toSet(),
                        capabilitiesState = capabilities,
                        errorMessage = null,
                    )
                }
            } else if (profilesResult is NetworkResult.Success) {
                val activeName = (activeResult as? NetworkResult.Success)?.data?.active
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        profiles = profilesResult.data.profiles.orEmpty(),
                        rosterScope = rosterScope,
                        activeProfileName = activeName ?: it.activeProfileName,
                        hiddenProfiles = AuthManager.getHiddenProfiles().toSet(),
                        capabilitiesState = capabilities,
                        errorMessage = null,
                    )
                }
            } else {
                val err =
                    (profilesResult as? NetworkResult.Failure)?.error?.message
                        ?: "Failed to load bots"
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        capabilitiesState = capabilities,
                        errorMessage = err,
                    )
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun toggleShowHidden() {
        _uiState.update { it.copy(showHidden = !it.showHidden) }
    }

    suspend fun selectBot(bot: ProfileInfo): Boolean {
        val result = ProfileSwitchCoordinator.switchProfile(bot.name)
        return result is NetworkResult.Success
    }

    fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun createBot(
        name: String,
        title: String,
        description: String,
        shape: String,
        color: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val req =
                CreateProfileRequest(
                    name = name,
                    description = description.ifBlank { null },
                    clone_from_default = false,
                )
            val result = safeApiCall { ApiClient.hermesApi.createProfile(req) }
            if (result is NetworkResult.Success) {
                // Configure UI metadata (avatar, custom title) and bot SOUL via RPC
                val botMeta =
                    BotRosterMeta(
                        title = title.ifBlank { null },
                        description = description.ifBlank { null },
                        avatar =
                            BotAvatarMeta(
                                shape = shape,
                                color = color,
                            ),
                    )
                val botSoul = composeBotSoul(name, title, description)
                wsClientConfigureBot(name, botMeta, soul = botSoul)
                loadBots()
                onSuccess()
            } else {
                val err =
                    (result as? NetworkResult.Failure)?.error?.message
                        ?: "Failed to create bot"
                _uiState.update { it.copy(errorMessage = err) }
            }
        }
    }

    fun updateBotMeta(
        name: String,
        title: String,
        description: String,
        shape: String,
        color: String,
        icon: String? = null,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val bot = _uiState.value.profiles.find { it.name == name }
            val existingMeta = bot?.botMeta() ?: BotRosterMeta()
            val updatedMeta =
                existingMeta.copy(
                    title = title.ifBlank { null },
                    description = description.ifBlank { null },
                    avatar =
                        (existingMeta.avatar ?: BotAvatarMeta()).copy(
                            shape = shape,
                            color = color,
                            icon = icon,
                        ),
                )
            try {
                wsClientConfigureBot(name, updatedMeta).await()
            } catch (_: Exception) {
            }
            loadBots()
            onSuccess()
        }
    }

    fun deleteBot(
        name: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val result = safeApiCall { ApiClient.hermesApi.deleteProfile(name) }
            if (result is NetworkResult.Success) {
                loadBots()
                onSuccess()
            } else {
                val err =
                    (result as? NetworkResult.Failure)?.error?.message
                        ?: "Failed to delete bot"
                _uiState.update { it.copy(errorMessage = err) }
            }
        }
    }

    private fun composeBotSoul(
        name: String,
        title: String,
        description: String,
    ): String {
        val displayName = title.ifBlank { name }
        val lines = mutableListOf<String>()
        lines.add("# $displayName")
        lines.add("")
        if (title.isNotBlank()) lines.add("**Role:** $title")
        if (description.isNotBlank()) lines.add("**Mission:** $description")
        lines.add("")
        lines.add("You are $displayName, a persistent named agent (profile `$name`) on this machine.")
        lines.add("You keep your own memory, skills, and conversation history across sessions.")
        return lines.joinToString("\n")
    }

    private fun wsClientConfigureBot(
        name: String,
        meta: BotRosterMeta,
        soul: String? = null,
    ): kotlinx.coroutines.CompletableDeferred<Any?> {
        val metaMap =
            buildMap<String, Any> {
                meta.title?.let { put("title", it) }
                meta.description?.let { put("description", it) }
                meta.avatar?.let { av ->
                    put(
                        "avatar",
                        buildMap<String, Any> {
                            av.shape?.let { put("shape", it) }
                            av.color?.let { put("color", it) }
                            av.icon?.let { put("icon", it) }
                        },
                    )
                }
                if (!meta.groups.isNullOrEmpty()) {
                    put("groups", meta.groups)
                }
            }

        val params =
            buildMap<String, Any> {
                put("name", name)
                put("ui_meta", mapOf("hermes-bots" to metaMap))
                soul?.let { put("soul", it) }
            }

        return HermesWsClient.request(
            WsMethods.PROFILES_CONFIGURE,
            params,
        )
    }
}
