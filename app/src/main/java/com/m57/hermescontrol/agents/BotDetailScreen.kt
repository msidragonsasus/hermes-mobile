package com.m57.hermescontrol.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
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
import com.m57.hermescontrol.data.remote.SpecialistsRepository
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.bots.BotsViewModel
import com.m57.hermescontrol.ui.bots.EditBotBottomSheet
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
    val scope = rememberCoroutineScope()
    var activeEditor by remember { mutableStateOf<DetailEditorSheet?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(profileName) {
        detailViewModel.load()
    }

    val bot = state.profile
    val setupIncomplete =
        state.setupSnapshot?.setup_completed == false || (bot?.isSetupIncomplete == true && state.setupSnapshot == null)

    if (showDeleteConfirm && bot != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    text = stringResource(R.string.bots_edit_delete_confirm_title, bot.effectiveTitle),
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
                    displayTitle = bot.effectiveTitle,
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
        title = {
            Text(
                text = bot?.effectiveTitle ?: profileName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        showShellMenu = false,
        modifier = Modifier.testTag("bot_detail_screen"),
    ) { innerPadding ->
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
                val approvalLabel =
                    when (state.approvalMode) {
                        "smart" -> "smart"
                        "off" -> "off"
                        else -> "manual"
                    }
                val enabledSkillCount = state.skills.count { it.enabled }
                val activeCronCount = state.cronJobs.count { it.state != "paused" }
                val rows =
                    listOf(
                        Triple(
                            "soul",
                            Icons.Outlined.Description to stringResource(R.string.specialist_detail_row_soul),
                            bot.effectiveDescription.ifBlank { "未設定" },
                        ),
                        Triple(
                            "routines",
                            Icons.Outlined.Schedule to stringResource(R.string.specialist_detail_row_routines),
                            "${activeCronCount}件有効",
                        ),
                        Triple(
                            "skills",
                            Icons.Outlined.AutoAwesome to stringResource(R.string.specialist_detail_row_skills),
                            "$enabledSkillCount/${state.skills.size}件",
                        ),
                        Triple(
                            "approvals",
                            Icons.Outlined.Shield to stringResource(R.string.specialist_detail_row_approvals),
                            approvalLabel,
                        ),
                        Triple(
                            "model",
                            Icons.Outlined.Tune to stringResource(R.string.specialist_detail_row_model),
                            bot.model?.takeIf { it.isNotBlank() } ?: "既定",
                        ),
                        Triple(
                            "appearance",
                            Icons.Outlined.Palette to stringResource(R.string.specialist_detail_row_appearance),
                            bot.effectiveTitle,
                        ),
                    )

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
                ) {
                    if (state.saving) {
                        item("saving") {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    state.notice?.let { note ->
                        item("notice") {
                            Spacer(Modifier.height(8.dp))
                            InlineNotice(
                                text = note,
                                tone = NoticeTone.Info,
                                actionLabel = stringResource(R.string.action_close),
                                onAction = detailViewModel::clearNotice,
                            )
                        }
                    }

                    state.error?.let { err ->
                        item("error") {
                            Spacer(Modifier.height(8.dp))
                            InlineNotice(text = err, tone = NoticeTone.Error)
                        }
                    }

                    item("header") {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BotAvatar(
                                    name = bot.name,
                                    avatar = bot.botMeta()?.avatar,
                                    size = 52.dp,
                                    showPresence = false,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = bot.effectiveTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "@${bot.name}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                Surface(
                                    color =
                                        if (setupIncomplete) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        text =
                                            stringResource(
                                                if (setupIncomplete) {
                                                    R.string.specialist_detail_status_incomplete
                                                } else {
                                                    R.string.specialist_detail_status_ready
                                                },
                                            ),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }

                            Spacer(Modifier.height(14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PrimaryPillButton(
                                    onClick = {
                                        scope.launch {
                                            val canonicalId =
                                                bot.canonical_session?.resolved_id ?: bot.canonical_session?.id
                                            BotConversations.openSpecialist(
                                                profileName = bot.name,
                                                canonicalSessionId = canonicalId,
                                                openSetupAfterLaunch = setupIncomplete,
                                            )
                                        }
                                    },
                                    modifier = Modifier.weight(1f).testTag("detail_open_chat"),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Chat,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.specialist_detail_open_chat))
                                }
                                if (setupIncomplete) {
                                    OutlinedButton(
                                        onClick = { activeEditor = DetailEditorSheet.SETUP },
                                        modifier = Modifier.testTag("detail_open_setup"),
                                    ) {
                                        Text(stringResource(R.string.specialist_setup_open_action))
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    items(rows, key = { it.first }) { (id, iconAndTitle, trailingValue) ->
                        val (icon, label) = iconAndTitle
                        CompactListRow(
                            onClick = {
                                when (id) {
                                    "soul" -> activeEditor = DetailEditorSheet.SOUL
                                    "routines" -> activeEditor = DetailEditorSheet.ROUTINES
                                    "skills" -> activeEditor = DetailEditorSheet.SKILLS
                                    "approvals" -> activeEditor = DetailEditorSheet.APPROVALS
                                    "model" -> NavigationController.navigateTo(BotDefaultsKey(bot.name))
                                    "appearance" -> activeEditor = DetailEditorSheet.APPEARANCE
                                }
                            },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = trailingValue,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            modifier = Modifier.testTag("detail_row_$id"),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    if (bot.is_default != true && bot.name != "default") {
                        item("delete") {
                            CompactListRow(
                                onClick = { showDeleteConfirm = true },
                                modifier = Modifier.testTag("detail_row_delete"),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = stringResource(R.string.specialist_detail_row_delete),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
