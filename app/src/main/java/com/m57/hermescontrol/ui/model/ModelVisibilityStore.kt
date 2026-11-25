package com.m57.hermescontrol.ui.model

import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Uses the existing encrypted UI store, scoped by connection. Every bot shares one picker. */
class ModelVisibilityStore(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    private val states = mutableMapOf<String, MutableStateFlow<ModelVisibility>>()

    @Synchronized
    private fun state(scope: String): MutableStateFlow<ModelVisibility> =
        states.getOrPut(scope) {
            MutableStateFlow(read(scope)?.let { Json.decodeFromString<ModelVisibility>(it) } ?: ModelVisibility())
        }

    @Synchronized
    fun observe(scope: String): StateFlow<ModelVisibility> = state(scope).asStateFlow()

    @Synchronized
    fun update(
        scope: String,
        transform: (ModelVisibility) -> ModelVisibility,
    ) {
        val state = state(scope)
        val next = transform(state.value)
        // Publish only after persistence succeeds. A failed write retains the user's previous choices.
        write(scope, Json.encodeToString(next))
        state.value = next
    }

    companion object {
        private const val NAME = "model_visibility"
        private const val LEGACY_SEPARATOR = "\n"

        val shared =
            ModelVisibilityStore(
                read = { scope ->
                    // Before main was chosen explicitly, its key carried an empty profile id.
                    AuthManager.readUiPreference(scope, NAME)
                        ?: scope
                            .takeIf { it.endsWith(LEGACY_SEPARATOR + AppScope.PROFILE) }
                            ?.let { AuthManager.readUiPreference(it.removeSuffix(AppScope.PROFILE), NAME) }
                },
                write = { scope, value -> AuthManager.writeUiPreference(scope, NAME, value) },
            )
    }
}
