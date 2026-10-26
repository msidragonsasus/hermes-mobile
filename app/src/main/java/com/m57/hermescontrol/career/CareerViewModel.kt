package com.m57.hermescontrol.career

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

data class CareerUiState(
    val initialized: Boolean = false,
    val enabled: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val snapshot: CareerSnapshot? = null,
    val stale: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val pending: CareerPendingOperation? = null,
    val initializationError: String? = null,
)

class CareerViewModel(
    private val repository: CareerRepository,
    private val local: CareerLocalStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CareerUiState())
    val state = mutableState.asStateFlow()

    private var initializing = false

    init {
        restoreLocalState()
    }

    fun retryInitialization() {
        if (state.value.initializationError != null) restoreLocalState()
    }

    // A failed local read is unknown state, never evidence that this scope is disabled.
    // Retry reads the same scope without changing its enable flag or pending operation.
    private fun restoreLocalState(enable: Boolean? = null) {
        if (initializing || state.value.busy || state.value.loading) return
        initializing = true
        mutableState.update { it.copy(initialized = false, initializationError = null, stale = true) }
        viewModelScope.launch {
            try {
                if (enable != null) local.setEnabled(enable)
                val enabled = local.enabled()
                mutableState.update { it.copy(enabled = enabled) }
                if (enabled) {
                    var readFailed = false
                    try {
                        val snapshot = local.cached()
                        mutableState.update { it.copy(snapshot = snapshot) }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        readFailed = true
                    }
                    try {
                        val pending = local.pending()
                        mutableState.update { it.copy(pending = pending) }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        readFailed = true
                    }
                    check(!readFailed)
                } else {
                    mutableState.update { it.copy(snapshot = null, pending = null) }
                }
                mutableState.update { it.copy(initialized = true, error = null) }
                if (enabled) refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val message = "端末内の就活データを読めません。削除せず保持しています。再試行で読み直してください。"
                mutableState.update { it.copy(initializationError = message, error = message) }
            } finally {
                initializing = false
            }
        }
    }

    fun enable(value: Boolean) {
        if (!state.value.initialized) return
        restoreLocalState(value)
    }

    fun refresh() {
        if (!state.value.initialized || !state.value.enabled || state.value.loading) return
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val snapshot = repository.snapshot()
                local.cache(snapshot)
                mutableState.update { it.copy(snapshot = snapshot, stale = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = careerError(error), stale = true) }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    fun send(
        action: String,
        payload: JsonObject,
    ) {
        if (!state.value.initialized || state.value.pending != null || state.value.busy || !state.value.enabled) return
        val operation =
            CareerPendingOperation(
                action,
                if ("operation_id" in
                    payload
                ) {
                    payload
                } else {
                    payload.with("operation_id" to JsonPrimitive(UUID.randomUUID().toString()))
                },
            )
        execute(operation)
    }

    internal fun importShukatsu(result: ShukatsuImportResult) {
        if (!state.value.initialized || state.value.pending != null || state.value.busy || !state.value.enabled) return
        send("import", result.uploadPayload)
    }

    fun retry() {
        if (state.value.initialized && !state.value.busy) state.value.pending?.let(::execute)
    }

    fun discardPending() = discardPendingAndThen {}

    fun discardPendingAndThen(after: () -> Unit) {
        if (!state.value.initialized || state.value.busy) return
        viewModelScope.launch {
            try {
                local.savePending(null)
                mutableState.update { it.copy(pending = null, error = null) }
                after()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = careerError(error)) }
            }
        }
    }

    private fun execute(operation: CareerPendingOperation) {
        if (!state.value.initialized || state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                local.savePending(operation)
                mutableState.update { it.copy(pending = operation) }
                val result = repository.mutate(operation.action, operation.payload)
                local.savePending(null)
                mutableState.update {
                    it.copy(
                        pending = null,
                        message =
                            if (result.text("state") ==
                                "queued"
                            ) {
                                "処理を開始しました。更新で結果を確認できます。"
                            } else {
                                "保存しました。"
                            },
                    )
                }
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = careerError(error)) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
