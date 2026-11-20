package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.SpecialistAppConnection
import com.m57.hermescontrol.data.model.SpecialistSettingsPreview
import com.m57.hermescontrol.data.model.SpecialistSetupMessage
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.components.ConversationActionCard

internal const val SPECIALIST_SETUP_REVIEW = "specialist-setup-review"

internal fun ChatMessage.isSpecialistSetupText(): Boolean = id.startsWith("specialist:")

private fun SpecialistSetupMessage.asChatMessage(): ChatMessage =
    ChatMessage(
        id = "specialist:$id",
        role = if (role == "user") MessageRole.USER else MessageRole.ASSISTANT,
        content = content,
        timestamp = 0L,
    )

private fun marker(id: String) = ChatMessage(id = id, role = MessageRole.SYSTEM, content = "", timestamp = 0L)

/** Setup text belongs to the specialist REST transcript. Only this projection is passed to the renderer. */
internal fun SpecialistChatState.projectMessages(normal: List<ChatMessage>): List<ChatMessage> {
    val current = snapshot
    val turns = current?.conversation_turns.orEmpty().sortedBy { it.conversation_revision }
    val pendingId = request?.turn?.request_id ?: pendingTurn?.request_id
    val pendingText = request?.turn?.text ?: pendingTurn?.message
    val setup = turns.filter { it.mode == "setup" }
    val edits = turns.filter { it.mode == "edit" }
    return buildList {
        if (current?.setup_completed == false || setup.isNotEmpty()) {
            current?.greeting?.let { add(it.asChatMessage()) }
        }
        setup.forEach { turn ->
            add(turn.user.asChatMessage())
            add(turn.assistant.asChatMessage())
        }
        if (activeDraft?.kind == "setup") add(marker(SPECIALIST_SETUP_REVIEW))
        // The two server stores have independent orderings. Only the open edit
        // flow goes at the tail, so the renderer can still correlate ordinary replies.
        if (editing) addAll(normal)
        edits.forEach { turn ->
            add(turn.user.asChatMessage())
            add(turn.assistant.asChatMessage())
        }
        if (!editing) addAll(normal)
        if (pendingId != null && pendingText != null && turns.none { it.request_id == pendingId }) {
            add(SpecialistSetupMessage("$pendingId:user", "user", pendingText).asChatMessage())
        }
    }.distinctBy { it.id }
}

@Composable
internal fun SpecialistReviewCard(
    state: SpecialistChatState,
    canUseSession: Boolean,
    onConfirm: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.activeDraft ?: return
    val revision = state.snapshot?.revision ?: return
    val spacing = LocalSpacing.current
    var showSoul by rememberSaveable(draft.id) { mutableStateOf(false) }
    val edit = draft.kind == "edit"
    val after =
        draft.after
            ?: SpecialistSettingsPreview(
                draft.description,
                draft.sources,
                draft.skills,
                draft.autonomy,
                draft.routines,
                draft.soul,
                draft.apps,
            )
    val before = draft.before
    val none = stringResource(R.string.specialist_chat_none)
    Surface(
        modifier = modifier.fillMaxWidth().testTag("specialist_chat_review"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                stringResource(if (edit) R.string.specialist_chat_edit_draft else R.string.specialist_chat_setup_draft),
                style = MaterialTheme.typography.titleSmall,
            )
            ReviewField(
                stringResource(R.string.specialist_setup_role_label),
                before?.description,
                after.description,
                none,
            )
            ReviewField(
                stringResource(R.string.specialist_chat_sources),
                before?.sources?.joinToString("\n"),
                after.sources.joinToString("\n"),
                none,
            )
            ReviewField(
                stringResource(R.string.specialist_setup_skills_label),
                before?.skills?.joinToString("\n"),
                after.skills.joinToString("\n"),
                none,
            )
            ReviewField(
                stringResource(R.string.specialist_apps_label),
                before?.let { specialistAppsSummary(it.apps) },
                specialistAppsSummary(after.apps),
                none,
            )
            ReviewField(
                stringResource(R.string.specialist_setup_autonomy_label),
                before?.autonomy,
                after.autonomy,
                none,
            )
            ReviewField(
                stringResource(R.string.specialist_chat_routines),
                before?.routines?.joinToString("\n"),
                after.routines.joinToString("\n"),
                none,
            )
            TextButton(onClick = { showSoul = !showSoul }) {
                Text(
                    stringResource(
                        if (showSoul) R.string.specialist_chat_hide_soul else R.string.specialist_chat_show_soul,
                    ),
                )
            }
            if (showSoul) {
                ReviewField("SOUL", before?.soul, after.soul, none)
            }
            SpecialistAction(
                label = stringResource(R.string.specialist_chat_confirm),
                enabled = canUseSession && state.canConfirm(),
                onClick = { onConfirm(draft.id, revision) },
            )
        }
    }
}

@Composable
internal fun specialistAppsSummary(apps: List<SpecialistAppConnection>): String {
    val read = stringResource(R.string.specialist_app_access_read)
    val write = stringResource(R.string.specialist_app_access_write)
    val unknown = stringResource(R.string.specialist_app_access_unknown)
    val format = stringResource(R.string.specialist_app_access_format)
    return apps.joinToString("\n") { app ->
        val access =
            when (app.access) {
                "read" -> read
                "write" -> write
                else -> unknown
            }
        format.format(app.title, access)
    }
}

@Composable
private fun ReviewField(
    label: String,
    before: String?,
    after: String,
    none: String,
) {
    if (before != null && before == after) return
    Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        if (before != null) {
            Text(
                stringResource(R.string.specialist_chat_before, before.ifBlank { none }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (before ==
                null
            ) {
                after.ifBlank { none }
            } else {
                stringResource(R.string.specialist_chat_after, after.ifBlank { none })
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun SpecialistChatActions(
    state: SpecialistChatState,
    connected: Boolean,
    canUseSession: Boolean,
    onRetry: () -> Unit,
    onResolveSession: () -> Unit,
    onApply: () -> Unit,
    onReturnToChat: () -> Unit,
    onRecoverText: (String) -> Unit,
) {
    if (!state.showsFooterActions) return
    val snapshot = state.snapshot
    val pending = state.pendingTurn
    val request = state.request
    val waiting = state.hasInFlightTurn()
    val spacing = LocalSpacing.current
    ConversationActionCard(modifier = Modifier.testTag("specialist_chat_actions")) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            if (state.pendingApply) {
                Text(
                    stringResource(R.string.specialist_chat_saved_pending),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (!waiting && (state.busy || (state.loading && snapshot == null))) {
                Text(stringResource(R.string.specialist_chat_waiting), style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { error ->
                Text(stringResource(specialistErrorResource(error)), style = MaterialTheme.typography.bodySmall)
            }
            if (state.needsSessionResolution) {
                TextButton(onClick = onResolveSession, enabled = connected) {
                    Text(stringResource(R.string.specialist_chat_open_canonical))
                }
            }
            if (state.pendingApply && request == null && !state.editing && state.activeDraft?.kind != "edit") {
                SpecialistAction(stringResource(R.string.specialist_chat_apply), canUseSession && state.canAct, onApply)
            }
            val rejectedText = request?.turn?.text?.takeIf { request.rejected }
            val recoverableText = rejectedText ?: pending?.message?.takeIf { request == null && !waiting }
            if (recoverableText != null) {
                TextButton(onClick = { onRecoverText(recoverableText) }, enabled = !state.loading && !state.busy) {
                    Text(stringResource(R.string.specialist_chat_recover_text))
                }
            }
            if (request?.rejected != true && (request != null || pending != null || state.error != null) && !waiting) {
                TextButton(
                    onClick = onRetry,
                    enabled =
                        connected && !state.loading && !state.busy &&
                            (request == null || state.sessionId != null),
                ) {
                    Text(stringResource(R.string.action_retry))
                }
            }
            if (state.editing) {
                TextButton(onClick = onReturnToChat, enabled = canUseSession && state.canAct) {
                    Text(stringResource(R.string.specialist_chat_return_to_chat))
                }
            }
        }
    }
}

@Composable
private fun SpecialistAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Text(label)
    }
}

private fun specialistErrorResource(code: String): Int =
    when (code) {
        "stale_setup_revision", "stale_specialist_turn", "stale_setup_draft", "stale_settings_revision",
        "setup_already_confirmed", "setup_not_confirmed", "no_settings_pending", "invalid_specialist_turn_mode",
        "turn_request_conflict", "confirmation_request_conflict", "apply_request_conflict",
        -> {
            R.string.specialist_setup_stale_notice
        }

        "stale_specialist_apps" -> {
            R.string.specialist_apps_changed
        }

        "stale_settings_files" -> {
            R.string.specialist_chat_files_changed
        }

        "bot_chat_missing", "stale_bot_chat_session" -> {
            R.string.specialist_chat_session_changed
        }

        "specialist_turn_busy", "bot_chat_busy" -> {
            R.string.specialist_chat_busy
        }

        "settings_apply_incomplete", "uncertain" -> {
            R.string.specialist_chat_retry_saved
        }

        "specialist_model_unavailable", "invalid_specialist_model_output" -> {
            R.string.specialist_chat_model_retry
        }

        "specialist_state_missing", "profile_missing", "HTTP 400" -> {
            R.string.specialist_setup_missing
        }

        "HTTP 401", "HTTP 403" -> {
            R.string.specialist_chat_auth
        }

        "request_too_large", "invalid_setup_request", "invalid_setup_revision", "invalid_specialist_request" -> {
            R.string.specialist_chat_invalid
        }

        "no_settings_change" -> {
            R.string.specialist_chat_no_changes
        }

        "storage" -> {
            R.string.specialist_chat_storage_error
        }

        else -> {
            R.string.specialist_chat_unavailable
        }
    }
