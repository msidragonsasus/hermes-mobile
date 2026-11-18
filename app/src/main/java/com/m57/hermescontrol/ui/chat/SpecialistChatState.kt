package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SpecialistCapabilitiesResponse
import com.m57.hermescontrol.data.model.SpecialistSetupApplyRequest
import com.m57.hermescontrol.data.model.SpecialistSetupConfirmRequest
import com.m57.hermescontrol.data.model.SpecialistSetupSnapshot
import com.m57.hermescontrol.data.model.SpecialistSetupTurn
import com.m57.hermescontrol.data.model.SpecialistSetupTurnRequest
import com.m57.hermescontrol.data.remote.SpecialistApiOutcome
import com.m57.hermescontrol.data.remote.SpecialistCapabilitiesState
import com.m57.hermescontrol.data.remote.SpecialistRequestScope
import com.m57.hermescontrol.data.remote.SpecialistsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

internal data class SpecialistChatTarget(
    val scope: String,
    val epoch: Long,
    val profile: String,
) {
    val requestScope: SpecialistRequestScope get() = SpecialistRequestScope(epoch)
}

@Serializable
internal data class SpecialistChatRequest(
    val turn: SpecialistSetupTurnRequest? = null,
    val confirm: SpecialistSetupConfirmRequest? = null,
    val edit: Boolean = false,
    val apply: SpecialistSetupApplyRequest? = null,
    val rejected: Boolean = false,
)

internal data class SpecialistChatState(
    val target: SpecialistChatTarget? = null,
    val sessionId: String? = null,
    val capabilities: SpecialistCapabilitiesResponse? = null,
    val legacy: Boolean = false,
    val missing: Boolean = false,
    val snapshot: SpecialistSetupSnapshot? = null,
    val editing: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val request: SpecialistChatRequest? = null,
    val error: String? = null,
    val invalidDraftId: String? = null,
    val referencedEdit: SpecialistEditReference? = null,
    val dismissedPendingId: String? = null,
) {
    val ready: Boolean get() =
        !missing && snapshot != null && capabilities?.supportsSetupChat == true &&
            sessionId != null
    val editSupported: Boolean get() = capabilities?.edit_conversation == true
    val pendingApply: Boolean get() = snapshot?.activation == "pending_apply"
    val canEnterEdit: Boolean
        get() =
            editSupported && snapshot?.setup_completed == true &&
                (!pendingApply || editing || error == "stale_settings_files")
    val turnMode: String?
        get() =
            when {
                snapshot?.setup_completed == false -> "setup"
                editing && editSupported -> "edit"
                else -> null
            }
    val blocksNormalInput: Boolean
        get() =
            !legacy && !missing &&
                (
                    snapshot == null || pendingApply || turnMode != null || request?.confirm != null ||
                        request?.apply != null
                )
    val pendingTurn get() = snapshot?.pending_turn?.takeUnless { it.request_id == dismissedPendingId }
    val activeDraft
        get() =
            snapshot?.let { current ->
                current.draft?.takeIf { draft ->
                    draft.id != invalidDraftId &&
                        when (draft.kind) {
                            "edit" -> {
                                val referenceMatches =
                                    referencedEdit?.let {
                                        current.revision > it.revision ||
                                            (current.revision == it.revision && draft.id == it.draft_id)
                                    } ?: true
                                editSupported && current.setup_completed && referenceMatches &&
                                    current.revision > (current.settings_revision ?: -1) && draft.before != null &&
                                    draft.after != null
                            }

                            "setup" -> {
                                !current.setup_completed &&
                                    current.conversation_turns.any { it.card?.id == draft.id }
                            }

                            else -> {
                                false
                            }
                        }
                }
            }
    val canAct: Boolean get() = ready && !loading && !busy && request == null && pendingTurn == null
}

@Serializable
internal data class SpecialistEditReference(
    val ok: Boolean = false,
    val kind: String = "",
    val draft_id: String = "",
    val revision: Int = -1,
    val session_id: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(result: String?): SpecialistEditReference? =
            result?.let { raw ->
                runCatching { json.decodeFromString<SpecialistEditReference>(raw) }.getOrNull()?.takeIf {
                    it.ok && it.kind == "specialist_edit_proposal" && it.draft_id.isNotBlank() && it.revision >= 0
                }
            }
    }
}

internal class SpecialistChatController(
    private val scope: CoroutineScope,
    private val repository: SpecialistsRepository,
    private val isCurrent: (SpecialistChatTarget) -> Boolean,
    private val readRequest: suspend (String) -> SpecialistChatRequest?,
    private val writeRequest: suspend (String, SpecialistChatRequest?) -> Unit,
    private val resolveSession: () -> Unit,
    private val refreshRoster: () -> Unit,
    private val nowSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    private val mutableState = MutableStateFlow(SpecialistChatState())
    val state = mutableState.asStateFlow()
    private var work: Job? = null
    private var poll: Job? = null
    private var refreshQueued = false

    fun bind(
        target: SpecialistChatTarget?,
        sessionId: String?,
        seed: SpecialistSetupSnapshot? = null,
    ) {
        val current = state.value
        if (target != current.target) {
            work?.cancel()
            work = null
            poll?.cancel()
            refreshQueued = false
            mutableState.value = SpecialistChatState(target = target, sessionId = sessionId, snapshot = seed)
        } else if (sessionId != current.sessionId) {
            mutableState.value = current.copy(sessionId = sessionId)
        }
        if (target != null) refresh()
    }

    fun setEditing(editing: Boolean) {
        val current = state.value
        if (current.canEnterEdit && current.canAct) {
            mutableState.value = current.copy(editing = editing, error = current.error.takeIf { current.pendingApply })
        }
    }

    fun submit(text: String): Boolean {
        val current = state.value
        val snapshot = current.snapshot ?: return false
        val sessionId = current.sessionId ?: return false
        val mode = current.turnMode ?: return false
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !current.canAct) return false
        if (trimmed.codePointCount(0, trimmed.length) > 8000) {
            mutableState.value = current.copy(error = "invalid_specialist_request")
            return false
        }
        return send(
            SpecialistChatRequest(
                turn =
                    SpecialistSetupTurnRequest(
                        newId(),
                        sessionId,
                        snapshot.revision,
                        snapshot.conversation_revision,
                        trimmed,
                        mode,
                    ),
            ),
        )
    }

    fun confirm(
        draftId: String,
        revision: Int,
    ) {
        val current = state.value
        val draft = current.activeDraft ?: return
        if (!current.canAct || draft.id != draftId || current.snapshot?.revision != revision) return
        send(
            SpecialistChatRequest(
                confirm = SpecialistSetupConfirmRequest(revision, draftId, newId()),
                edit =
                    draft.kind == "edit",
            ),
        )
    }

    fun apply() {
        val current = state.value
        val revision = current.snapshot?.settings_revision ?: return
        val sessionId = current.sessionId ?: return
        if (!current.canAct || !current.pendingApply || current.editing || current.activeDraft?.kind == "edit") return
        send(SpecialistChatRequest(apply = SpecialistSetupApplyRequest(newId(), sessionId, revision)))
    }

    fun retry() {
        val current = state.value
        val retained = current.request
        if (retained != null) {
            if (!retained.rejected && !current.loading && !current.busy &&
                current.pendingTurn?.expires_at?.let { it > nowSeconds() } != true
            ) {
                send(retained)
            }
            return
        }
        val pending = current.pendingTurn
        val snapshot = current.snapshot
        val sessionId = current.sessionId
        if (pending != null && snapshot != null && sessionId != null && pending.expires_at <= nowSeconds()) {
            val mode = if (snapshot.setup_completed) "edit" else "setup"
            send(
                SpecialistChatRequest(
                    turn =
                        SpecialistSetupTurnRequest(
                            pending.request_id,
                            sessionId,
                            snapshot.revision,
                            snapshot.conversation_revision,
                            pending.message,
                            mode,
                        ),
                ),
            )
        } else {
            refresh()
        }
    }

    fun recoverText() {
        val current = state.value
        val request = current.request
        val pending = current.pendingTurn
        val target = current.target ?: return
        if (current.loading || current.busy || !isCurrent(target) ||
            (request?.rejected != true && (pending == null || pending.expires_at > nowSeconds()))
        ) {
            return
        }
        val pendingId = request?.turn?.request_id ?: pending?.request_id
        mutableState.value = current.copy(busy = true)
        work =
            scope.launch {
                if (persist(target, null)) {
                    update(target) {
                        it.copy(
                            request = null,
                            error = null,
                            dismissedPendingId = pendingId,
                            editing = it.snapshot?.setup_completed == true && !it.pendingApply && it.editSupported,
                        )
                    }
                }
                finish(target)
            }
    }

    fun onEditProposal(reference: SpecialistEditReference) {
        val current = state.value
        if (current.sessionId != reference.session_id || !current.editSupported) return
        if ((current.snapshot?.revision ?: -1) >= reference.revision ||
            (current.referencedEdit?.revision ?: -1) > reference.revision
        ) {
            return
        }
        mutableState.value = current.copy(referencedEdit = reference)
        refresh()
    }

    fun refresh() {
        val target = state.value.target ?: return
        if (!isCurrent(target)) return
        if (work?.isActive == true) {
            refreshQueued = true
            return
        }
        poll?.cancel()
        mutableState.value = state.value.copy(loading = true)
        work =
            scope.launch {
                load(target, discover = true)
                finish(target)
            }
    }

    private fun send(request: SpecialistChatRequest): Boolean {
        val current = state.value
        val target = current.target ?: return false
        if (!current.ready || current.loading || current.busy || !isCurrent(target)) return false
        if (request.turn?.session_id?.let { it != current.sessionId } == true ||
            request.apply?.session_id?.let { it != current.sessionId } == true
        ) {
            return false
        }
        poll?.cancel()
        mutableState.value = current.copy(busy = true, request = request, error = null)
        work =
            scope.launch {
                if (!persist(target, request) || !isCurrent(target) || state.value.sessionId != current.sessionId) {
                    finish(target)
                    return@launch
                }
                val outcome =
                    when {
                        request.turn != null -> {
                            repository.sendSetupTurn(target.profile, request.turn, target.requestScope)
                        }

                        request.apply != null -> {
                            repository.applySetup(target.profile, request.apply, target.requestScope)
                        }

                        request.confirm != null && request.edit -> {
                            repository.confirmEdit(
                                target.profile,
                                request.confirm,
                                target.requestScope,
                            )
                        }

                        request.confirm != null -> {
                            repository.confirmDraft(target.profile, request.confirm, target.requestScope)
                        }

                        else -> {
                            error("Missing specialist operation")
                        }
                    }
                if (!isCurrent(target)) return@launch
                when (outcome) {
                    is SpecialistApiOutcome.Success -> {
                        onSuccess(target, outcome.data)
                    }

                    is SpecialistApiOutcome.Conflict -> {
                        onFailure(
                            target,
                            request,
                            outcome.code ?: "conflict",
                        )
                    }

                    is SpecialistApiOutcome.Validation -> {
                        onFailure(
                            target,
                            request,
                            outcome.status?.takeIf { it in 400..403 }?.let { "HTTP $it" }
                                ?: outcome.code ?: outcome.message,
                        )
                    }

                    is SpecialistApiOutcome.Unsupported -> {
                        onFailure(
                            target,
                            request,
                            "profile_missing",
                        )
                    }

                    is SpecialistApiOutcome.UncertainTransport -> {
                        update(target) { it.copy(error = "uncertain") }
                    }
                }
                load(target, discover = false)
                finish(target)
            }
        return true
    }

    private suspend fun onSuccess(
        target: SpecialistChatTarget,
        result: Any,
    ) {
        update(target) { current ->
            val snapshot = current.snapshot ?: return@update current
            when (result) {
                is SpecialistSetupTurn -> {
                    if (result.conversation_revision <= snapshot.conversation_revision) {
                        current
                    } else {
                        current.copy(
                            snapshot =
                                snapshot.copy(
                                    revision = result.revision,
                                    conversation_revision = result.conversation_revision,
                                    conversation_turns = snapshot.conversation_turns + result,
                                    draft =
                                        result.card ?: snapshot.draft.takeIf { result.revision == snapshot.revision },
                                    pending_turn = null,
                                ),
                            invalidDraftId = null,
                        )
                    }
                }

                is SpecialistSetupSnapshot -> {
                    current.copy(
                        snapshot =
                            result.copy(
                                conversation_turns = result.conversation_turns.ifEmpty { snapshot.conversation_turns },
                            ),
                        editing = false,
                        invalidDraftId = null,
                    )
                }

                is com.m57.hermescontrol.data.model.SpecialistSetupApplyResponse -> {
                    current.copy(
                        snapshot =
                            snapshot.copy(
                                activation = result.activation,
                                applied_revision = result.applied_revision,
                            ),
                        editing = false,
                    )
                }

                else -> {
                    current
                }
            }
        }
        if (persist(target, null)) update(target) { it.copy(request = null, error = null) }
        if (result !is SpecialistSetupTurn) refreshRoster()
    }

    private suspend fun onFailure(
        target: SpecialistChatTarget,
        request: SpecialistChatRequest,
        code: String,
    ) {
        val retryable = code in setOf("specialist_turn_busy", "bot_chat_busy", "settings_apply_incomplete")
        val retained = if (retryable) request else request.takeIf { it.turn != null }?.copy(rejected = true)
        if (persist(target, retained)) {
            update(target) {
                it.copy(
                    request = retained,
                    error = code,
                    invalidDraftId =
                        if (code == "stale_setup_draft" || code == "stale_settings_files") {
                            it.snapshot?.draft?.id
                        } else {
                            it.invalidDraftId
                        },
                )
            }
        }
        when (code) {
            "bot_chat_missing", "stale_bot_chat_session" -> {
                update(target) { it.copy(sessionId = null) }
                resolveSession()
            }

            "profile_missing", "specialist_state_missing", "HTTP 400" -> {
                refreshRoster()
            }

            else -> {
                Unit
            }
        }
    }

    private suspend fun load(
        target: SpecialistChatTarget,
        discover: Boolean,
    ) {
        if (!isCurrent(target)) return
        if (discover && state.value.capabilities == null && !state.value.legacy) {
            when (val flags = repository.fetchCapabilities(target.requestScope)) {
                is SpecialistCapabilitiesState.Supported -> {
                    update(
                        target,
                    ) { it.copy(capabilities = flags.capabilities, legacy = !flags.capabilities.supportsSetupChat) }
                }

                is SpecialistCapabilitiesState.LegacyUnsupported -> {
                    update(target) { it.copy(legacy = true) }
                }

                else -> {
                    update(target) { it.copy(error = "unavailable") }
                }
            }
        }
        if (!isCurrent(target) || state.value.capabilities?.supportsSetupChat != true) return
        val saved =
            try {
                readRequest(target.scope)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                update(target) { it.copy(error = "storage") }
                null
            }
        if (!isCurrent(target)) return
        if (state.value.request == null && saved != null) {
            update(target) { it.copy(request = saved, editing = saved.turn?.mode == "edit") }
        }
        when (val outcome = repository.getSetup(target.profile, target.requestScope)) {
            is SpecialistApiOutcome.Success -> {
                val snapshot = outcome.data
                if (snapshot.name != target.profile) return
                update(target) { current ->
                    val previous = current.snapshot
                    if (previous != null && (
                            snapshot.revision < previous.revision ||
                                snapshot.conversation_revision < previous.conversation_revision
                        )
                    ) {
                        current
                    } else {
                        current.copy(
                            snapshot = snapshot,
                            missing = false,
                            error = current.error.takeUnless { it == "unavailable" },
                        )
                    }
                }
                val current = state.value.takeIf { it.target == target } ?: return
                val request = current.request ?: return
                val requestedSession = request.turn?.session_id ?: request.apply?.session_id
                if (request.isObservedIn(snapshot) && persist(target, null)) {
                    update(target) { it.copy(request = null, error = null) }
                } else if (!request.rejected && requestedSession != null && current.sessionId != null &&
                    current.sessionId != requestedSession
                ) {
                    val retained = request.takeIf { it.turn != null }?.copy(rejected = true)
                    if (persist(target, retained)) {
                        update(target) { it.copy(request = retained, error = "stale_bot_chat_session") }
                    }
                }
            }

            is SpecialistApiOutcome.Unsupported -> {
                update(target) {
                    it.copy(missing = true, error = outcome.code ?: "profile_missing")
                }
            }

            is SpecialistApiOutcome.Validation -> {
                update(target) { it.copy(error = outcome.code ?: outcome.message) }
            }

            else -> {
                update(target) { it.copy(error = it.error ?: "unavailable") }
            }
        }
    }

    private fun SpecialistChatRequest.isObservedIn(snapshot: SpecialistSetupSnapshot): Boolean =
        when {
            turn != null -> {
                snapshot.conversation_turns.any { it.request_id == turn.request_id }
            }

            apply != null -> {
                snapshot.applied_revision == apply.expected_revision
            }

            confirm != null -> {
                snapshot.draft?.id == confirm.draft_id &&
                    (snapshot.settings_revision ?: -1) > confirm.expected_revision
            }

            else -> {
                false
            }
        }

    private suspend fun persist(
        target: SpecialistChatTarget,
        request: SpecialistChatRequest?,
    ): Boolean =
        try {
            writeRequest(target.scope, request)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            update(target) { it.copy(error = "storage") }
            false
        }

    private fun finish(target: SpecialistChatTarget) {
        if (!isCurrent(target) || state.value.target != target) return
        work = null
        update(target) { it.copy(loading = false, busy = false) }
        if (refreshQueued) {
            refreshQueued = false
            refresh()
        } else if (state.value.pendingTurn
                ?.expires_at
                ?.let { it > nowSeconds() } == true
        ) {
            poll =
                scope.launch {
                    delay(2_000)
                    refresh()
                }
        }
    }

    private fun update(
        target: SpecialistChatTarget,
        transform: (SpecialistChatState) -> SpecialistChatState,
    ) {
        val current = state.value
        if (current.target == target && isCurrent(target)) mutableState.value = transform(current)
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
