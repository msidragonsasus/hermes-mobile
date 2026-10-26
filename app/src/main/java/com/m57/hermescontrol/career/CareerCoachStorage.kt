package com.m57.hermescontrol.career

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

internal data class CareerCoachStore(
    val local: CareerLocalStore,
    val key: String? = null,
)

/** In-process handover only: a migrated draft invalidates any older screen's writer. */
internal object CareerCoachStorage {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val versions = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val unsavedWriters = ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>>()
    val generations = versions.asStateFlow()

    fun generation(key: String?): Long = key?.let { versions.value[it] } ?: 0

    fun editing(
        key: String?,
        writer: String,
        active: Boolean,
    ) {
        if (key == null) return
        if (active) {
            unsavedWriters.getOrPut(key) { ConcurrentHashMap() }[writer] = true
        } else {
            unsavedWriters[key]?.remove(writer)
        }
    }

    fun hasEdits(key: String?): Boolean = key != null && unsavedWriters[key]?.isNotEmpty() == true

    fun migrated(key: String?) {
        if (key != null) versions.update { it + (key to ((it[key] ?: 0) + 1)) }
    }

    suspend fun <T> locked(
        key: String?,
        block: suspend () -> T,
    ): T = if (key == null) block() else locks.getOrPut(key) { Mutex() }.withLock { block() }
}
