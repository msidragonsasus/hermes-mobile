package com.m57.hermescontrol.career

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class CareerNoteState(
    val body: String,
    val version: Int,
    val loaded: Boolean = false,
    val savedLocally: Boolean = false,
    val error: String? = null,
)

/** Owned by the navigation entry, so changing company tabs cannot cancel a draft write. */
internal class CareerNoteViewModel(
    private val company: CareerEntity,
    private val local: CareerLocalStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CareerNoteState(company.data.text("notes").orEmpty(), company.version))
    val state = mutableState.asStateFlow()
    private val writes = Mutex()
    private val storageKey = "company-note/${company.id}"

    init {
        viewModelScope.launch {
            try {
                val saved = local.uiValue(storageKey)?.let { Json.parseToJsonElement(it) as JsonObject }
                mutableState.update {
                    it.copy(
                        body = saved?.text("body") ?: it.body,
                        version = saved?.text("version")?.toIntOrNull() ?: it.version,
                        loaded = true,
                        savedLocally = true,
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = careerError(error)) }
            }
        }
    }

    fun change(body: String) {
        if (!state.value.loaded) return
        mutableState.update { it.copy(body = body, savedLocally = false) }
        persist()
    }

    fun acknowledge(entity: CareerEntity?) {
        if (entity != null && entity.data.text("notes").orEmpty() == state.value.body &&
            entity.version != state.value.version
        ) {
            mutableState.update { it.copy(version = entity.version) }
            persist()
        }
    }

    fun useReviewedVersion(version: Int) {
        if (!state.value.loaded) return
        mutableState.update { it.copy(version = version, savedLocally = false) }
        persist()
    }

    fun persist(after: (() -> Unit)? = null) {
        if (!state.value.loaded) return
        viewModelScope.launch {
            writes.withLock {
                val draft = state.value
                try {
                    local.saveUiValue(
                        storageKey,
                        buildJsonObject {
                            put("body", draft.body)
                            put("version", draft.version)
                        }.toString(),
                    )
                    mutableState.update { it.copy(savedLocally = it.body == draft.body, error = null) }
                    after?.invoke()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableState.update { it.copy(savedLocally = false, error = careerError(error)) }
                }
            }
        }
    }
}
