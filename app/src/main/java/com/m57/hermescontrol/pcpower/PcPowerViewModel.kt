package com.m57.hermescontrol.pcpower

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

data class PcPowerUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val status: PcPowerStatus? = null,
    val error: String? = null,
    val message: String? = null,
    val statusStale: Boolean = false,
    val checkingTransition: Boolean = false,
    val statusError: String? = null,
    val refreshNote: String? = null,
) {
    val canWake: Boolean
        get() = !busy && !loading && !checkingTransition && !statusStale && status?.wake_available == true

    val canSleep: Boolean
        get() =
            !busy && !loading && !checkingTransition && !statusStale &&
                status?.power_state == "awake" && status.sleep_available

    val canRefresh: Boolean
        get() = !busy && !loading && !checkingTransition
}

class PcPowerViewModel(
    private val repository: PcPowerRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PcPowerUiState())
    val state = mutableState.asStateFlow()
    private var screenResumed = false
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L

    fun onScreenResumed() {
        if (screenResumed) return
        screenResumed = true
        refresh()
    }

    fun onScreenPaused() {
        screenResumed = false
        refreshGeneration++
        refreshJob?.cancel()
        refreshJob = null
        mutableState.update { it.copy(loading = false, checkingTransition = false, statusStale = true) }
    }

    fun refresh() {
        if (!screenResumed || state.value.busy || refreshJob?.isActive == true) return
        startRefresh()
    }

    fun wake() = execute(PowerAction.WAKE)

    fun sleep() = execute(PowerAction.SLEEP)

    private fun startRefresh(action: PowerAction? = null) {
        val generation = ++refreshGeneration
        mutableState.update {
            it.copy(
                loading = true,
                statusStale = true,
                checkingTransition = action != null,
                statusError = null,
                refreshNote = null,
            )
        }
        refreshJob =
            viewModelScope.launch {
                try {
                    val attempts = if (action == null) 1 else POST_ACTION_ATTEMPTS
                    repeat(attempts) { attempt ->
                        if (attempt > 0) delay(POST_ACTION_INTERVAL_MS)
                        if (!readStatus(generation)) return@launch
                        val observed = state.value.status?.power_state
                        if (action == null || observed == action.expectedState) return@launch
                    }
                    if (generation == refreshGeneration) {
                        mutableState.update {
                            it.copy(refreshNote = "状態の変化を確認できませんでした。少し待って「状態を更新」を押してください。")
                        }
                    }
                } finally {
                    if (generation == refreshGeneration) {
                        mutableState.update { it.copy(loading = false, checkingTransition = false) }
                    }
                }
            }
    }

    private suspend fun readStatus(generation: Long): Boolean {
        mutableState.update { it.copy(loading = true, statusStale = true) }
        return try {
            val status = withTimeout(STATUS_TIMEOUT_MS) { repository.status() }
            currentCoroutineContext().ensureActive()
            if (generation != refreshGeneration) return false
            mutableState.update { it.copy(status = status, statusStale = false, statusError = null) }
            true
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            currentCoroutineContext().ensureActive()
            if (generation == refreshGeneration) {
                mutableState.update { it.copy(statusStale = true, statusError = pcPowerError(error)) }
            }
            false
        } finally {
            if (generation == refreshGeneration) mutableState.update { it.copy(loading = false) }
        }
    }

    private fun execute(action: PowerAction) {
        if (!screenResumed) return
        if (action == PowerAction.WAKE && !state.value.canWake) return
        if (action == PowerAction.SLEEP && !state.value.canSleep) return
        // Reserve synchronously: repeated taps cannot queue another power request.
        mutableState.update {
            it.copy(busy = true, statusStale = true, error = null, message = null, refreshNote = null)
        }
        viewModelScope.launch {
            try {
                when (action) {
                    PowerAction.WAKE -> repository.wake()
                    PowerAction.SLEEP -> repository.sleep(UUID.randomUUID().toString())
                }
                mutableState.update {
                    it.copy(
                        message =
                            when (action) {
                                PowerAction.WAKE -> "起動信号を送信しました。PCの状態を自動で確認します。"
                                PowerAction.SLEEP -> "対象PCがスリープ要求を受理しました。PCの状態を自動で確認します。"
                            },
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = pcPowerError(error)) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
            // A failed response may still have caused an effect. Only read status; never resend the action.
            if (screenResumed) startRefresh(action)
        }
    }

    private enum class PowerAction(
        val expectedState: String,
    ) {
        WAKE("awake"),
        SLEEP("asleep_or_unreachable"),
    }

    private companion object {
        const val POST_ACTION_ATTEMPTS = 7
        const val POST_ACTION_INTERVAL_MS = 5_000L
        const val STATUS_TIMEOUT_MS = 5_000L
    }
}
