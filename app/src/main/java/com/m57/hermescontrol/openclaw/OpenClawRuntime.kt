package com.m57.hermescontrol.openclaw

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Application-owned connection: leaving a Compose destination must not retire the socket. */
internal object OpenClawRuntime {
    private val models = ViewModelStore()
    private var owner: String? = null
    var active: OpenClawViewModel? = null
        private set
    private val foregroundFlow = MutableStateFlow(true)
    val foregroundState = foregroundFlow.asStateFlow()
    val foreground: Boolean get() = foregroundFlow.value
    var visibleSession: String? = null

    fun model(application: Application): OpenClawViewModel {
        val scope = OpenClawViewModel.ownerScope()
        if (owner != scope) {
            models.clear()
            active = null
            owner = scope
        }
        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    OpenClawViewModel(application, scope) as T
            }
        return ViewModelProvider(models, factory)[OpenClawViewModel::class.java].also { active = it }
    }

    fun setForeground(value: Boolean) {
        foregroundFlow.value = value
        if (value) active?.ensureConnected()
    }
}
