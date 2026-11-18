package com.m57.hermescontrol.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.SpecialistSetupConfirmRequest
import com.m57.hermescontrol.data.model.SpecialistSetupDraft
import com.m57.hermescontrol.data.model.SpecialistSetupDraftRequest
import com.m57.hermescontrol.data.model.SpecialistSetupSnapshot
import com.m57.hermescontrol.data.remote.SpecialistApiOutcome
import com.m57.hermescontrol.data.remote.SpecialistsRepository
import com.m57.hermescontrol.ui.common.HermesTextField
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.PrimaryPillButton
import kotlinx.coroutines.launch
import java.util.UUID

internal data class SpecialistSetupState(
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val snapshot: SpecialistSetupSnapshot? = null,
    val missingOnServer: Boolean = false,
    val staleConflict: Boolean = false,
    val errorMessage: String? = null,
    val uncertainConfirm: Boolean = false,
    val roleInput: String = "",
    val sourcesInput: String = "",
    val selectedSkills: Set<String> = emptySet(),
    val autonomyInput: String = "",
    val routinesInput: String = "",
    val confirmRequestId: String = UUID.randomUUID().toString(),
    val sendConfirmedRoleInChat: Boolean = true,
    val confirmedRoleSentKeys: Set<String> = emptySet(),
    val ownerMessageDispatchNote: String? = null,
)

internal fun splitSetupEntries(raw: String): List<String> =
    raw
        .split('\n', ',', '、')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

internal fun formatConfirmedRoleOwnerMessage(draft: SpecialistSetupDraft): String =
    buildString {
        appendLine("初期セットアップを確定しました。この役割で進めてください。")
        if (draft.description.isNotBlank()) {
            appendLine("・担当: ${draft.description}")
        }
        if (draft.sources.isNotEmpty()) {
            appendLine("・情報源: ${draft.sources.joinToString(", ")}")
        }
        if (draft.skills.isNotEmpty()) {
            appendLine("・許可スキル: ${draft.skills.joinToString(", ")}")
        }
        if (draft.autonomy.isNotBlank()) {
            append("・確認不要の範囲: ${draft.autonomy}")
        }
    }.trim()

/** Pure state machine helpers for unit-testing revision/draft/confirm/409/404 transitions. */
internal object SpecialistSetupReducer {
    fun onSnapshotLoaded(
        state: SpecialistSetupState,
        snapshot: SpecialistSetupSnapshot,
        preserveUserEdits: Boolean = false,
    ): SpecialistSetupState {
        val draft = snapshot.draft
        val shouldSeedInputs =
            !preserveUserEdits &&
                state.roleInput.isBlank() &&
                state.autonomyInput.isBlank() &&
                state.sourcesInput.isBlank() &&
                state.routinesInput.isBlank() &&
                draft != null
        return state.copy(
            isLoading = false,
            isWorking = false,
            snapshot = snapshot,
            missingOnServer = false,
            staleConflict = false,
            errorMessage = null,
            uncertainConfirm = false,
            roleInput = if (shouldSeedInputs) draft.description else state.roleInput,
            sourcesInput = if (shouldSeedInputs) draft.sources.joinToString("\n") else state.sourcesInput,
            selectedSkills =
                if (shouldSeedInputs) {
                    draft.skills.toSet()
                } else {
                    state.selectedSkills.intersect(snapshot.available_skills.toSet())
                },
            autonomyInput = if (shouldSeedInputs) draft.autonomy else state.autonomyInput,
            routinesInput = if (shouldSeedInputs) draft.routines.joinToString("\n") else state.routinesInput,
            confirmRequestId = UUID.randomUUID().toString(),
        )
    }

    fun onOutcome(
        state: SpecialistSetupState,
        outcome: SpecialistApiOutcome<SpecialistSetupSnapshot>,
        isConfirmAction: Boolean,
        preserveEditsOnReload: Boolean = false,
    ): SpecialistSetupState =
        when (outcome) {
            is SpecialistApiOutcome.Success -> {
                onSnapshotLoaded(state, outcome.data, preserveUserEdits = preserveEditsOnReload)
            }

            is SpecialistApiOutcome.Unsupported -> {
                state.copy(
                    isLoading = false,
                    isWorking = false,
                    missingOnServer = true,
                    errorMessage = null,
                    uncertainConfirm = false,
                )
            }

            is SpecialistApiOutcome.Conflict -> {
                val isStale =
                    outcome.code in
                        setOf("stale_setup_revision", "stale_setup_draft", "setup_already_confirmed")
                state.copy(
                    isLoading = false,
                    isWorking = false,
                    staleConflict = isStale,
                    errorMessage = if (isStale) null else outcome.message,
                    uncertainConfirm = false,
                )
            }

            is SpecialistApiOutcome.Validation -> {
                state.copy(
                    isLoading = false,
                    isWorking = false,
                    staleConflict = false,
                    errorMessage = outcome.message,
                    uncertainConfirm = false,
                )
            }

            is SpecialistApiOutcome.UncertainTransport -> {
                state.copy(
                    isLoading = false,
                    isWorking = false,
                    errorMessage = if (isConfirmAction) null else outcome.message,
                    uncertainConfirm = isConfirmAction,
                )
            }
        }

    fun dispatchConfirmedRoleIfEligible(
        state: SpecialistSetupState,
        profileName: String,
        confirmedSnapshot: SpecialistSetupSnapshot,
        draft: SpecialistSetupDraft,
        isChatConnected: Boolean,
        canSendToCurrentChat: Boolean,
        onSendConfirmedRoleMessage: ((String) -> Boolean)?,
    ): SpecialistSetupState {
        val sendKey = "$profileName:${confirmedSnapshot.revision}:${draft.id}"
        if (!state.sendConfirmedRoleInChat ||
            !canSendToCurrentChat ||
            onSendConfirmedRoleMessage == null ||
            sendKey in state.confirmedRoleSentKeys
        ) {
            return state
        }
        if (!isChatConnected) {
            return state.copy(
                ownerMessageDispatchNote = "現在オフラインのため会話への送信はスキップしました（設定自体は確定済みです）。",
            )
        }
        val sent = onSendConfirmedRoleMessage(formatConfirmedRoleOwnerMessage(draft))
        // Mark key as attempted regardless of outcome so unknown send results are never replayed.
        val updatedKeys = state.confirmedRoleSentKeys + sendKey
        val note =
            if (sent) {
                "確定した役割を会話へ1通送信しました（自動再送は行いません）。"
            } else {
                "会話への送信状態が確定できなかったため、自動再送は行いませんでした。"
            }
        return state.copy(confirmedRoleSentKeys = updatedKeys, ownerMessageDispatchNote = note)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpecialistSetupSheet(
    profileName: String,
    displayTitle: String,
    isChatConnected: Boolean,
    canSendToCurrentChat: Boolean,
    onSendConfirmedRoleMessage: ((String) -> Boolean)? = null,
    onConfirmed: (SpecialistSetupSnapshot) -> Unit = {},
    onDismiss: () -> Unit,
    repository: SpecialistsRepository = remember { SpecialistsRepository() },
) {
    val scope = rememberCoroutineScope()
    var state by remember(profileName) { mutableStateOf(SpecialistSetupState()) }

    fun loadSnapshot(preserveEdits: Boolean = false) {
        state = state.copy(isLoading = true, errorMessage = null, staleConflict = false)
        scope.launch {
            val outcome = repository.getSetup(profileName)
            state =
                SpecialistSetupReducer.onOutcome(
                    state,
                    outcome,
                    isConfirmAction = false,
                    preserveEditsOnReload = preserveEdits,
                )
        }
    }

    LaunchedEffect(profileName) {
        loadSnapshot(preserveEdits = false)
    }

    fun submitDraft() {
        val current = state.snapshot ?: return
        val role = state.roleInput.trim()
        val autonomy = state.autonomyInput.trim()
        if (role.isBlank() || autonomy.isBlank()) {
            state = state.copy(errorMessage = "「担当・役割」と「確認なしで進めてよい範囲」を入力してください。")
            return
        }
        state = state.copy(isWorking = true, errorMessage = null, staleConflict = false, uncertainConfirm = false)
        scope.launch {
            val outcome =
                repository.saveDraft(
                    profileName,
                    SpecialistSetupDraftRequest(
                        expected_revision = current.revision,
                        role = role,
                        sources = splitSetupEntries(state.sourcesInput),
                        skills = state.selectedSkills.toList().sorted(),
                        autonomy = autonomy,
                        routines = splitSetupEntries(state.routinesInput),
                    ),
                )
            state =
                SpecialistSetupReducer.onOutcome(state, outcome, isConfirmAction = false, preserveEditsOnReload = true)
        }
    }

    fun confirmDraft(reuseRequestId: Boolean) {
        val current = state.snapshot ?: return
        val draft = current.draft ?: return
        val reqId =
            if (reuseRequestId) {
                state.confirmRequestId
            } else {
                UUID.randomUUID().toString().also { state = state.copy(confirmRequestId = it) }
            }
        state = state.copy(isWorking = true, errorMessage = null, staleConflict = false, uncertainConfirm = false)
        scope.launch {
            val outcome =
                repository.confirmDraft(
                    profileName,
                    SpecialistSetupConfirmRequest(
                        expected_revision = current.revision,
                        draft_id = draft.id,
                        request_id = reqId,
                    ),
                )
            val next =
                SpecialistSetupReducer.onOutcome(
                    state,
                    outcome,
                    isConfirmAction = true,
                    preserveEditsOnReload = true,
                )
            if (outcome is SpecialistApiOutcome.Success && outcome.data.setup_completed) {
                val confirmedSnap = outcome.data
                state =
                    SpecialistSetupReducer.dispatchConfirmedRoleIfEligible(
                        state = next,
                        profileName = profileName,
                        confirmedSnapshot = confirmedSnap,
                        draft = draft,
                        isChatConnected = isChatConnected,
                        canSendToCurrentChat = canSendToCurrentChat,
                        onSendConfirmedRoleMessage = onSendConfirmedRoleMessage,
                    )
                onConfirmed(confirmedSnap)
            } else {
                state = next
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!state.isWorking) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("specialist_setup_sheet"),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.specialist_setup_title, displayTitle),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            if (state.isLoading || state.isWorking) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (state.missingOnServer) {
                Spacer(modifier = Modifier.height(10.dp))
                InlineNotice(
                    text = stringResource(R.string.specialist_setup_missing),
                    tone = NoticeTone.Warning,
                )
            }

            if (state.staleConflict) {
                Spacer(modifier = Modifier.height(10.dp))
                InlineNotice(
                    text = stringResource(R.string.specialist_setup_stale_notice),
                    tone = NoticeTone.Warning,
                    actionLabel = stringResource(R.string.specialist_setup_refresh_latest),
                    onAction = { loadSnapshot(preserveEdits = true) },
                    modifier = Modifier.testTag("setup_stale_notice"),
                )
            }

            if (state.uncertainConfirm) {
                Spacer(modifier = Modifier.height(10.dp))
                InlineNotice(
                    text = stringResource(R.string.specialist_setup_confirm_uncertain),
                    tone = NoticeTone.Warning,
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { confirmDraft(reuseRequestId = true) },
                    modifier = Modifier.testTag("setup_confirm_uncertain"),
                )
            }

            state.errorMessage?.let { err ->
                Spacer(modifier = Modifier.height(10.dp))
                InlineNotice(
                    text = err,
                    tone = NoticeTone.Error,
                    modifier = Modifier.testTag("setup_error_notice"),
                )
            }

            val snapshot = state.snapshot
            if (snapshot != null) {
                if (snapshot.setup_completed) {
                    Spacer(modifier = Modifier.height(10.dp))
                    InlineNotice(
                        text = stringResource(R.string.specialist_setup_persisted_next_boundary),
                        tone = NoticeTone.Info,
                        modifier = Modifier.testTag("setup_persisted_notice"),
                    )
                }

                state.ownerMessageDispatchNote?.let { note ->
                    Spacer(modifier = Modifier.height(8.dp))
                    InlineNotice(text = note, tone = NoticeTone.Info)
                }

                if (snapshot.questions.isNotEmpty() && !snapshot.setup_completed) {
                    Spacer(modifier = Modifier.height(12.dp))
                    snapshot.questions.forEachIndexed { idx, q ->
                        Text(
                            text = "${idx + 1}. $q",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }

                if (!snapshot.setup_completed) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HermesTextField(
                        value = state.roleInput,
                        onValueChange = { state = state.copy(roleInput = it.take(4000)) },
                        label = { Text(stringResource(R.string.specialist_setup_role_label)) },
                        minLines = 2,
                        maxLines = 4,
                        enabled = !state.isWorking,
                        modifier = Modifier.fillMaxWidth().testTag("setup_role_input"),
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    HermesTextField(
                        value = state.sourcesInput,
                        onValueChange = { state = state.copy(sourcesInput = it.take(4000)) },
                        label = { Text(stringResource(R.string.specialist_setup_sources_label)) },
                        minLines = 1,
                        maxLines = 3,
                        enabled = !state.isWorking,
                        modifier = Modifier.fillMaxWidth().testTag("setup_sources_input"),
                    )

                    if (snapshot.available_skills.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.specialist_setup_skills_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            snapshot.available_skills.forEach { skill ->
                                val selected = skill in state.selectedSkills
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        val nextSkills =
                                            if (selected) state.selectedSkills - skill else state.selectedSkills + skill
                                        state = state.copy(selectedSkills = nextSkills)
                                    },
                                    enabled = !state.isWorking,
                                    label = { Text(skill) },
                                    modifier = Modifier.testTag("setup_skill_$skill"),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HermesTextField(
                        value = state.autonomyInput,
                        onValueChange = { state = state.copy(autonomyInput = it.take(4000)) },
                        label = { Text(stringResource(R.string.specialist_setup_autonomy_label)) },
                        minLines = 2,
                        maxLines = 3,
                        enabled = !state.isWorking,
                        modifier = Modifier.fillMaxWidth().testTag("setup_autonomy_input"),
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    HermesTextField(
                        value = state.routinesInput,
                        onValueChange = { state = state.copy(routinesInput = it.take(4000)) },
                        label = { Text(stringResource(R.string.specialist_setup_routines_label)) },
                        minLines = 1,
                        maxLines = 3,
                        enabled = !state.isWorking,
                        modifier = Modifier.fillMaxWidth().testTag("setup_routines_input"),
                    )
                    Text(
                        text = stringResource(R.string.specialist_setup_routines_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = ::submitDraft,
                            enabled = !state.isWorking,
                            modifier = Modifier.testTag("setup_build_draft_button"),
                        ) {
                            Text(stringResource(R.string.specialist_setup_build_draft))
                        }
                    }
                }

                snapshot.draft?.let { draft ->
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "下書き（Revision ${snapshot.revision}）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().testTag("setup_draft_preview"),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("担当: ${draft.description}", style = MaterialTheme.typography.bodySmall)
                            if (draft.sources.isNotEmpty()) {
                                Text(
                                    "情報源: ${draft.sources.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                "許可スキル: ${if (draft.skills.isEmpty()) "なし" else draft.skills.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text("確認不要範囲: ${draft.autonomy}", style = MaterialTheme.typography.bodySmall)
                            if (draft.routines.isNotEmpty()) {
                                Text(
                                    "定例作業案（無効のまま保存）: ${draft.routines.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (!snapshot.setup_completed) {
                        if (canSendToCurrentChat && onSendConfirmedRoleMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Checkbox(
                                    checked = state.sendConfirmedRoleInChat,
                                    onCheckedChange = { state = state.copy(sendConfirmedRoleInChat = it) },
                                    enabled = !state.isWorking,
                                )
                                Text(
                                    text = stringResource(R.string.specialist_setup_send_role_toggle),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = onDismiss, enabled = !state.isWorking) {
                                Text(stringResource(R.string.action_close))
                            }
                            PrimaryPillButton(
                                onClick = { confirmDraft(reuseRequestId = false) },
                                enabled = !state.isWorking,
                                modifier = Modifier.testTag("setup_confirm_button"),
                            ) {
                                Text(stringResource(R.string.specialist_setup_confirm))
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            PrimaryPillButton(onClick = onDismiss) {
                                Text(stringResource(R.string.action_close))
                            }
                        }
                    }
                } ?: run {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                    }
                }
            } else if (!state.isLoading) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
