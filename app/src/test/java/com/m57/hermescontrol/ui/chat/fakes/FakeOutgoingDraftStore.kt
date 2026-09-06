package com.m57.hermescontrol.ui.chat.fakes

import com.m57.hermescontrol.data.local.OutgoingDraftStore
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.OutgoingDraft
import com.m57.hermescontrol.domain.hermes.draftTargetKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeOutgoingDraftStore : OutgoingDraftStore {
    val drafts = MutableStateFlow<List<OutgoingDraft>>(emptyList())
    var failSave = false

    override fun observe(scope: ConnectionScope) =
        drafts.map { list ->
            list.filter {
                it.targetKey ==
                    scope.draftTargetKey()
            }
        }

    override suspend fun save(draft: OutgoingDraft) {
        check(!failSave) { "Synthetic storage unavailable" }
        drafts.value = drafts.value.filterNot { it.id == draft.id } + draft
    }

    override suspend fun delete(
        scope: ConnectionScope,
        id: String,
    ) {
        drafts.value = drafts.value.filterNot { it.id == id && it.targetKey == scope.draftTargetKey() }
    }
}
