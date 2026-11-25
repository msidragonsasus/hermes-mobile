package com.m57.hermescontrol.worker

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.ws.ChangeEventHub
import com.m57.hermescontrol.data.ws.ChangeEvents
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.home.HomeFailure
import com.m57.hermescontrol.home.homeFailureOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

internal sealed interface WorkerSourceStatus {
    data object Unknown : WorkerSourceStatus

    data object Ok : WorkerSourceStatus

    data object Unavailable : WorkerSourceStatus

    data class Failed(
        val failure: HomeFailure,
    ) : WorkerSourceStatus
}

internal data class WorkUiState(
    val isLoading: Boolean = false,
    val escrows: List<EscrowRecord> = emptyList(),
    val subagents: List<WorkerSubagent> = emptyList(),
    val scheduledJobs: List<WorkerScheduledJob> = emptyList(),
    @param:StringRes val fatalErrorRes: Int? = null,
    @param:StringRes val partialErrorRes: Int? = null,
    @param:StringRes val toastMessageRes: Int? = null,
    val busyEscrowIds: Set<String> = emptySet(),
    val busySubagentIds: Set<String> = emptySet(),
    val busyJobNames: Set<String> = emptySet(),
    val approvalsStatus: WorkerSourceStatus = WorkerSourceStatus.Unknown,
    val routinesStatus: WorkerSourceStatus = WorkerSourceStatus.Unknown,
    val deleteTarget: WorkerScheduledJob? = null,
) {
    val isAllEmpty: Boolean
        get() = escrows.isEmpty() && subagents.isEmpty() && scheduledJobs.isEmpty()
}

interface WorkGatewayClient {
    suspend fun fetchPendingEscrows(): Result<List<EscrowRecord>>

    suspend fun decideEscrow(
        escrowId: String,
        request: EscrowDecisionRequest,
    ): EscrowDecisionOutcome

    suspend fun listSubagents(): Result<List<WorkerSubagent>>

    suspend fun interruptSubagent(subagentId: String): Result<Unit>

    suspend fun listScheduledJobs(): Result<List<WorkerScheduledJob>>

    /** [jobId] is the server job id; `cron.manage` reads it from its `name` param. */
    suspend fun manageScheduledJob(
        action: String,
        jobId: String,
    ): Result<Unit>
}

enum class EscrowDecisionOutcome {
    ACCEPTED,
    UNAVAILABLE,
    FAILED,
}

object DefaultWorkGatewayClient : WorkGatewayClient {
    private const val WS_TIMEOUT_MS = 12_000L
    private const val SESSION_NOT_OWNED = 4001

    override suspend fun fetchPendingEscrows(): Result<List<EscrowRecord>> =
        runCatching {
            val response = ApiClient.hermesApi.getWorkerEscrows(status = "pending")
            if (!response.isSuccessful) throw HttpException(response)
            response.body()?.items.orEmpty()
        }

    override suspend fun decideEscrow(
        escrowId: String,
        request: EscrowDecisionRequest,
    ): EscrowDecisionOutcome =
        runCatching {
            val response = ApiClient.hermesApi.decideWorkerEscrow(escrowId, request)
            val body = response.body()
            when {
                response.isSuccessful && body?.ok == true -> {
                    EscrowDecisionOutcome.ACCEPTED
                }

                response.code() == 409 || (response.isSuccessful && body?.ok == false) -> {
                    EscrowDecisionOutcome.UNAVAILABLE
                }

                else -> {
                    EscrowDecisionOutcome.FAILED
                }
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            EscrowDecisionOutcome.FAILED
        }

    override suspend fun listSubagents(): Result<List<WorkerSubagent>> =
        runCatching {
            val raw = HermesWsClient.request(WsMethods.SUBAGENT_LIST, emptyMap(), WS_TIMEOUT_MS).await()
            WorkerWsParsers.parseSubagents(raw)
        }.recoverCatching { error ->
            // subagent.list is scoped to a live session this client owns; with none, nothing is visible.
            if (error is HermesWsClient.HermesRpcException && error.code == SESSION_NOT_OWNED) {
                emptyList()
            } else {
                throw error
            }
        }

    override suspend fun interruptSubagent(subagentId: String): Result<Unit> =
        runCatching {
            HermesWsClient
                .request(
                    WsMethods.SUBAGENT_INTERRUPT,
                    mapOf("subagent_id" to subagentId),
                    WS_TIMEOUT_MS,
                ).await()
        }.map {}

    override suspend fun listScheduledJobs(): Result<List<WorkerScheduledJob>> =
        runCatching {
            val raw =
                HermesWsClient
                    .request(
                        WsMethods.CRON_MANAGE,
                        mapOf("action" to "list", "include_disabled" to true),
                        WS_TIMEOUT_MS,
                    ).await()
            WorkerWsParsers.parseCronManageJobs(raw)
        }

    override suspend fun manageScheduledJob(
        action: String,
        jobId: String,
    ): Result<Unit> =
        runCatching {
            HermesWsClient
                .request(
                    WsMethods.CRON_MANAGE,
                    mapOf("action" to action, "name" to jobId),
                    WS_TIMEOUT_MS,
                ).await()
        }.map {}
}

class WorkViewModel(
    private val gateway: WorkGatewayClient = DefaultWorkGatewayClient,
    subscribeCronChanges: Boolean = true,
    private val scopeKey: () -> String = { AppScope.connectionKey() },
    private val epochs: Flow<Long> = AuthManager.connectionEpoch,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(WorkUiState())
    internal val uiState: StateFlow<WorkUiState> = mutableUiState.asStateFlow()
    private var lastScopeKey = scopeKey()
    private var observedEpoch = (epochs as? StateFlow<Long>)?.value ?: 0L
    private var lastEpoch = observedEpoch
    private val epoch get() = (epochs as? StateFlow<Long>)?.value ?: observedEpoch
    private var refreshJob: Job? = null
    private var rerunRequested = false

    init {
        viewModelScope.launch {
            epochs.collect { value ->
                observedEpoch = value
                if (value % 2L != 0L) return@collect
                val changed = value != lastEpoch || scopeKey() != lastScopeKey
                lastEpoch = value
                if (changed) refresh()
            }
        }
        if (subscribeCronChanges) {
            viewModelScope.launch {
                ChangeEventHub.events.filter { it.type == ChangeEvents.CRON }.collect { refresh() }
            }
        }
    }

    fun refresh() {
        requestRefresh()
    }

    internal fun ownsScope(key: String): Boolean = lastScopeKey == key

    internal suspend fun refreshAndAwait() {
        requestRefresh()?.join()
    }

    private fun requestRefresh(): Job? {
        if (epoch % 2L != 0L) return null
        val key = scopeKey()
        if (key != lastScopeKey) {
            lastScopeKey = key
            refreshJob?.cancel()
            refreshJob = null
            rerunRequested = false
            mutableUiState.value = WorkUiState()
        }
        refreshJob?.takeIf { it.isActive }?.let {
            rerunRequested = true
            return it
        }
        rerunRequested = false
        val job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                mutableUiState.update { it.copy(isLoading = true, fatalErrorRes = null, partialErrorRes = null) }
                try {
                    while (key == scopeKey() && epoch % 2L == 0L) {
                        refreshPass(key, epoch)
                        ensureActive()
                        if (!rerunRequested) break
                        rerunRequested = false
                    }
                } finally {
                    if (refreshJob === coroutineContext[Job]) mutableUiState.update { it.copy(isLoading = false) }
                }
            }
        refreshJob = job
        job.start()
        return job
    }

    private suspend fun refreshPass(
        key: String,
        requestEpoch: Long,
    ) {
        val (escrowsRes, subagentsRes, jobsRes) =
            coroutineScope {
                val escrows = async { safeCall { gateway.fetchPendingEscrows() } }
                val subagents = async { safeCall { gateway.listSubagents() } }
                val jobs = async { safeCall { gateway.listScheduledJobs() } }
                Triple(escrows.await(), subagents.await(), jobs.await())
            }
        if (scopeKey() != key || epoch != requestEpoch) return
        val approvalsStatus = escrowsRes.sourceStatus()
        val routinesStatus = jobsRes.sourceStatus()
        val allFailed =
            approvalsStatus is WorkerSourceStatus.Failed && subagentsRes.isFailure &&
                routinesStatus is WorkerSourceStatus.Failed
        val anyFailed =
            approvalsStatus is WorkerSourceStatus.Failed || subagentsRes.isFailure ||
                routinesStatus is WorkerSourceStatus.Failed

        mutableUiState.update { current ->
            val escrows =
                escrowsRes.getOrElse {
                    if (approvalsStatus == WorkerSourceStatus.Unavailable) emptyList() else current.escrows
                }
            val subagents = subagentsRes.getOrDefault(current.subagents)
            val jobs =
                jobsRes.getOrElse {
                    if (routinesStatus == WorkerSourceStatus.Unavailable) emptyList() else current.scheduledJobs
                }
            val fatal = allFailed && escrows.isEmpty() && subagents.isEmpty() && jobs.isEmpty()
            current.copy(
                escrows = escrows,
                subagents = subagents,
                scheduledJobs = jobs,
                approvalsStatus = approvalsStatus,
                routinesStatus = routinesStatus,
                fatalErrorRes = R.string.worker_work_error_load.takeIf { fatal },
                partialErrorRes = R.string.worker_work_error_partial.takeIf { anyFailed && !fatal },
            )
        }
    }

    private fun Result<*>.sourceStatus(): WorkerSourceStatus {
        val error = exceptionOrNull() ?: return WorkerSourceStatus.Ok
        if (error is CancellationException) throw error
        val failure = homeFailureOf(error)
        return if (error is HttpException && error.code() in setOf(404, 405, 501) &&
            failure is HomeFailure.ServerOutdated
        ) {
            WorkerSourceStatus.Unavailable
        } else {
            WorkerSourceStatus.Failed(failure)
        }
    }

    fun decideEscrow(
        record: EscrowRecord,
        decision: EscrowDecision,
    ) {
        if (record.id in mutableUiState.value.busyEscrowIds) return
        val request = WorkerDecisionRequests.build(decision, record.digest, record.display.deny_only)
        if (request == null) {
            mutableUiState.update { it.copy(toastMessageRes = R.string.worker_approval_unavailable) }
            return
        }

        val key = lastScopeKey
        if (key != scopeKey() || epoch % 2L != 0L) return
        viewModelScope.launch {
            if (key != scopeKey() || epoch % 2L != 0L) return@launch
            mutableUiState.update { it.copy(busyEscrowIds = it.busyEscrowIds + record.id) }
            val outcome = gateway.decideEscrow(record.id, request)
            if (key != scopeKey()) return@launch
            when (outcome) {
                EscrowDecisionOutcome.ACCEPTED -> {
                    val toast =
                        if (decision == EscrowDecision.APPROVE) {
                            R.string.worker_approval_approved
                        } else {
                            R.string.worker_approval_denied
                        }
                    mutableUiState.update { state ->
                        state.copy(
                            escrows = state.escrows.filterNot { it.id == record.id },
                            busyEscrowIds = state.busyEscrowIds - record.id,
                            toastMessageRes = toast,
                        )
                    }
                }

                EscrowDecisionOutcome.UNAVAILABLE -> {
                    mutableUiState.update { state ->
                        state.copy(
                            escrows = state.escrows.filterNot { it.id == record.id },
                            busyEscrowIds = state.busyEscrowIds - record.id,
                            toastMessageRes = R.string.worker_approval_unavailable,
                        )
                    }
                }

                EscrowDecisionOutcome.FAILED -> {
                    mutableUiState.update { state ->
                        state.copy(
                            busyEscrowIds = state.busyEscrowIds - record.id,
                            toastMessageRes = R.string.worker_approval_send_failed,
                        )
                    }
                }
            }
        }
    }

    fun interruptSubagent(subagentId: String) {
        if (subagentId in mutableUiState.value.busySubagentIds) return
        val key = lastScopeKey
        if (key != scopeKey() || epoch % 2L != 0L) return
        viewModelScope.launch {
            if (key != scopeKey() || epoch % 2L != 0L) return@launch
            mutableUiState.update { it.copy(busySubagentIds = it.busySubagentIds + subagentId) }
            val result = safeCall { gateway.interruptSubagent(subagentId) }
            if (key != scopeKey()) return@launch
            if (result.isSuccess) {
                val updated = safeCall { gateway.listSubagents() }
                if (key != scopeKey()) return@launch
                mutableUiState.update { state ->
                    state.copy(
                        subagents =
                            updated.getOrElse {
                                state.subagents.filterNot { it.subagentId == subagentId }
                            },
                        busySubagentIds = state.busySubagentIds - subagentId,
                        toastMessageRes = R.string.worker_live_stopped,
                    )
                }
            } else {
                mutableUiState.update { state ->
                    state.copy(
                        busySubagentIds = state.busySubagentIds - subagentId,
                        toastMessageRes = R.string.worker_action_failed,
                    )
                }
            }
        }
    }

    fun toggleJobPause(job: WorkerScheduledJob) {
        if (job.name in mutableUiState.value.busyJobNames) return
        val action = if (job.enabled) "pause" else "resume"
        val key = lastScopeKey
        if (key != scopeKey() || epoch % 2L != 0L) return
        viewModelScope.launch {
            if (key != scopeKey() || epoch % 2L != 0L) return@launch
            mutableUiState.update { it.copy(busyJobNames = it.busyJobNames + job.name) }
            val result = safeCall { gateway.manageScheduledJob(action, job.id) }
            if (key != scopeKey()) return@launch
            if (result.isSuccess) {
                val updated = safeCall { gateway.listScheduledJobs() }
                if (key != scopeKey()) return@launch
                mutableUiState.update { state ->
                    state.copy(
                        scheduledJobs =
                            updated.getOrElse {
                                state.scheduledJobs.map {
                                    if (it.name == job.name) it.copy(enabled = !job.enabled) else it
                                }
                            },
                        busyJobNames = state.busyJobNames - job.name,
                        toastMessageRes =
                            if (job.enabled) {
                                R.string.worker_schedule_paused
                            } else {
                                R.string.worker_schedule_resumed
                            },
                    )
                }
            } else {
                mutableUiState.update { state ->
                    state.copy(
                        busyJobNames = state.busyJobNames - job.name,
                        toastMessageRes = R.string.worker_action_failed,
                    )
                }
            }
        }
    }

    fun requestDeleteJob(job: WorkerScheduledJob) {
        mutableUiState.update { it.copy(deleteTarget = job) }
    }

    fun dismissDeleteJob() {
        mutableUiState.update { it.copy(deleteTarget = null) }
    }

    fun confirmDeleteJob() {
        val target = mutableUiState.value.deleteTarget ?: return
        mutableUiState.update { it.copy(deleteTarget = null) }
        if (target.name in mutableUiState.value.busyJobNames) return
        val key = lastScopeKey
        if (key != scopeKey() || epoch % 2L != 0L) return
        viewModelScope.launch {
            if (key != scopeKey() || epoch % 2L != 0L) return@launch
            mutableUiState.update { it.copy(busyJobNames = it.busyJobNames + target.name) }
            val result = safeCall { gateway.manageScheduledJob("remove", target.id) }
            if (key != scopeKey()) return@launch
            if (result.isSuccess) {
                val updated = safeCall { gateway.listScheduledJobs() }
                if (key != scopeKey()) return@launch
                mutableUiState.update { state ->
                    state.copy(
                        scheduledJobs =
                            updated.getOrElse {
                                state.scheduledJobs.filterNot { it.name == target.name }
                            },
                        busyJobNames = state.busyJobNames - target.name,
                        toastMessageRes = R.string.worker_schedule_deleted,
                    )
                }
            } else {
                mutableUiState.update { state ->
                    state.copy(
                        busyJobNames = state.busyJobNames - target.name,
                        toastMessageRes = R.string.worker_action_failed,
                    )
                }
            }
        }
    }

    fun clearToast() {
        mutableUiState.update { it.copy(toastMessageRes = null) }
    }

    private suspend inline fun <T> safeCall(block: () -> Result<T>): Result<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
}
