package com.m57.hermescontrol.data.local

import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.OutgoingDraft
import com.m57.hermescontrol.domain.hermes.draftTargetKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface OutgoingDraftStore {
    fun observe(scope: ConnectionScope): Flow<List<OutgoingDraft>>

    suspend fun save(draft: OutgoingDraft)

    suspend fun delete(
        scope: ConnectionScope,
        id: String,
    )
}

class OutgoingDraftRepository(
    private val dao: OutgoingDraftDao,
) : OutgoingDraftStore {
    private val json = Json { ignoreUnknownKeys = false }

    override fun observe(scope: ConnectionScope): Flow<List<OutgoingDraft>> =
        dao.observe(scope.draftTargetKey()).map { rows ->
            rows.map { row ->
                // Corruption must surface as a storage failure, never an empty successful load.
                json.decodeFromString<OutgoingDraft>(row.payload).also {
                    require(it.id == row.id && it.targetKey == row.targetKey && it.createdAt == row.createdAt)
                }
            }
        }

    override suspend fun save(draft: OutgoingDraft) {
        require(draft.id.isNotBlank() && draft.scope.serverIdentity.isNotBlank())
        dao.save(OutgoingDraftEntity(draft.id, draft.targetKey, draft.createdAt, json.encodeToString(draft)))
    }

    override suspend fun delete(
        scope: ConnectionScope,
        id: String,
    ) = dao.delete(id, scope.draftTargetKey())
}
