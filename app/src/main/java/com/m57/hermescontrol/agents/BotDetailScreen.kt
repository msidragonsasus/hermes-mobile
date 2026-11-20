package com.m57.hermescontrol.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.data.model.ConfigUpdateRequest
import com.m57.hermescontrol.data.model.CronJob
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.data.model.SpecialistSetupSnapshot
import com.m57.hermescontrol.data.model.ToggleSkillRequest
import com.m57.hermescontrol.data.model.UpdateProfileDescriptionRequest
import com.m57.hermescontrol.data.model.UpdateProfileSoulRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.SpecialistApiOutcome
import com.m57.hermescontrol.data.remote.SpecialistCapabilitiesState
import com.m57.hermescontrol.data.remote.SpecialistRequestScope
import com.m57.hermescontrol.data.remote.SpecialistsRepository
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.bots.BotsViewModel
import com.m57.hermescontrol.ui.bots.EditBotBottomSheet
import com.m57.hermescontrol.ui.chat.components.friendlyModelName
import com.m57.hermescontrol.ui.chat.specialistAppsSummary
import com.m57.hermescontrol.ui.common.BotAvatar
import com.m57.hermescontrol.ui.common.CompactListRow
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.HermesTextField
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.PrimaryPillButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class BotDetailUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val profile: ProfileInfo? = null,
    val soul: String = "",
    val skills: List<Skill> = emptyList(),
    val cronJobs: List<CronJob> = emptyList(),
    val approvalMode: String = "manual",
    val setupSnapshot: SpecialistSetupSnapshot? = null,
    val error: String? = null,
    val notice: String? = null,
)

internal class BotDetailViewModel(
    private val profileName: String,
    private val repository: SpecialistsRepository = SpecialistsRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(BotDetailUiState())
    val uiState = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val loaded =
                    withContext(Dispatchers.IO) {
                        val api = ApiClient.hermesApi
                        val profilesDeferred = async { safeApiCall { api.getProfiles() } }
                        val soulDeferred = async { safeApiCall { api.getProfileSoul(profileName) } }
                        val skillsDeferred = async { safeApiCall { api.getSkillsForProfile(profileName) } }
                        val cronDeferred = async { safeApiCall { api.getCronJobsForProfile(profileName) } }
                        val configDeferred = async { safeApiCall { api.getConfig(profile = profileName) } }
                        val setupDeferred = async { repository.getSetup(profileName) }

                        val profilesRes = profilesDeferred.await()
                        val bot =
                            (profilesRes as? NetworkResult.Success)?.data?.profiles?.firstOrNull {
                                it.name == profileName
                            }
                        if (bot == null) {
                            error("指定されたbotが見つかりませんでした。")
                        }
                        val soulText = (soulDeferred.await() as? NetworkResult.Success)?.data?.content.orEmpty()
                        val skillsList = (skillsDeferred.await() as? NetworkResult.Success)?.data.orEmpty()
                        val cronList = (cronDeferred.await() as? NetworkResult.Success)?.data.orEmpty()
                        val configMap = (configDeferred.await() as? NetworkResult.Success)?.data
                        val approvalsObj = configMap?.get("approvals") as? JsonObject
                        val mode = (approvalsObj?.get("mode") as? JsonPrimitive)?.contentOrNull ?: "manual"
                        val setupSnap =
                            (setupDeferred.await() as? SpecialistApiOutcome.Success)?.data

                        BotDetailUiState(
                            loading = false,
                            profile = bot,
                            soul = soulText,
                            skills = skillsList,
                            cronJobs = cronList,
                            approvalMode = mode,
                            setupSnapshot = setupSnap,
                        )
                    }
                _uiState.value = loaded
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loading = false, error = e.message ?: "botの詳細を読み込めませんでした。")
                }
            }
        }
    }

    fun saveSoulAndDescription(
        description: String,
        soul: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, error = null) }
            val result =
                withContext(Dispatchers.IO) {
                    val api = ApiClient.hermesApi
                    val descRes =
                        safeApiCall {
                            api.updateProfileDescription(
                                profileName,
                                UpdateProfileDescriptionRequest(description.trim()),
                            )
                        }
                    if (descRes is NetworkResult.Failure) return@withContext descRes
                    safeApiCall { api.updateProfileSoul(profileName, UpdateProfileSoulRequest(soul)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            saving = false,
                            soul = soul,
                            notice = "役割・SOULを保存しました（次回の会話再構築時に永続反映されます）。",
                        )
                    }
                    load()
                    onSuccess()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(saving = false, error = result.error.message.ifBlank { "SOULの保存に失敗しました。" })
                    }
                }
            }
        }
    }

    fun toggleSkill(
        skillName: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, error = null) }
            val res =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.toggleSkillForProfile(
                            ToggleSkillRequest(skillName, enabled),
                            profile = profileName,
                        )
                    }
                }
            when (res) {
                is NetworkResult.Success -> {
                    _uiState.update { current ->
                        current.copy(
                            saving = false,
                            skills =
                                current.skills.map {
                                    if (it.name ==
                                        skillName
                                    ) {
                                        it.copy(enabled = enabled)
                                    } else {
                                        it
                                    }
                                },
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(saving = false, error = res.error.message.ifBlank { "スキルの更新に失敗しました。" })
                    }
                }
            }
        }
    }

    fun toggleCronPaused(
        job: CronJob,
        pause: Boolean,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, error = null) }
            val res =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        if (pause) {
                            ApiClient.hermesApi.pauseCronJobForProfile(job.id, profile = profileName)
                        } else {
                            ApiClient.hermesApi.resumeCronJobForProfile(job.id, profile = profileName)
                        }
                    }
                }
            when (res) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(saving = false) }
                    load()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(saving = false, error = res.error.message.ifBlank { "定例作業の更新に失敗しました。" })
                    }
                }
            }
        }
    }

    fun saveApprovalMode(
        mode: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, error = null) }
            val res =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.updateConfig(
                            ConfigUpdateRequest(
                                config = mapOf("approvals" to JsonObject(mapOf("mode" to JsonPrimitive(mode)))),
                                profile = profileName,
                            ),
                        )
                    }
                }
            when (res) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            saving = false,
                            approvalMode = mode,
                            notice = "承認範囲を保存しました。",
                        )
                    }
                    onSuccess()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(saving = false, error = res.error.message.ifBlank { "承認範囲の保存に失敗しました。" })
                    }
                }
            }
        }
    }

    fun deleteBot(onDeleted: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, error = null) }
            val res =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.deleteProfile(profileName) }
                }
            when (res) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(saving = false) }
                    onDeleted()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(saving = false, error = res.error.message.ifBlank { "botの削除に失敗しました。" })
                    }
                }
            }
        }
    }

    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }
}

private enum class DetailEditorSheet {
    SOUL,
    ROUTINES,
    SKILLS,
    APPROVALS,
    APPEARANCE,
    SETUP,
}

private val generatedBotId = Regex("bot-[0-9a-f]+", RegexOption.IGNORE_CASE)

internal fun botDetailDisplayName(
    profileName: String,
    fallback: String,
    vararg profiles: ProfileInfo?,
): String {
    fun String.isDisplayName(): Boolean =
        isNotBlank() &&
            !trim().equals(profileName, ignoreCase = true) &&
            !generatedBotId.matches(trim()) &&
            !(generatedBotId.matches(profileName) && contains(profileName, ignoreCase = true))

    val matchingProfiles = profiles.filterNotNull().filter { it.name == profileName }
    return matchingProfiles.firstNotNullOfOrNull {
        BotConversations.label(profileName, it)?.takeIf { name ->
            name.isDisplayName()
        }
    }
        ?: matchingProfiles.firstNotNullOfOrNull { it.display_name?.takeIf { name -> name.isDisplayName() } }
        ?: BotConversations.label(profileName)?.takeIf { it.isDisplayName() }
        ?: fallback
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BotDetailScreen(
    profileName: String,
    botsViewModel: BotsViewModel = viewModel { BotsViewModel() },
    detailViewModel: BotDetailViewModel =
        viewModel(key = "bot-detail:${AuthManager.uiPreferenceScope()}:$profileName") {
            BotDetailViewModel(profileName)
        },
) {
    val state by detailViewModel.uiState.collectAsStateWithLifecycle()
    val roster by botsViewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val specialistRepository = remember { SpecialistsRepository() }
    var activeEditor by remember { mutableStateOf<DetailEditorSheet?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(profileName) {
        detailViewModel.load()
    }

    val bot = state.profile
    val rosterBot =
        roster.profiles.takeIf { roster.rosterScope == BotConversations.serverScope() }?.firstOrNull {
            it.name ==
                profileName
        }
    val displayName = botDetailDisplayName(profileName, stringResource(R.string.chat_header_specialist), rosterBot, bot)
    val setupIncomplete =
        state.setupSnapshot?.setup_completed == false || (bot?.isSetupIncomplete == true && state.setupSnapshot == null)

    if (showDeleteConfirm && bot != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    text = stringResource(R.string.bots_edit_delete_confirm_title, displayName),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = { Text(stringResource(R.string.bots_edit_delete_confirm_msg)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        detailViewModel.deleteBot {
                            scope.launch {
                                botsViewModel.loadBots(isRefresh = true)
                                if (BotConversations.activeProfile() == profileName) {
                                    BotConversations.openMain()
                                } else {
                                    NavigationController.goBack()
                                }
                            }
                        }
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                ) { Text(stringResource(R.string.bots_edit_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    when (activeEditor) {
        DetailEditorSheet.SOUL -> {
            if (bot != null) {
                var descDraft by remember(bot.effectiveDescription) { mutableStateOf(bot.effectiveDescription) }
                var soulDraft by remember(state.soul) { mutableStateOf(state.soul) }
                ModalBottomSheet(
                    onDismissRequest = { if (!state.saving) activeEditor = null },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            stringResource(R.string.specialist_detail_row_soul),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        InlineNotice(
                            text = "永続的なSOULの変更は次回の会話再構築・圧縮時に反映されます。",
                            tone = NoticeTone.Info,
                        )
                        state.error?.let { InlineNotice(text = it, tone = NoticeTone.Error) }
                        HermesTextField(
                            value = descDraft,
                            onValueChange = { descDraft = it },
                            label = { Text(stringResource(R.string.bots_create_desc_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("detail_desc_input"),
                        )
                        HermesTextField(
                            value = soulDraft,
                            onValueChange = { soulDraft = it },
                            label = { Text("SOUL.md") },
                            minLines = 6,
                            maxLines = 14,
                            modifier = Modifier.fillMaxWidth().testTag("detail_soul_input"),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            TextButton(onClick = { activeEditor = null }, enabled = !state.saving) {
                                Text(stringResource(R.string.action_cancel))
                            }
                            PrimaryPillButton(
                                onClick = {
                                    detailViewModel.saveSoulAndDescription(descDraft, soulDraft) {
                                        activeEditor = null
                                        botsViewModel.loadBots(isRefresh = true)
                                    }
                                },
                                enabled = !state.saving,
                                modifier = Modifier.testTag("detail_soul_save"),
                            ) { Text(stringResource(R.string.action_save)) }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }

        DetailEditorSheet.ROUTINES -> {
            ModalBottomSheet(
                onDismissRequest = { activeEditor = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.specialist_detail_row_routines),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    val inactiveProposals =
                        state.setupSnapshot
                            ?.draft
                            ?.routines
                            .orEmpty()
                    if (inactiveProposals.isNotEmpty()) {
                        InlineNotice(
                            text = "初期設定の定例作業案（未有効化）: ${inactiveProposals.joinToString(", ")}",
                            tone = NoticeTone.Info,
                        )
                    }
                    if (state.cronJobs.isEmpty()) {
                        Text(
                            "登録されている定例作業はありません。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        state.cronJobs.forEach { job ->
                            val paused = job.state == "paused"
                            CompactListRow(
                                trailing = {
                                    TextButton(
                                        onClick = { detailViewModel.toggleCronPaused(job, pause = !paused) },
                                        enabled = !state.saving,
                                    ) {
                                        Text(
                                            stringResource(
                                                if (paused) {
                                                    R.string.worker_action_resume
                                                } else {
                                                    R.string.worker_action_pause
                                                },
                                            ),
                                        )
                                    }
                                },
                            ) {
                                Text(
                                    job.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (paused) "一時停止中" else "有効",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        DetailEditorSheet.SKILLS -> {
            ModalBottomSheet(
                onDismissRequest = { activeEditor = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.specialist_detail_row_skills),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.skills.isEmpty()) {
                        Text(
                            "利用可能なスキルがありません。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        state.skills.forEach { skill ->
                            CompactListRow(
                                trailing = {
                                    Switch(
                                        checked = skill.enabled,
                                        onCheckedChange = { detailViewModel.toggleSkill(skill.name, it) },
                                        enabled = !state.saving,
                                    )
                                },
                            ) {
                                Text(
                                    skill.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        DetailEditorSheet.APPROVALS -> {
            val options =
                listOf(
                    "manual" to stringResource(R.string.specialist_detail_approval_manual),
                    "smart" to stringResource(R.string.specialist_detail_approval_smart),
                    "off" to stringResource(R.string.specialist_detail_approval_off),
                )
            ModalBottomSheet(
                onDismissRequest = { activeEditor = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.specialist_detail_row_approvals),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    options.forEach { (mode, label) ->
                        CompactListRow(
                            onClick = {
                                detailViewModel.saveApprovalMode(mode) { activeEditor = null }
                            },
                            trailing = {
                                RadioButton(
                                    selected = state.approvalMode == mode,
                                    onClick = {
                                        detailViewModel.saveApprovalMode(mode) { activeEditor = null }
                                    },
                                )
                            },
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        DetailEditorSheet.APPEARANCE -> {
            if (bot != null) {
                EditBotBottomSheet(
                    bot = bot,
                    displayName = displayName,
                    onExecutionSettings = {
                        activeEditor = null
                        NavigationController.navigateTo(BotDefaultsKey(profileName))
                    },
                    onDismiss = { activeEditor = null },
                    onSave = { title, description, shape, color, icon ->
                        botsViewModel.updateBotMeta(bot.name, title, description, shape, color, icon) {
                            activeEditor = null
                            detailViewModel.load()
                        }
                    },
                    onDelete = {
                        activeEditor = null
                        showDeleteConfirm = true
                    },
                )
            }
        }

        DetailEditorSheet.SETUP -> {
            if (bot != null) {
                SpecialistSetupSheet(
                    profileName = bot.name,
                    displayTitle = displayName,
                    isChatConnected = false,
                    canSendToCurrentChat = false,
                    onConfirmed = {
                        detailViewModel.load()
                        botsViewModel.loadBots(isRefresh = true)
                    },
                    onDismiss = { activeEditor = null },
                )
            }
        }

        null -> {
            Unit
        }
    }

    HermesScaffold(
        title = {},
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        pinTopBar = true,
        topBarHeight = 48.dp,
        showShellMenu = false,
        modifier = Modifier.testTag("bot_detail_screen"),
    ) {
        when {
            state.loading && bot == null -> {
                LoadingState(Modifier.fillMaxSize())
            }

            state.error != null && bot == null -> {
                ErrorState(
                    message = state.error.orEmpty(),
                    onRetry = detailViewModel::load,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            bot != null -> {
                val avatar = bot.botMeta()?.avatar
                val description = bot.effectiveDescription
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("bot_detail_list"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.saving) {
                        item("saving") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                    }
                    state.notice?.let { note ->
                        item("notice") {
                            InlineNotice(
                                text = note,
                                tone = NoticeTone.Info,
                                actionLabel = stringResource(R.string.action_close),
                                onAction = detailViewModel::clearNotice,
                            )
                        }
                    }
                    state.error?.let { err ->
                        item("error") { InlineNotice(text = err, tone = NoticeTone.Error) }
                    }

                    item("header") {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            BotAvatar(
                                name = displayName,
                                avatar = avatar?.copy(shape = "circle"),
                                size = 72.dp,
                                showPresence = false,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.semantics { heading() }.testTag("detail_name"),
                            )
                            BotDetailDescription(
                                description = description,
                                onEdit = { activeEditor = DetailEditorSheet.SOUL },
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                shape = CircleShape,
                            ) {
                                Text(
                                    text =
                                        stringResource(
                                            if (setupIncomplete) {
                                                R.string.specialist_detail_status_setting_up
                                            } else {
                                                R.string.specialist_detail_status_ready
                                            },
                                        ),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }

                    item("consultation") {
                        PrimaryPillButton(
                            onClick = {
                                scope.launch {
                                    val canonicalId = bot.canonical_session?.resolved_id ?: bot.canonical_session?.id
                                    BotConversations.openSpecialist(
                                        profileName = bot.name,
                                        canonicalSessionId = canonicalId,
                                        openSetupAfterLaunch = setupIncomplete,
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("detail_open_chat"),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Chat,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.specialist_detail_open_chat))
                        }
                    }

                    if (setupIncomplete || state.setupSnapshot?.activation == "pending_apply") {
                        item("setup") {
                            OutlinedButton(
                                onClick = {
                                    val epoch = AuthManager.connectionEpoch.value
                                    scope.launch {
                                        val capabilities =
                                            specialistRepository.fetchCapabilities(
                                                SpecialistRequestScope(epoch),
                                            )
                                        if (epoch != AuthManager.connectionEpoch.value) return@launch
                                        when (capabilities) {
                                            is SpecialistCapabilitiesState.Supported -> {
                                                if (capabilities.capabilities.supportsSetupChat) {
                                                    if (!BotConversations.openSpecialist(bot.name)) {
                                                        android.widget.Toast
                                                            .makeText(
                                                                context,
                                                                R.string.specialist_chat_unavailable,
                                                                android.widget.Toast.LENGTH_SHORT,
                                                            ).show()
                                                    }
                                                } else {
                                                    activeEditor = DetailEditorSheet.SETUP
                                                }
                                            }

                                            is SpecialistCapabilitiesState.LegacyUnsupported -> {
                                                activeEditor = DetailEditorSheet.SETUP
                                            }

                                            else -> {
                                                android.widget.Toast
                                                    .makeText(
                                                        context,
                                                        R.string.specialist_chat_unavailable,
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("detail_open_setup"),
                            ) {
                                Text(stringResource(R.string.specialist_setup_open_action))
                            }
                        }
                    }

                    item("work") {
                        BotSettingsGroup(stringResource(R.string.specialist_detail_group_work)) {
                            BotSettingsRow(
                                label = stringResource(R.string.specialist_detail_row_routines),
                                icon = Icons.Outlined.Schedule,
                                value =
                                    stringResource(
                                        R.string.specialist_detail_routines_count,
                                        state.cronJobs.count { it.state != "paused" },
                                    ),
                                modifier = Modifier.testTag("detail_row_routines"),
                                onClick = { activeEditor = DetailEditorSheet.ROUTINES },
                            )
                            BotSettingsRow(
                                label = stringResource(R.string.specialist_detail_row_skills),
                                icon = Icons.Outlined.AutoAwesome,
                                value =
                                    stringResource(
                                        R.string.specialist_detail_skills_count,
                                        state.skills.count { it.enabled },
                                        state.skills.size,
                                    ),
                                modifier = Modifier.testTag("detail_row_skills"),
                                onClick = { activeEditor = DetailEditorSheet.SKILLS },
                            )
                            BotSettingsRow(
                                label = stringResource(R.string.specialist_detail_row_approvals),
                                icon = Icons.Outlined.Shield,
                                value = state.approvalMode.takeIf { it == "smart" || it == "off" } ?: "manual",
                                modifier = Modifier.testTag("detail_row_approvals"),
                                onClick = { activeEditor = DetailEditorSheet.APPROVALS },
                            )
                        }
                    }

                    item("model") {
                        BotSettingsGroup(stringResource(R.string.specialist_detail_group_model)) {
                            BotSettingsRow(
                                label = stringResource(R.string.specialist_detail_row_model),
                                icon = Icons.Outlined.Tune,
                                value =
                                    friendlyModelName(bot.model)
                                        ?: stringResource(R.string.specialist_detail_default_model),
                                modifier = Modifier.testTag("detail_row_model"),
                                onClick = { NavigationController.navigateTo(BotDefaultsKey(bot.name)) },
                            )
                        }
                    }

                    item("appearance_and_apps") {
                        BotSettingsGroup(stringResource(R.string.specialist_detail_group_appearance_apps)) {
                            BotSettingsRow(
                                label = stringResource(R.string.specialist_detail_row_appearance),
                                icon = Icons.Outlined.Palette,
                                modifier = Modifier.testTag("detail_row_appearance"),
                                onClick = { activeEditor = DetailEditorSheet.APPEARANCE },
                                avatarPreview = {
                                    BotAvatar(
                                        name = displayName,
                                        avatar = avatar,
                                        size = 32.dp,
                                        showPresence = false,
                                        modifier = Modifier.clearAndSetSemantics {},
                                    )
                                },
                            )
                            state.setupSnapshot?.apps?.let { apps ->
                                BotSettingsRow(
                                    label = stringResource(R.string.specialist_apps_label),
                                    icon = Icons.Outlined.Extension,
                                    value =
                                        specialistAppsSummary(
                                            apps,
                                        ).ifBlank { stringResource(R.string.specialist_chat_none) },
                                    modifier = Modifier.testTag("detail_row_apps"),
                                )
                            }
                        }
                    }

                    if (bot.is_default != true && bot.name != "default") {
                        item("delete") {
                            TextButton(
                                onClick = { showDeleteConfirm = true },
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                modifier = Modifier.fillMaxWidth().testTag("detail_row_delete"),
                            ) {
                                Text(stringResource(R.string.specialist_detail_row_delete))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BotDetailDescription(
    description: String,
    onEdit: () -> Unit,
) {
    if (description.isBlank()) {
        TextButton(onClick = onEdit, modifier = Modifier.testTag("detail_row_soul")) {
            Text(stringResource(R.string.specialist_detail_row_soul))
        }
        return
    }

    var expanded by rememberSaveable(description) { mutableStateOf(false) }
    var overflows by remember(description) { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                modifier = Modifier.weight(1f, fill = false),
            )
            IconButton(onClick = onEdit, modifier = Modifier.testTag("detail_row_soul")) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.specialist_detail_row_soul),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (overflows || expanded) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    stringResource(
                        if (expanded) R.string.prompt_disclosure_show_less else R.string.prompt_disclosure_show_more,
                    ),
                )
            }
        }
    }
}

@Composable
private fun BotSettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun BotSettingsRow(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    avatarPreview: (@Composable () -> Unit)? = null,
) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = secondary, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1.6f),
        )
        avatarPreview?.invoke()
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = secondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
