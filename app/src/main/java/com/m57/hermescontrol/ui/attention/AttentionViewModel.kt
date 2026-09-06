package com.m57.hermescontrol.ui.attention

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.HermesControlApp
import com.m57.hermescontrol.domain.hermes.RequestRef
import kotlinx.coroutines.launch

/** Activity-scoped response jobs survive tab changes and configuration recreation. */
class AttentionViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val coordinator = (application as HermesControlApp).attentionCoordinator

    fun approve(
        ref: RequestRef,
        choice: String,
    ) {
        viewModelScope.launch { coordinator.respondApproval(ref, choice) }
    }

    fun clarify(
        ref: RequestRef,
        answer: String,
    ) {
        viewModelScope.launch { coordinator.respondClarification(ref, answer) }
    }
}
