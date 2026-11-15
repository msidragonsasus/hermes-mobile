package com.m57.hermescontrol.worker

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.ws.ChangeEvents
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.ui.common.refreshOnChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkUiState(
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

    suspend fun manageScheduledJob(
        action: String,
        name: String,
    ): Result<Unit>
}

enum class EscrowDecisionOutcome {
    ACCEPTED,
    UNAVAILABLE,
    FAILED,
}

object DefaultWorkGatewayClient : WorkGatewayClient {
    private const val WS_TIMEOUT_MS = 12_000L

    override suspend fun fetchPendingEscrows(): Result<List<EscrowRecord>> =
        runCatching {
            val response = ApiClient.hermesApi.getWorkerEscrows(status = "pending")
            check(response.isSuccessful) { "HTTP ${response.code()}" }
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
        name: String,
    ): Result<Unit> =
        runCatching {
            HermesWsClient
                .request(
                    WsMethods.CRON_MANAGE,
                    mapOf("action" to action, "name" to name),
                    WS_TIMEOUT_MS,
                ).await()
        }.map {}
}

class WorkViewModel(
    private val gateway: WorkGatewayClient = DefaultWorkGatewayClient,
    subscribeCronChanges: Boolean = true,
) : ViewModel() {
    private val _uiState = MutableStateFlow(WorkUiState())
    val uiState: StateFlow<WorkUiState> = _uiState.asStateFlow()

    init {
        if (subscribeCronChanges) {
            refreshOnChange(
                eventType = ChangeEvents.CRON,
                apiCall = {
                    val res = gateway.listScheduledJobs()
                    res.fold(
                        onSuccess = { NetworkResult.Success(it) },
                        onFailure = { error ->
                            if (error is CancellationException) throw error
                            NetworkResult.Failure(NetworkError.Unknown("refresh failed", error))
                        },
                    )
                },
                onSuccess = { jobs ->
                    _uiState.update { it.copy(scheduledJobs = jobs) }
                },
            )
        }
    }

    fun refresh() {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    fatalErrorRes = null,
                    partialErrorRes = null,
                )
            }
            val (escrowsRes, subagentsRes, jobsRes) =
                coroutineScope {
                    val escrowsDeferred = async { safeCall { gateway.fetchPendingEscrows() } }
                    val subagentsDeferred = async { safeCall { gateway.listSubagents() } }
                    val jobsDeferred = async { safeCall { gateway.listScheduledJobs() } }
                    Triple(
                        escrowsDeferred.await(),
                        subagentsDeferred.await(),
                        jobsDeferred.await(),
                    )
                }

            val allFailed = escrowsRes.isFailure && subagentsRes.isFailure && jobsRes.isFailure
            val anyFailed = escrowsRes.isFailure || subagentsRes.isFailure || jobsRes.isFailure

            _uiState.update { current ->
                val escrows = escrowsRes.getOrDefault(current.escrows)
                val subagents = subagentsRes.getOrDefault(current.subagents)
                val jobs = jobsRes.getOrDefault(current.scheduledJobs)
                val emptyAfter = escrows.isEmpty() && subagents.isEmpty() && jobs.isEmpty()
                current.copy(
                    isLoading = false,
                    escrows = escrows,
                    subagents = subagents,
                    scheduledJobs = jobs,
                    fatalErrorRes = if (allFailed && emptyAfter) R.string.worker_work_error_load else null,
                    partialErrorRes =
                        if (anyFailed && !(allFailed && emptyAfter)) {
                            R.string.worker_work_error_partial
                        } else {
                            null
                        },
                )
            }
        }
    }

    fun decideEscrow(
        record: EscrowRecord,
        decision: EscrowDecision,
    ) {
        if (record.id in _uiState.value.busyEscrowIds) return
        val request = WorkerDecisionRequests.build(decision, record.digest, record.display.deny_only)
        if (request == null) {
            _uiState.update { it.copy(toastMessageRes = R.string.worker_approval_unavailable) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(busyEscrowIds = it.busyEscrowIds + record.id) }
            val outcome = gateway.decideEscrow(record.id, request)
            when (outcome) {
                EscrowDecisionOutcome.ACCEPTED -> {
                    val toast =
                        if (decision == EscrowDecision.APPROVE) {
                            R.string.worker_approval_approved
                        } else {
                            R.string.worker_approval_denied
                        }
                    _uiState.update { state ->
                        state.copy(
                            escrows = state.escrows.filterNot { it.id == record.id },
                            busyEscrowIds = state.busyEscrowIds - record.id,
                            toastMessageRes = toast,
                        )
                    }
                }

                EscrowDecisionOutcome.UNAVAILABLE -> {
                    _uiState.update { state ->
                        state.copy(
                            escrows = state.escrows.filterNot { it.id == record.id },
                            busyEscrowIds = state.busyEscrowIds - record.id,
                            toastMessageRes = R.string.worker_approval_unavailable,
                        )
                    }
                }

                EscrowDecisionOutcome.FAILED -> {
                    _uiState.update { state ->
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
        if (subagentId in _uiState.value.busySubagentIds) return
        viewModelScope.launch {
            _uiState.update { it.copy(busySubagentIds = it.busySubagentIds + subagentId) }
            val result = safeCall { gateway.interruptSubagent(subagentId) }
            if (result.isSuccess) {
                val updated = safeCall { gateway.listSubagents() }
                _uiState.update { state ->
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
                _uiState.update { state ->
                    state.copy(
                        busySubagentIds = state.busySubagentIds - subagentId,
                        toastMessageRes = R.string.worker_action_failed,
                    )
                }
            }
        }
    }

    fun toggleJobPause(job: WorkerScheduledJob) {
        if (job.name in _uiState.value.busyJobNames) return
        val action = if (job.enabled) "pause" else "resume"
        viewModelScope.launch {
            _uiState.update { it.copy(busyJobNames = it.busyJobNames + job.name) }
            val result = safeCall { gateway.manageScheduledJob(action, job.name) }
            if (result.isSuccess) {
                val updated = safeCall { gateway.listScheduledJobs() }
                _uiState.update { state ->
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
                _uiState.update { state ->
                    state.copy(
                        busyJobNames = state.busyJobNames - job.name,
                        toastMessageRes = R.string.worker_action_failed,
                    )
                }
            }
        }
    }

    fun requestDeleteJob(job: WorkerScheduledJob) {
        _uiState.update { it.copy(deleteTarget = job) }
    }

    fun dismissDeleteJob() {
        _uiState.update { it.copy(deleteTarget = null) }
    }

    fun confirmDeleteJob() {
        val target = _uiState.value.deleteTarget ?: return
        _uiState.update { it.copy(deleteTarget = null) }
        if (target.name in _uiState.value.busyJobNames) return
        viewModelScope.launch {
            _uiState.update { it.copy(busyJobNames = it.busyJobNames + target.name) }
            val result = safeCall { gateway.manageScheduledJob("remove", target.name) }
            if (result.isSuccess) {
                val updated = safeCall { gateway.listScheduledJobs() }
                _uiState.update { state ->
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
                _uiState.update { state ->
                    state.copy(
                        busyJobNames = state.busyJobNames - target.name,
                        toastMessageRes = R.string.worker_action_failed,
                    )
                }
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessageRes = null) }
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
