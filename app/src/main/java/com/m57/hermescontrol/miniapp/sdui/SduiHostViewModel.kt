package com.m57.hermescontrol.miniapp.sdui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

internal enum class SduiUnavailable {
    /** 404: this server does not offer the screen. Native routes remain. */
    HOST_UNSUPPORTED,
    AUTH_REQUIRED,

    /** The payload is a contract this app does not understand, or exceeds its limits. */
    CONTRACT_UNSUPPORTED,

    /** Manifest and screen disagree about the release; reload, never retry a write. */
    RELEASE_CHANGED,
    LOAD_FAILED,

    /** Terminal for this host: a new host is built by reopening the route. */
    CONNECTION_CHANGED,
}

internal sealed interface SduiPhase {
    data object Idle : SduiPhase

    data object Loading : SduiPhase

    data class Ready(
        val screen: SduiScreen,
        val canCheck: Boolean,
    ) : SduiPhase

    data class Unavailable(
        val reason: SduiUnavailable,
    ) : SduiPhase
}

/**
 * The single MyPage-check operation of this host. Only [Idle] and [Refused] may start a new
 * operation id. [Ambiguous] survives refresh, back, re-entry and process death and can only be
 * resolved by explicitly resending the SAME id with the SAME release the first request named.
 */
internal sealed interface SduiCheckState {
    data object Idle : SduiCheckState

    data class Sending(
        val operationId: String,
        val retry: Boolean,
    ) : SduiCheckState

    data class Ambiguous(
        val operationId: String,
    ) : SduiCheckState

    /** Queued or running. A new operation needs an explicit refresh first. */
    data class Accepted(
        val receipt: SduiReceipt,
    ) : SduiCheckState

    /** The server answered and did not accept; nothing is pending. */
    data object Refused : SduiCheckState

    /** The displayed release is stale. [retained] is an unresolved operation id to keep. */
    data class NeedsReload(
        val retained: String?,
    ) : SduiCheckState

    /**
     * An earlier request may exist, but it cannot be recovered as the same request (the stored record
     * is incomplete or unreadable). Neither a new check nor a resend is possible; an explicit refresh
     * reads the record again.
     */
    data object Blocked : SduiCheckState
}

internal data class SduiUiState(
    val phase: SduiPhase = SduiPhase.Idle,
    val check: SduiCheckState = SduiCheckState.Idle,
)

/**
 * Activity-scoped, so the check state (and its operation id) survives leaving and re-entering the
 * route; [release] drops everything transient. Nothing here runs a write unless the user taps:
 * [load] and [refresh] only read. The unresolved operation (id + the release it named) is also kept
 * in [pendingStore], scoped to this connection, profile and company, so a new process restores it
 * as [SduiCheckState.Ambiguous] and never resends it by itself.
 */
internal class SduiHostViewModel(
    private val repository: SduiRepository,
    private val companyId: String,
    private val pendingStore: SduiPendingStore,
    private val newOperationId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(SduiUiState())
    val state: StateFlow<SduiUiState> = mutableState.asStateFlow()

    private var job: Job? = null

    /** Bumped whenever outstanding work is abandoned; results from older work are discarded. */
    private var generation = 0

    /**
     * The release the unresolved request named when it was first dispatched. A refreshed screen on
     * another release never rebinds it; non-null whenever the check state holds an operation id.
     */
    private var pendingReleaseId: String? = null

    /** Entry load. Keeps a pending or accepted check as it is. */
    fun load() = startLoad(userRefresh = false)

    /** Explicit refresh. Also the "fresh display" that lets a new check follow an accepted one. */
    fun refresh() = startLoad(userRefresh = true)

    fun sendCheck() {
        val current = mutableState.value
        val ready = current.phase as? SduiPhase.Ready ?: return
        if (!ready.canCheck) return
        if (current.check != SduiCheckState.Idle && current.check != SduiCheckState.Refused) return
        if (!ensureCurrent()) return
        dispatchCheck(ready.screen, newOperationId(), ready.screen.releaseId, retry = false)
    }

    /**
     * Explicit resend of the unresolved operation. Neither the id nor the release it named is ever
     * regenerated or rebound to the screen that is displayed now.
     */
    fun retryCheck() {
        val current = mutableState.value
        val ready = current.phase as? SduiPhase.Ready ?: return
        val ambiguous = current.check as? SduiCheckState.Ambiguous ?: return
        if (!ready.canCheck) return
        if (!ensureCurrent()) return
        val releaseId = pendingReleaseId
        if (releaseId == null) {
            // An id without its release is not the same request. Do not turn it into a new write.
            mutableState.update { it.copy(check = SduiCheckState.Blocked) }
            return
        }
        dispatchCheck(ready.screen, ambiguous.operationId, releaseId, retry = true)
    }

    /** The connection, profile or client changed. This host is finished; its work is discarded. */
    fun onConnectionChanged() {
        abandonWork()
        mutableState.update {
            it.copy(phase = SduiPhase.Unavailable(SduiUnavailable.CONNECTION_CHANGED), check = it.check.unsent())
        }
    }

    /**
     * Screen disposal: cancel and discard transient work and the displayed screen; keep only the
     * check state. Re-entry loads again and re-verifies the connection, so even a "connection
     * changed" verdict does not outlive the screen that showed it.
     */
    fun release() {
        abandonWork()
        mutableState.update { it.copy(phase = SduiPhase.Idle, check = it.check.unsent()) }
    }

    override fun onCleared() {
        abandonWork()
    }

    private fun startLoad(userRefresh: Boolean) {
        val current = mutableState.value
        if (current.phase is SduiPhase.Loading || current.check is SduiCheckState.Sending) return
        if (current.phase == SduiPhase.Unavailable(SduiUnavailable.CONNECTION_CHANGED)) return
        if (!ensureCurrent()) return
        val mine = ++generation
        mutableState.update { it.copy(phase = SduiPhase.Loading) }
        job =
            viewModelScope.launch {
                // Before anything is shown: an unresolved request from an earlier process comes back first.
                val stored = if (mutableState.value.check.canRestore()) pendingStore.read() else null
                if (!stillValid(mine)) return@launch
                if (stored != null) restore(stored)
                val manifest =
                    when (val fetched = repository.manifest()) {
                        is SduiFetch.Ok -> fetched.value
                        else -> return@launch finishLoad(mine, fetched.unavailable())
                    }
                if (!stillValid(mine)) return@launch
                val screen =
                    when (val fetched = repository.screen(manifest, companyId)) {
                        is SduiFetch.Ok -> fetched.value
                        else -> return@launch finishLoad(mine, fetched.unavailable())
                    }
                if (!stillValid(mine)) return@launch
                mutableState.update {
                    it.copy(
                        phase = SduiPhase.Ready(screen, manifest.canCheck),
                        check = it.check.afterFreshDisplay(userRefresh),
                    )
                }
            }
    }

    private fun finishLoad(
        mine: Int,
        reason: SduiUnavailable,
    ) {
        if (!stillValid(mine)) return
        mutableState.update { it.copy(phase = SduiPhase.Unavailable(reason)) }
        if (reason == SduiUnavailable.CONNECTION_CHANGED) abandonWork()
    }

    private fun dispatchCheck(
        screen: SduiScreen,
        operationId: String,
        releaseId: String,
        retry: Boolean,
    ) {
        val mine = ++generation
        // Set before launching, so a second tap or a cancellation can only ever see "in flight".
        pendingReleaseId = releaseId
        mutableState.update { it.copy(check = SduiCheckState.Sending(operationId, retry)) }
        job =
            viewModelScope.launch {
                // The record is durable BEFORE the request exists. If it cannot be written, nothing is sent.
                if (!pendingStore.commit(SduiPendingCheck(operationId, releaseId))) {
                    if (!stillValid(mine)) return@launch
                    mutableState.update {
                        it.copy(check = if (retry) SduiCheckState.Ambiguous(operationId) else SduiCheckState.Refused)
                    }
                    if (!retry) pendingReleaseId = null
                    return@launch
                }
                if (!stillValid(mine)) return@launch
                val result = repository.check(screen, operationId, releaseId)
                if (!stillValid(mine)) return@launch
                applyCheck(mine, result, operationId, retry)
            }
    }

    private suspend fun applyCheck(
        mine: Int,
        result: SduiCheckResult,
        operationId: String,
        retry: Boolean,
    ) {
        // Only an accepted receipt, or the very first request being refused, settles the operation.
        // A refused, unauthorized or missing answer to a RETRY says nothing about the earlier attempt.
        val firstRefusal = !retry && result.isKnownRefusal()
        val cleared = (result is SduiCheckResult.Accepted || firstRefusal) && pendingStore.clear()
        if (!stillValid(mine)) return
        // If a refusal could not be erased from disk the record stays, and so does the ambiguity.
        val resolved = (result is SduiCheckResult.Accepted) || (firstRefusal && cleared)
        if (resolved) pendingReleaseId = null
        val unresolved = SduiCheckState.Ambiguous(operationId)
        val settled = if (resolved) SduiCheckState.Idle else unresolved
        mutableState.update {
            when (result) {
                is SduiCheckResult.Accepted -> {
                    it.copy(check = SduiCheckState.Accepted(result.receipt))
                }

                SduiCheckResult.Ambiguous -> {
                    it.copy(check = unresolved)
                }

                SduiCheckResult.Refused, SduiCheckResult.OperationConflict -> {
                    it.copy(check = if (resolved) SduiCheckState.Refused else unresolved)
                }

                SduiCheckResult.ReleaseMismatch -> {
                    it.copy(check = SduiCheckState.NeedsReload(if (resolved) null else operationId))
                }

                SduiCheckResult.Unauthorized -> {
                    it.copy(phase = SduiPhase.Unavailable(SduiUnavailable.AUTH_REQUIRED), check = settled)
                }

                SduiCheckResult.NotFound -> {
                    it.copy(phase = SduiPhase.Unavailable(SduiUnavailable.HOST_UNSUPPORTED), check = settled)
                }

                SduiCheckResult.NotSent -> {
                    it.copy(phase = SduiPhase.Unavailable(SduiUnavailable.CONNECTION_CHANGED), check = settled)
                }

                SduiCheckResult.ConnectionChanged -> {
                    it.copy(phase = SduiPhase.Unavailable(SduiUnavailable.CONNECTION_CHANGED), check = unresolved)
                }
            }
        }
        val phase = mutableState.value.phase
        if (phase == SduiPhase.Unavailable(SduiUnavailable.CONNECTION_CHANGED)) abandonWork()
    }

    /** Brings back the record of an earlier process, or of an earlier read that failed. Never sends. */
    private fun restore(read: SduiPendingRead) {
        val restored: SduiCheckState =
            when (read) {
                SduiPendingRead.None -> {
                    pendingReleaseId = null
                    SduiCheckState.Idle
                }

                is SduiPendingRead.Found -> {
                    pendingReleaseId = read.pending.releaseId
                    SduiCheckState.Ambiguous(read.pending.operationId)
                }

                SduiPendingRead.Invalid, SduiPendingRead.Unreadable -> {
                    pendingReleaseId = null
                    SduiCheckState.Blocked
                }
            }
        mutableState.update { if (it.check.canRestore()) it.copy(check = restored) else it }
    }

    private fun SduiCheckState.canRestore(): Boolean = this == SduiCheckState.Idle || this == SduiCheckState.Blocked

    /** Answers that prove the server did not take the request (as far as the first request is concerned). */
    private fun SduiCheckResult.isKnownRefusal(): Boolean =
        when (this) {
            SduiCheckResult.ReleaseMismatch,
            SduiCheckResult.OperationConflict,
            SduiCheckResult.Unauthorized,
            SduiCheckResult.NotFound,
            SduiCheckResult.Refused,
            SduiCheckResult.NotSent,
            -> true

            is SduiCheckResult.Accepted,
            SduiCheckResult.ConnectionChanged,
            SduiCheckResult.Ambiguous,
            -> false
        }

    private fun ensureCurrent(): Boolean {
        if (repository.isCurrent()) return true
        onConnectionChanged()
        return false
    }

    /** Work is applied only if nothing abandoned it and the connection is still the one it started on. */
    private fun stillValid(mine: Int): Boolean {
        if (mine != generation) return false
        if (repository.isCurrent()) return true
        onConnectionChanged()
        return false
    }

    private fun abandonWork() {
        generation++
        job?.cancel()
        job = null
    }

    /** A send that was cancelled or dropped may have reached the server: keep its id, unresolved. */
    private fun SduiCheckState.unsent(): SduiCheckState =
        if (this is SduiCheckState.Sending) SduiCheckState.Ambiguous(operationId) else this

    private fun SduiCheckState.afterFreshDisplay(userRefresh: Boolean): SduiCheckState =
        when (this) {
            is SduiCheckState.NeedsReload -> retained?.let(SduiCheckState::Ambiguous) ?: SduiCheckState.Idle
            SduiCheckState.Refused -> SduiCheckState.Idle
            is SduiCheckState.Accepted -> if (userRefresh) SduiCheckState.Idle else this
            else -> this
        }

    private fun SduiFetch<*>.unavailable(): SduiUnavailable =
        when (this) {
            is SduiFetch.Ok -> {
                SduiUnavailable.LOAD_FAILED
            }

            is SduiFetch.Rejected -> {
                if (reason == SduiRejection.RELEASE_MISMATCH) {
                    SduiUnavailable.RELEASE_CHANGED
                } else {
                    SduiUnavailable.CONTRACT_UNSUPPORTED
                }
            }

            SduiFetch.NotFound -> {
                SduiUnavailable.HOST_UNSUPPORTED
            }

            SduiFetch.Unauthorized -> {
                SduiUnavailable.AUTH_REQUIRED
            }

            SduiFetch.Unavailable -> {
                SduiUnavailable.LOAD_FAILED
            }

            SduiFetch.ConnectionChanged -> {
                SduiUnavailable.CONNECTION_CHANGED
            }
        }
}
